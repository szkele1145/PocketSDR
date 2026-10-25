package com.pocketsdr.app

import android.media.AudioFormat
import android.media.AudioTrack
import com.pocketsdr.app.dsp.Biquad
import com.pocketsdr.app.dsp.ComplexDecimator
import com.pocketsdr.app.dsp.Demodulators
import com.pocketsdr.app.dsp.Fft
import com.pocketsdr.app.dsp.RealDecimator
import com.pocketsdr.app.source.IQSource
import com.pocketsdr.app.source.SimulatedSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * SDR 核心引擎：IQ 源 → 频谱/瀑布 → NCO 下变频 → 解调 → 音频输出。
 *
 * 采样率链（源 1.024 Msps）：
 *  WFM 路径：÷4 → 256ksps 解调（±75kHz 频偏无卷绕）→ 立体声解码 → 音频 ÷8 → 32kHz 立体声
 *  NFM/AM 路径：÷4 → ÷8 = ÷32 → 32ksps 解调 → 直出
 *
 * 线程模型：单 DSP 线程全链路；UI 通过 [UiBridge] 收推送、通过 volatile 字段发指令。
 */
class SdrEngine private constructor(
    private val source: IQSource,
    private val ui: UiBridge
) {
    /** 供 HardwareControl 等上层访问源（只读） */
    val sourceOrNull: IQSource get() = source
    val isRealSource: Boolean get() = source.isReal

    /**
     * 硬件 LNA 增益（tenths dB）。
     *
     * 返回是否被硬件接受：增益设置失败时，界面上的档位和硬件实际档位
     * 就对不上了——用户以为增益调上去了，信号强度却纹丝不动，
     * 看起来像增益功能坏了。返回失败才能让 UI 说出这一点。
     *
     * 仿真源也实现了增益（见 [com.pocketsdr.app.source.SimulatedSource]），
     * 这样没有硬件时也能体验、也能验证增益寻优这套逻辑。
     */
    fun setHardwareLnaGain(tenthsDb: Int): Boolean {
        return when (val s = source) {
            is com.pocketsdr.app.source.rtl.RtlUsbSource -> s.setLnaGain(tenthsDb)
            is com.pocketsdr.app.source.SimulatedSource -> s.setLnaGain(tenthsDb)
            else -> true
        }
    }

    /** 硬件手动增益模式；返回是否被硬件接受（理由同 [setHardwareLnaGain]） */
    fun setHardwareGainMode(manual: Boolean): Boolean {
        return when (val s = source) {
            is com.pocketsdr.app.source.rtl.RtlUsbSource -> s.setGainMode(manual)
            is com.pocketsdr.app.source.SimulatedSource -> s.setGainMode(manual)
            else -> true
        }
    }

    /**
     * 当前生效的 LNA 增益（tenths dB）。
     *
     * 增益扫描要能恢复用户原来的那一档，就必须有「读」的能力——
     * 以前只有「设」，没有「读」。
     */
    val hardwareLnaGain: Int
        get() = when (val s = source) {
            is com.pocketsdr.app.source.rtl.RtlUsbSource -> s.currentLnaGain
            is com.pocketsdr.app.source.SimulatedSource -> s.simGainTenthDb
            else -> 0
        }

    /** 当前是否为手动增益模式 */
    val hardwareManualGain: Boolean
        get() = when (val s = source) {
            is com.pocketsdr.app.source.rtl.RtlUsbSource -> s.manualGainMode
            is com.pocketsdr.app.source.SimulatedSource -> s.simManualGain
            else -> false
        }

    /** 硬件直接采样模式（0=关 1=I-ADC 2=Q-ADC），HF 短波用 */
    fun setHardwareDirectSampling(mode: Int): Boolean {
        val src = source as? com.pocketsdr.app.source.rtl.RtlUsbSource ?: return false
        val ok = src.setDirectSampling(mode)
        if (ok) pendingReset = true
        return ok
    }

    val hardwareDirectSampling: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.directSampling ?: 0

    /** FC0013 LNA 增益档（tenths dB），librtlsdr 同表 */
    val lnaGains: IntArray
        get() = LNA_GAINS

    /** offset tuning（躲开 DC 的 1/f 噪声），仿真源 no-op */
    fun setHardwareOffsetTuning(on: Boolean): Boolean {
        val src = source as? com.pocketsdr.app.source.rtl.RtlUsbSource ?: return false
        val ok = src.setOffsetTuning(on)
        if (ok) pendingReset = true
        return ok
    }

    /** RTL2832 内部 AGC 开关（SDR 场景通常关，防它拉平均信号） */
    fun setHardwareRtlAgc(on: Boolean): Boolean {
        val src = source as? com.pocketsdr.app.source.rtl.RtlUsbSource ?: return false
        return src.setRtlAgc(on)
    }

    /** 频率校准 PPM（晶振误差补偿），仿真源 no-op */
    fun setHardwarePpm(ppm: Int): Boolean {
        val src = source as? com.pocketsdr.app.source.rtl.RtlUsbSource ?: return false
        val ok = src.setPpm(ppm)
        if (ok) pendingReset = true
        return ok
    }

    /** 当前生效的 PPM */
    val hardwarePpm: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.ppm ?: 0
    enum class Mode(val label: String) { WFM("WFM"), NFM("NFM"), AM("AM"), USB("USB"), LSB("LSB") }

    // ---------- UI 可写状态 ----------
    @Volatile var mode: Mode = Mode.WFM
    @Volatile var volume: Float = 0.8f
    /** 扫描时临时静音（防跳频爆音） */
    @Volatile var muted: Boolean = false
    /**
     * 因音频焦点丢失而暂时让出声音（来电、导航播报）。
     *
     * 与 [muted] 分开是为了**语义正确**：
     *
     * - [muted] 是用户按了静音键 —— 界面该显示「静音中」
     * - 本标志是系统让我们让路 —— 界面该显示「通话中」，
     *   如果也显示成「静音中」，用户会以为是自己按了静音，
     *   然后去点那个按钮想取消——点了也没用，因为让路是系统决定的
     *
     * 以前两者混用一个标志，来电时界面会莫名其妙变成红色的「🔇 静音中」。
     */
    @Volatile var focusMuted: Boolean = false
        private set

    /** 实际是否输出声音：用户静音或焦点让出，任一成立都不出声 */
    val outputMuted: Boolean get() = muted || focusMuted

    /**
     * 设置「因焦点让出声音」标志。
     *
     * 由音频焦点回调调用，不改 [muted]——用户的静音偏好必须原样保留，
     * 否则通话结束后恢复声音时，会把用户自己设的静音也一起解除。
     */
    fun setFocusMuted(v: Boolean) {
        focusMuted = v
    }
    @Volatile var squelchDb: Float = -40f          // 静噪阈值 dBFS（-100..0）
    @Volatile private var _centerFreq: Long = 98_000_000
    @Volatile private var _tunedFreq: Long = 98_100_000

    val centerFreq: Long get() = _centerFreq
    val tunedFreq: Long get() = _tunedFreq
    val sampleRate: Float get() = source.sampleRate

    /**
     * 最近一次调谐是否被硬件接受。
     *
     * 调谐是异步的（UI 入队 → DSP 线程应用 → 硬件 I2C 写），所以
     * [tuneTo] 的返回值只代表「已提交」。真正成没成要看这个标志：
     * 它在 DSP 线程应用调谐后立即更新，调用方稍等几十毫秒即可读到。
     *
     * 扫台与试听都依赖它——否则会拿着「调谐其实失败了」的信号强度
     * 当成有效数据，搜出频点错位、电平虚假的电台列表。
     */
    @Volatile var lastTuneOk: Boolean = true
        private set

    @Volatile var running = false
        private set

    // ---------- DSP 静态配置 ----------
    private val fftSize = 1024
    private val fft = Fft(fftSize)
    private val fftPower = FloatArray(fftSize)

    // ---------- 线程与缓冲 ----------
    private var dspThread: Thread? = null
    private val iqBuf = FloatArray(BLOCK * 2)

    // ---------- NCO 下变频 ----------
    private var ncoPhase = 0.0

    // ---------- 抽取器 ----------
    // 级 A：1.024M → 256k（÷4），65 抽头（性能优化：129→65，129 抽头在低端机 WFM 链 ~270M MAC/s 超预算）
    // 截止 92kHz；65 抽头 Hamming 阻带衰减 44dB，混叠分量在 940k+ 处被衰减后折回
    private val stageA = ComplexDecimator(4, 65, 0.09f)
    // 级 B：256k → 32k（÷8），65 抽头，截止 11.8kHz，阻带 15kHz < 16kHz
    private val stageB = ComplexDecimator(8, 65, 0.046f)

    // ---------- 解调器 ----------
    private val wfmDemod = Demodulators.FmDemod()
    private val stereo = Demodulators.StereoDecoder(256_000f)  // 解码留在 256k（38k 子载波需要 fs>76k）
    private val audioDecL = RealDecimator(8, 65, 0.049f)       // 256k → 32k L（65 抽头性能优化）
    private val audioDecR = RealDecimator(8, 65, 0.049f)       // 256k → 32k R
    private val deempL = Demodulators.DeEmphasis(50f, 32_000f)
    private val deempR = Demodulators.DeEmphasis(50f, 32_000f)
    /** RDS 解码（WFM 路径 256k 喂入） */
    val rds = com.pocketsdr.app.dsp.RdsDecoder(256_000f)
    private val nfmDemod = Demodulators.NfmDemod()
    private val amDemod = Demodulators.AmDemod()
    private val nfmAudioLp = Biquad.Lowpass(3400f, 32_000f)
    private val amAudioHpf = Biquad.Highpass(120f, 32_000f)
    private val ssbDemod = com.pocketsdr.app.dsp.HilbertFir.Companion.SsbDemod(65)
    private val ssbAudioLp = Biquad.Lowpass(2700f, 32_000f)   // SSB 语音带宽
    private val ssbAudioHpf = Biquad.Highpass(300f, 32_000f)  // 去低频隆隆声
    private val ssbAgc = com.pocketsdr.app.dsp.AudioAgc(32_000f, targetPeak = 0.3f)
    private val narrowAgc = com.pocketsdr.app.dsp.AudioAgc(32_000f, targetPeak = 0.35f)

    // ---------- 音频 ----------
    private val audioRate = 32_000
    private var audioTrack: AudioTrack? = null

    // ---------- 频谱显示（双缓冲交替，UI 即时读） ----------
    private val specA = FloatArray(fftSize)
    private val specB = FloatArray(fftSize)
    private var specFlip = false
    /** 谱线 EMA 平滑缓冲（上升快跟/下降慢衰减，SDR# 余晖风格） */
    private val specSmooth = FloatArray(fftSize)
    /** UI 谱线读平滑值（瀑布用原始值） */
    val latestSpectrum: FloatArray get() = specSmooth

    /**
     * 自动 dB 基线：噪声底的 25% 分位数（dB）。
     *
     * 真机噪声底随增益/天线/环境差几十 dB，固定显示范围不是「太高看不全」就是
     * 「太低全黑」。这里用分位数而非中位数——满屏都是强台时中位数会被拉高，
     * 分位数更接近真实底噪。
     */
    @Volatile var noiseFloorDb: Float = -85f
        private set
    /** 频谱峰值（dB），用于定显示上限 */
    @Volatile var spectrumPeakDb: Float = -30f
        private set
    /** 是否启用自动 dB 范围 */
    @Volatile var autoDbRange: Boolean = true

    private val noiseSortBuf = FloatArray(256)   // 噪声底统计采样缓冲
    private var noiseFill = 0
    /** 统计样本数：@Volatile 是因为增益扫描线程会调 [resetNoiseStats] */
    @Volatile private var noiseCount = 0
    /**
     * 还要跳过多少帧平滑。
     *
     * reset 之后只有**第一帧**是纯新值，第二帧就变成 0.7/0.3 的混合了。
     * 增益扫描要读的是「这一档自己的」底噪，混合值会得出错误结论，
     * 所以重置后连续若干帧都直接采用新值。
     */
    @Volatile private var noiseSkipSmooth = 0

    /**
     * 丢弃当前的噪声底/峰值统计，让下一帧统计**直接采用新值**而不做平滑。
     *
     * 平滑是为了让 UI 上的读数不跳——但增益一变，旧档位的值就变成了「残留」：
     * 底噪用 0.7/0.3 平滑，300 ms 后旧值还占一半，读到的是两个档位的混合，
     * 用来比较哪一档更好会得出错误结论。
     *
     * 增益扫描每换一档就调一次这个方法，测到的就是**该档自己的**噪声底。
     */
    fun resetNoiseStats() {
        noiseCount = 0
        // 接下来的 4 帧（约 0.5 秒）都直接采用新值，不与旧档位混合
        noiseSkipSmooth = 4
    }

    /**
     * 峰值保持缓冲（SDR# 的 Peak Hold）。
     * 每帧取"当前值"与"旧峰值缓慢衰减"的较大者：短暂出现的弱信号会留下痕迹，
     * 便于在瀑布来不及看清时定位载波。关闭时填 -200 让线自然消失。
     */
    private val specPeak = FloatArray(fftSize) { -200f }
    @Volatile var peakHoldEnabled: Boolean = false
    /** UI 读峰值线（关闭时全为 -200，画出来在屏幕外，等于不显示） */
    val latestPeak: FloatArray get() = specPeak
    private val fftDb = FloatArray(fftSize)

    // 瀑布行累积计数
    private var fftCount = 0

    /**
     * 瀑布刷新速度档位。
     *
     * 0 = 慢（约 3 行/秒，盯弱信号漂移/看调制细节）
     * 1 = 中（默认，约 7.7 行/秒）
     * 2 = 快（约 15.5 行/秒，快速扫频时信号不漏）
     *
     * 慢档不是"更真实"，而是给用户看时间变化用的；快档保证快速调谐时不漏掉瞬时信号。
     */
    @Volatile var waterfallSpeed: Int = 1

    // ---------- 静噪 ----------
    private var squelchOpen = false
    /** 静噪是否已开（DSP 线程写，UI 读）：开了说明信号确实超过门限 */
    @Volatile var squelchIsOpen: Boolean = false
        private set
    @Volatile var signalDb = -120f
    private var signalDbSmooth = -120f   // 信号强度条 EMA 平滑，避免跳闪
    /** 立体声锁定强度 0..1（UI 显示 ST 指示） */
    val stereoLock: Float get() = stereo.stereoLock

    // ---------- 录音 ----------
    private var recorder: WavRecorder? = null

    /**
     * 录音相关的失败原因（null = 没有失败）。
     *
     * 以前录音写盘失败会让异常冒到 DSP 线程、把整个 DSP 循环打死，
     * 而界面还显示「录音中」——频谱冻结、声音停了，用户一脸懵。
     * 现在写失败会被接住，录音自动停止，这里记下原因给界面显示。
     */
    @Volatile var recordingError: String? = null
        private set

    /** AudioTrack 写入失败次数（track 被释放/停止后写入会返回负错误码） */
    @Volatile var audioWriteFailures: Int = 0
        private set

    /** 开始录音；返回是否成功 */
    fun startRecording(context: android.content.Context): Boolean {
        val r = recorder ?: WavRecorder(context).also { recorder = it }
        recordingError = null
        // 把 RDS 台名带进文件名：录音列表里光有频率，回听时分不清是哪个台
        val ok = r.start(audioRate, _tunedFreq, rds?.stationName ?: "")
        if (!ok) recordingError = r.lastError ?: "无法开始录音（存储不可用）"
        return ok
    }

    fun stopRecording(): String? {
        val r = recorder ?: return null
        val path = r.stop()
        // 停止时才发现写盘失败（WAV 头没回填上）：必须说出来
        if (r.lastError != null) recordingError = r.lastError
        return path
    }

    /** 暂停录音（丢弃后续帧，文件不增长；WAV 无暂停语义，恢复后同一文件继续追加） */
    fun pauseRecording() {
        recorder?.let { it.paused = true }
    }

    /** 恢复录音 */
    fun resumeRecording() {
        recorder?.let { it.paused = false }
    }

    val recordingPaused: Boolean get() = recorder?.paused == true

    val isRecording: Boolean get() = recorder?.recording == true

    // ---------- 数据流健康（真机联调诊断）----------
    /** 已处理的采样块数（读流是否在推进） */
    @Volatile var blockCount: Long = 0
        private set
    /** 最近一次有数据的时间戳（ms），用于判断"流是否还活着" */
    @Volatile var lastDataMs: Long = 0
        private set

    /** 距上次收到数据过了多久（ms）；0 表示从未收到 */
    fun dataIdleMs(): Long =
        if (lastDataMs == 0L) -1L else System.currentTimeMillis() - lastDataMs

    /** USB 累计读超时次数（供电不稳/OTG 线接触不良的硬指标） */
    val usbReadTimeouts: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.totalReadTimeouts ?: 0
    /** USB 累计端点复位次数（≥1 就发生过断流自救） */
    val usbResets: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.totalResets ?: 0
    /** I2C 累计重试次数（供电/接触不良的软指标） */
    val i2cRetries: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.i2cRetries ?: 0
    /** 硬件调谐累计失败次数（频率变了声音没变的硬证据） */
    val tuneFailures: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.tuneFailures ?: 0
    /** 硬件设置（增益/模式）累计失败次数 */
    val hwSettingFailures: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.hwSettingFailures ?: 0

    /**
     * 生成一份纯文本自检报告。
     *
     * 目的：真机出问题时用户不必去翻 logcat（大部分人不会），一段可复制的文本
     * 就能定位「认到设备了吗 / 有数据流吗 / 噪声底正常吗 / 有没有读超时」。
     */
    fun selfCheckReport(appVersion: String): String {
        val rtl = source as? com.pocketsdr.app.source.rtl.RtlUsbSource
        val idle = dataIdleMs()
        val sb = StringBuilder()
        sb.append("=== PocketSDR 自检报告 ===\n")
        sb.append("版本：$appVersion\n")
        sb.append("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}\n")
        sb.append("源码：\n")
        sb.append("  数据源：").append(if (isRealSource) "真机 RTL-SDR" else "仿真模式").append('\n')
        if (rtl != null) {
            sb.append("  USB：").append(rtl.usbDeviceInfo).append('\n')
            sb.append("  tuner：").append(rtl.tunerName).append('\n')
            if (rtl.probeDetail.isNotEmpty()) {
                sb.append("  探测：").append(rtl.probeDetail).append('\n')
            }
            sb.append("  直接采样：").append(
                when (hardwareDirectSampling) { 1 -> "I-ADC"; 2 -> "Q-ADC"; else -> "关" }).append('\n')
        }
        sb.append("  采样率：").append("%.3f MHz".format(sampleRate / 1e6)).append('\n')
        sb.append("  中心频率：").append("%.6f MHz".format(centerFreq / 1e6)).append('\n')
        sb.append("  当前频点：").append("%.6f MHz".format(tunedFreq / 1e6)).append('\n')
        sb.append("  模式：").append(mode.label).append('\n')
        sb.append("  PPM 校准：").append(hardwarePpm).append('\n')
        sb.append("  LNA 档位：").append(lnaGains.size).append(" 档\n")
        sb.append("信号：\n")
        sb.append("  电平：").append("%.1f dB".format(signalDb)).append('\n')
        sb.append("  噪声底：").append("%.1f dB".format(noiseFloorDb)).append('\n')
        sb.append("  频谱峰值：").append("%.1f dB".format(spectrumPeakDb)).append('\n')
        sb.append("  静噪门限：").append("%.1f dB".format(squelchDb)).append('\n')
        sb.append("  静噪开：").append(if (squelchIsOpen) "是" else "否").append('\n')
        sb.append("流健康：\n")
        sb.append("  数据块：").append(blockCount).append('\n')
        sb.append("  最近数据：").append(
            when {
                idle < 0 -> "尚未收到任何数据"
                idle < 2000 -> "正常"
                else -> "已中断 ${idle / 1000} 秒"
            }).append('\n')
        sb.append("  USB 读超时：").append(usbReadTimeouts).append(" 次\n")
        sb.append("  USB 端点复位：").append(usbResets).append(" 次\n")
        sb.append("  I2C 重试：").append(rtl?.i2cRetries ?: 0).append(" 次\n")
        sb.append("  demod 写失败：").append(rtl?.demodWriteFailures ?: 0).append(" 次\n")
        sb.append("  调谐失败：").append(rtl?.tuneFailures ?: 0).append(" 次\n")
        sb.append("  硬件设置失败：").append(rtl?.hwSettingFailures ?: 0).append(" 次\n")
        sb.append("  音频写失败：").append(audioWriteFailures).append(" 次\n")
        sb.append("  录音写失败：").append(recorder?.writeFailures ?: 0).append(" 次\n")
        sb.append("RDS：\n")
        sb.append("  锁定：").append(if (rds.dataLock) "是" else "否").append('\n')
        sb.append("  PI：").append(if (rds.piCode >= 0) String.format("%04X", rds.piCode) else "未知").append('\n')
        sb.append("  PS：").append(if (rds.stationName.isNotEmpty()) rds.stationName else "未知").append('\n')
        sb.append("  PTY：").append(if (rds.pty >= 0) rds.pty.toString() else "未知").append('\n')
        sb.append("  TP/TA：").append(if (rds.trafficProgram) "TP" else "-").append("/").append(if (rds.trafficAnnouncement) "TA" else "-").append('\n')
        sb.append("  RT：").append(if (rds.radioText.isNotEmpty()) rds.radioText else "未知").append('\n')
        sb.append("  RT 段：").append(rds.rtSegments).append("（PS 段：").append(rds.psSegments).append("）\n")
        sb.append("  CRC 正确块：").append(rds.crcBlocksOk).append('\n')
        sb.append("  群组：").append(if (rds.groupTypesSummary.isNotEmpty()) rds.groupTypesSummary else "暂无").append('\n')
        sb.append("  AF：").append(if (rds.afFrequenciesKhz.isNotEmpty()) rds.afFrequenciesKhz.joinToString(",") { "%.1f".format(it/1000.0) } else "暂无").append(if (rds.afLfMf) "（LF/MF）" else "").append('\n')
        sb.append("  仿真源：").append(if (!isRealSource) "正常" else "不适用").append('\n')
        return sb.toString()
    }

    // ---------- 公共 API ----------
    fun start() {
        if (running) return
        running = true
        audioTrack = buildAudioTrack()
        dspThread = Thread({ dspLoop() }, "SdrDsp").apply {
            priority = Thread.MAX_PRIORITY - 1
            // 再降 nice 值（需要 root/系统 App 才生效，普通 App 静默失败无副作用）
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            start()
        }
        ui.pushStatus(if (source.isReal) "硬件已连接" else "仿真模式")
    }

    fun stop() {
        running = false
        // 录音收尾（WAV 头回填），在杀线程前进行
        recorder?.let { r ->
            val path = r.stop()
            if (path != null) {
                // 写盘失败过的录音不能说「已保存」——文件可能缺数据或头写不进去。
                val err = r.lastError
                if (err != null) {
                    recordingError = err
                    ui.pushStatus("⚠ $err")
                } else {
                    ui.pushStatus("录音已保存: ${path.substringAfterLast('\\').substringAfterLast('/')}")
                }
            }
        }
        dspThread?.join(2000)
        dspThread = null
        audioTrack?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        audioTrack = null
        ui.pushStatus("已停止")
    }

    fun close() { stop(); source.close() }

    /** 调谐到绝对频率 [freqHz]；偏离当前中心超过 span/3 时自动重定源中心 */
    fun tuneTo(freqHz: Long) {
        val span = source.sampleRate
        if (abs(freqHz - _centerFreq) > span / 3) {
            submitTune(freqHz, freqHz)
        } else {
            submitTune(_centerFreq, freqHz)
        }
    }

    /** 直接调中心（频谱拖动用），tuned 跟随平移保持相对偏移 */
    fun recenter(f: Long) {
        submitTune(f, _tunedFreq + (f - _centerFreq))
    }

    /** 只调收听点，不动源中心（频谱内点选/步进连跳）。
     *  轻 reset：只对齐 NCO 相位避免爆音，不清滤波器/PLL/AGC——
     *  连跳（60ms 间隔）时反复全清会让立体声永远锁不上、AGC 抽搐。 */
    fun tuneWithinSpan(freqHz: Long) {
        submitTune(_centerFreq, freqHz, light = true)
    }

    // ---------- 调谐/模式请求队列（UI 线程投递 → DSP 块头原子应用） ----------
    // 所有滤波器/PLL/AGC 状态只允许 DSP 线程触碰：UI 直接 reset 会与
    // demodulate() 并发撕裂同一批对象（切换模式多次后 FM 失效的根因）。
    private val tuneLock = Any()
    private var pendingCenter: Long? = null
    private var pendingTuned: Long? = null
    private var pendingLight = false
    @Volatile private var pendingMode: Mode? = null
    /** UI 线程请求 DSP 重置（硬件直采/偏移调谐后），块头原子执行 */
    @Volatile private var pendingReset = false

    private fun submitTune(center: Long, tuned: Long, light: Boolean = false) {
        synchronized(tuneLock) {
            // 只有本次请求链全为 light 才轻处理；新请求重置该标志
            pendingLight = if (pendingCenter == null) light else (pendingLight && light)
            pendingCenter = center
            pendingTuned = tuned
        }
    }

    /** 切换解调模式：只入队，重置在 DSP 线程块头原子执行 */
    fun selectMode(m: Mode) {
        pendingMode = m
    }

    /** DSP 线程每块调用：原子应用调谐+模式状态（center+tuned+mode 一致更新） */
    private fun applyPendingTune() {
        var c: Long? = null
        var t: Long? = null
        var light = false
        var m: Mode? = null
        var needResetNow = false
        synchronized(tuneLock) {
            m = pendingMode
            pendingMode = null
            if (pendingCenter != null) {
                c = pendingCenter
                t = pendingTuned
                light = pendingLight
                pendingCenter = null
                pendingTuned = null
                pendingLight = false
            }
        }
        if (pendingReset) { needResetNow = true; pendingReset = false }
        var needReset = needResetNow
        if (m != null && m != mode) {
            mode = m            // DSP 线程写，demodulate 同线程读，无撕裂
            needReset = true
        }
        if (c != null && t != null) {
            _centerFreq = c
            _tunedFreq = t
            // 换台必须清 RDS 状态：台名/滚动文本/PI 都是**上一个台**的，
            // 不清的话界面会挂着旧台名，而且两个台的段会混着投票，
            // 可能拼出不存在的「混合台名」。
            rds.resetAll()
            // 调谐在 DSP 线程做（与 bulk 读天然串行，I2C 写不与读竞争）
            // 硬件调谐可能失败（I2C 抖动/供电不足）。source.centerFreq 是属性
            // 赋值，拿不到成功与否——只能比较源的失败计数在前后是否增长。
            // 不比较的话：界面显示的频率是新频率，硬件却还停在旧频率，
            // 用户听到的还是上一个台，而屏幕上一切正常。
            val failsBefore = source.tuneFailureCount
            source.centerFreq = c
            lastTuneOk = source.tuneFailureCount == failsBefore
            if (!lastTuneOk) {
                SdrLog.w("DSP", "硬件调谐失败：$c Hz（源失败计数 ${source.tuneFailureCount}）")
            }
            if (light && !needReset) {
                // 轻调谐：仅对齐 NCO 相位，滤波器/PLL/AGC 保留——
                // 连跳（60ms 间隔）时反复全清会让立体声锁不上、AGC 抽搐
                ncoPhase = 0.0
            } else {
                needReset = true
            }
        }
        if (needReset) resetDspState()
    }

    private fun resetDspState() {
        stageA.reset(); stageB.reset()
        audioDecL.reset(); audioDecR.reset()
        wfmDemod.reset(); nfmDemod.reset(); amDemod.reset()
        nfmAudioLp.reset(); amAudioHpf.reset()
        ssbDemod.reset(); ssbAudioLp.reset(); ssbAudioHpf.reset()
        ssbAgc.reset(); narrowAgc.reset()
        stereo.reset()
        ncoPhase = 0.0
    }

    /**
     * 写音频到 AudioTrack，并把录音数据落盘。
     *
     * AudioTrack.write 返回负数表示失败（track 被 stop/release 之后会这样）。
     * 以前返回值被丢掉：声音已经没了，但频谱、信号强度、「运行中」全都照常显示，
     * 用户只看到「有信号却没声音」且没有任何提示。
     * 现在第一次失败就记下来并说明。
     */
    /**
     * 写音频：扬声器 + 录音。
     *
     * 三件事在这里分道扬镳：
     *
     * | 状态 | 扬声器 | 录音 |
     * |---|---|---|
     * | 静噪关闭 | 不出声 | **照录**（录下静音段） |
     * | 用户静音 | 不出声 | **照录** |
     * | 焦点让出（来电） | 不出声 | **照录** |
     *
     * 录音不受静噪限制是守听场景的常规做法：
     * 不能因为某一段没信号就把那段从文件里剪掉——
     * 事后回放才知道那个台几点上线、什么时候停播。
     * 以前录音写在「有信号且未静音」的同一个分支里，
     * 静噪一关，录出来的文件就缺了一大段。
     */
    private fun emitAudio(track: android.media.AudioTrack, frames: Int) {
        if (frames <= 0) return
        // 录音：不受静噪/静音影响
        recorder?.write(audioFrames, frames * 2)

        if (outputMuted || !squelchOpen) {
            // 静音/静噪关闭：扬声器不出声，但照样喂 AudioTrack（全零）。
            //
            // 为什么不直接不写：AudioTrack 的缓冲区会欠载，
            // 恢复声音的瞬间会先放出一段已经过期的旧音频——「咔」的一声爆音。
            // 持续喂零能让播放流保持连续，恢复时干净。
            writeTrack(track, muteBuf, frames)
            return
        }
        writeTrack(track, audioFrames, frames)
    }

    /** 往 AudioTrack 写一帧缓冲，失败时记账（AudioTrack 被 stop/release 后写入返回负数） */
    private fun writeTrack(track: android.media.AudioTrack, buf: ShortArray, frames: Int) {
        val n = track.write(buf, 0, frames * 2, android.media.AudioTrack.WRITE_BLOCKING)
        if (n < 0) {
            audioWriteFailures++
            if (audioWriteFailures == 1) {
                SdrLog.e("DSP", "音频写入失败（$n）——AudioTrack 可能已失效")
                ui.pushStatus("⚠ 音频输出已失效（写入失败 $audioWriteFailures 次），请重新打开应用")
            }
        }
    }

    // ---------- DSP 主循环 ----------
    private fun dspLoop() {
        while (running) {
            applyPendingTune()  // 块头原子应用调谐（center+tuned 一致）
            val got = source.read(iqBuf, BLOCK)
            if (got <= 0) {
                // 源返回 <=0 有两类：正常关闭（stop 时 running 已置 false），
                // 以及真机读不到数据、端点复位也救不回来。后者如果静静退出，
                // 用户看到的就是「频谱不动了、点哪都没反应」——必须明确告知。
                if (running) {
                    SdrLog.e("DSP", "数据源中断（read=$got），DSP 循环退出")
                    ui.pushStatus("收流中断：设备无数据，请重新插拔 USB")
                }
                break
            }
            updateSpectrum(got)
            demodulate(got)
            blockCount++
            lastDataMs = System.currentTimeMillis()
        }
    }

    private fun updateSpectrum(n: Int) {
        // 4 段 FFT 平均（起点 0/4096/8192/12288 均匀覆盖全块）：噪声底 ±6dB → ±3dB
        java.util.Arrays.fill(fftPower, 0f)
        val avgN = 4
        val segStart = n / avgN   // 4096：均匀间隔，段尾 12288+1024=13312 < 16384 ✓
        var base = 0
        for (a in 0 until avgN) {
            fft.powerSpectrumSeg(iqBuf, base, fftPower)
            base += segStart
        }
        val invAvg = 1f / avgN
        val scaleInv = invAvg / (fftSize * fftSize / 4f)
        val half = fftSize / 2
        val out = if (specFlip) specB else specA
        val smooth = specSmooth   // 谱线专用平滑缓冲
        val peak = specPeak
        val holdOn = peakHoldEnabled
        // 峰值衰减系数：每帧掉约 0.25 dB，肉眼看到"缓慢下滑"而不是黏住不动
        val peakDecay = 0.25f
        for (k in 0 until fftSize) {
            val src = (k + half) and (fftSize - 1)
            val db = 10f * (Math.log10((fftPower[src] * scaleInv).toDouble() + 1e-12)).toFloat()
            out[k] = db           // 瀑布/原始谱：瞬时值
            // 谱线 EMA：上升快跟、下降慢衰减（SDR# 风格余晖）
            val prev = smooth[k]
            smooth[k] = if (db > prev) db * 0.55f + prev * 0.45f
                        else db * 0.12f + prev * 0.88f
            // 峰值保持：立即跟上新高，否则按固定速率衰减
            peak[k] = if (holdOn) {
                val decayed = peak[k] - peakDecay
                if (db > decayed) db else decayed
            } else -200f
        }
        specFlip = !specFlip
        // 瀑布行率随档位：慢档每 16 帧一行（约 3 行/秒），中档每 8 帧，快档每 4 帧
        val period = when (waterfallSpeed) {
            0 -> 16
            1 -> 8
            else -> 4
        }
        if (++fftCount % period == 0) {
            ui.pushWaterfallRow(out.copyOf())
        }
        updateNoiseFloor(smooth)
    }

    /**
     * 统计噪声底与峰值，供 UI 定显示范围。
     * 每 8 帧（约 0.5 秒）统计一次：取 64 个等间隔 bin 的 dB 值，
     * 排序后取 25% 分位作底噪、最大值作峰值。
     */
    private var noiseTick = 0
    private fun updateNoiseFloor(smooth: FloatArray) {
        if (++noiseTick % 8 != 0) return
        val n = smooth.size
        val step = (n / 64).coerceAtLeast(1)
        var c = 0
        var peak = -120f
        var i = 0
        while (i < n && c < 64) {
            val v = smooth[i]
            if (v > peak) peak = v
            noiseSortBuf[c++] = v
            i += step
        }
        if (c < 8) return
        val slice = noiseSortBuf.copyOf(c)
        java.util.Arrays.sort(slice)
        val floor = slice[(c / 4).coerceIn(0, c - 1)]
        // 平滑：底噪变化慢，突变说明增益变了，1 秒内跟上。
        // 但刚被 reset 过的那几帧要跳过平滑——那几帧的数据属于新档位，
        // 和旧档位混在一起会得出错误的比较结论。
        if (noiseSkipSmooth > 0) {
            noiseSkipSmooth--
            noiseFloorDb = floor
            spectrumPeakDb = peak
        } else {
            noiseFloorDb = if (noiseCount == 0) floor else 0.7f * noiseFloorDb + 0.3f * floor
            spectrumPeakDb = if (noiseCount == 0) peak else 0.5f * spectrumPeakDb + 0.5f * peak
        }
        noiseCount++
    }

    private fun demodulate(n: Int) {
        val lo = (_tunedFreq - _centerFreq).toDouble() * 2.0 * PI / source.sampleRate
        val m = mode
        when (m) {
            Mode.WFM -> demodWfm(n, lo)
            Mode.NFM, Mode.AM -> demodNarrow(n, lo, m)
            Mode.USB, Mode.LSB -> demodSsb(n, lo, m == Mode.USB)
        }
    }

    /** WFM：NCO → ÷4 → 鉴频(256k) → 立体声 → ÷8 → 32k 音频 */
    private fun demodWfm(n: Int, lo: Double) {
        val track = audioTrack ?: return
        var frames = 0
        var energy = 0.0
        var cnt = 0
        for (s in 0 until n) {
            val i = iqBuf[s * 2]
            val q = iqBuf[s * 2 + 1]
            // NCO 混频：乘 e^{-jLOt}（Float 域）
            val c = com.pocketsdr.app.dsp.TrigLut.cos(ncoPhase).toFloat()
            val sn = (-com.pocketsdr.app.dsp.TrigLut.sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val d = stageA.process(mixedI, mixedQ) ?: continue
            cnt++
            energy += (d.i * d.i + d.q * d.q).toDouble()
            // 鉴频：输出 1.0 = ±128kHz（满偏 ±75kHz → ×1.7 满幅）
            val mp = wfmDemod.demodulate(d.i, d.q) * WFM_DEV_SCALE
            rds.process(mp)  // RDS（57k 子载波在 MPX 域）
            // 立体声解码在 256k（38k 子载波需 fs>76k），解码后双路 ÷8 → 32k
            val (l, r) = stereo.decode(mp)
            val la = audioDecL.process(l)
            val ra = audioDecR.process(r)
            if (la != null && ra != null) {
                val fl = deempL.process(la) * volume * 32767f
                val fr = deempR.process(ra) * volume * 32767f
                audioFrames[frames * 2] = fl.coerceIn(-32767f, 32767f).toInt().toShort()
                audioFrames[frames * 2 + 1] = fr.coerceIn(-32767f, 32767f).toInt().toShort()
                frames++
            }
        }
        applySquelch(energy / kotlin.math.max(cnt, 1))
        if (frames > 0) {
            emitAudio(track, frames)
        }
    }

    /** NFM/AM：NCO → ÷4 → ÷8 → 鉴频/包络(32k) → 直出 */
    private fun demodNarrow(n: Int, lo: Double, m: Mode) {
        val track = audioTrack ?: return
        var frames = 0
        var energy = 0.0
        var cnt = 0
        for (s in 0 until n) {
            val i = iqBuf[s * 2]
            val q = iqBuf[s * 2 + 1]
            val c = com.pocketsdr.app.dsp.TrigLut.cos(ncoPhase).toFloat()
            val sn = (-com.pocketsdr.app.dsp.TrigLut.sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val a = stageA.process(mixedI, mixedQ) ?: continue
            val d = stageB.process(a.i, a.q) ?: continue
            cnt++
            energy += (d.i * d.i + d.q * d.q).toDouble()
            val v0 = when (m) {
                Mode.NFM -> nfmAudioLp.process(nfmDemod.demodulate(d.i, d.q))
                else -> amAudioHpf.process(amDemod.demodulate(d.i, d.q))
            }
            // 慢速 AGC：远台信号起伏拉平（AM 调制深度保真：目标 0.35 稍松）
            val v = narrowAgc.process(v0) * volume * 32767f
            val cv = v.coerceIn(-32767f, 32767f)
            audioFrames[frames * 2] = cv.toInt().toShort()
            audioFrames[frames * 2 + 1] = cv.toInt().toShort()
            frames++
        }
        applySquelch(energy / kotlin.math.max(cnt, 1))
        if (frames > 0) {
            emitAudio(track, frames)
        }
    }

    /** SSB：NCO → ÷4 → ÷8 → Hilbert 相位法(32k) → 带通 → 直出 */
    private fun demodSsb(n: Int, lo: Double, upper: Boolean) {
        val track = audioTrack ?: return
        var frames = 0
        var energy = 0.0
        var cnt = 0
        for (s in 0 until n) {
            val i = iqBuf[s * 2]
            val q = iqBuf[s * 2 + 1]
            val c = com.pocketsdr.app.dsp.TrigLut.cos(ncoPhase).toFloat()
            val sn = (-com.pocketsdr.app.dsp.TrigLut.sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val a = stageA.process(mixedI, mixedQ) ?: continue
            val d = stageB.process(a.i, a.q) ?: continue
            cnt++
            energy += (d.i * d.i + d.q * d.q).toDouble()
            // 相位法 SSB：I ∓ H(Q) → 带通 → 慢速 AGC（弱信号可听、强信号不炸）
            val v0 = ssbDemod.demodulate(d.i, d.q, upper)
            val vbp = ssbAudioHpf.process(ssbAudioLp.process(v0))
            val vagc = ssbAgc.process(vbp)
            val v = vagc * volume * 32767f
            val cv = v.coerceIn(-32767f, 32767f)
            audioFrames[frames * 2] = cv.toInt().toShort()
            audioFrames[frames * 2 + 1] = cv.toInt().toShort()
            frames++
        }
        applySquelch(energy / kotlin.math.max(cnt, 1))
        if (frames > 0) {
            emitAudio(track, frames)
        }
    }

    private var openStreak = 0   // 连续超阈块计数（防毛刺开门）

    private fun applySquelch(avgPower: Double) {
        val db = 10.0 * Math.log10(avgPower + 1e-12)
        // EMA 平滑（α=0.35），信号强度条不抖动；静噪决策仍用瞬时 db，保持灵敏
        signalDbSmooth = 0.35f * db.toFloat() + 0.65f * signalDbSmooth
        signalDb = signalDbSmooth
        if (squelchOpen) {
            // 已开：低于 阈值−3dB 才关（迟滞）
            if (db < squelchDb - 3.0) {
                squelchOpen = false
                openStreak = 0
            }
        } else {
            // 未开：连续 2 块超阈才开（防单块毛刺）
            if (db > squelchDb) {
                if (++openStreak >= 2) {
                    squelchOpen = true
                }
            } else {
                openStreak = 0
            }
        }
        squelchIsOpen = squelchOpen
    }

    private fun buildAudioTrack(): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audioRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(audioRate / 5 * 4) // 200ms：防 DSP 抖动欠载（卡顿/爆音），收音机延迟无感
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        return track
    }

    companion object {
        private const val BLOCK = 16384                    // 16ms @1.024Msps
        private const val TWO_PI = 2.0 * PI
        // 鉴频输出已归一化 1.0=±128kHz（256ksps/2），广播满频偏 ±75kHz → ×1.7 满幅
        private const val WFM_DEV_SCALE = 1.7f

        /** FC0013 LNA 增益档（tenths dB），librtlsdr 同表 */
        private val LNA_GAINS = intArrayOf(
            -99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65,
            67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197
        )

        /** 仿真源引擎 */
        fun simulated(ui: UiBridge, centerFreq: Long = 98_000_000): SdrEngine =
            SdrEngine(SimulatedSource(centerFreq, 1_024_000f), ui)

        /** 真实源引擎 */
        fun withSource(src: IQSource, ui: UiBridge): SdrEngine = SdrEngine(src, ui)
    }

    private val audioFrames = ShortArray(BLOCK / 4)  // WFM 最坏 16384/8=2048 帧 ×2

    /**
     * 静音时喂给 AudioTrack 的全零缓冲。
     *
     * 静音不停止写 AudioTrack（见 [emitAudio]）——停写会导致缓冲区欠载，
     * 恢复声音的瞬间会放出一段过期的旧音频（爆音）。
     *
     * 内容永远是零，分配一次就够，不用每次填。
     */
    private val muteBuf = ShortArray(BLOCK / 4)
}

/** UI 桥：引擎 → UI 的单向推送（引擎线程调用） */
interface UiBridge {
    fun pushWaterfallRow(rowDb: FloatArray)
    fun pushStatus(text: String)
}
