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

    /** 硬件 LNA 增益（tenths dB），仿真源 no-op */
    fun setHardwareLnaGain(tenthsDb: Int) {
        (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.setLnaGain(tenthsDb)
    }

    /** 硬件手动增益模式 */
    fun setHardwareGainMode(manual: Boolean) {
        (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.setGainMode(manual)
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
    enum class Mode(val label: String) { WFM("WFM"), NFM("NFM"), AM("AM"), USB("USB"), LSB("LSB") }

    // ---------- UI 可写状态 ----------
    @Volatile var mode: Mode = Mode.WFM
    @Volatile var volume: Float = 0.8f
    /** 扫描时临时静音（防跳频爆音） */
    @Volatile var muted: Boolean = false
    @Volatile var squelchDb: Float = -40f          // 静噪阈值 dBFS（-100..0）
    @Volatile private var _centerFreq: Long = 98_000_000
    @Volatile private var _tunedFreq: Long = 98_100_000

    val centerFreq: Long get() = _centerFreq
    val tunedFreq: Long get() = _tunedFreq
    val sampleRate: Float get() = source.sampleRate

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

    // ---------- 静噪 ----------
    private var squelchOpen = false
    @Volatile var signalDb = -120f
    private var signalDbSmooth = -120f   // 信号强度条 EMA 平滑，避免跳闪
    /** 立体声锁定强度 0..1（UI 显示 ST 指示） */
    val stereoLock: Float get() = stereo.stereoLock

    // ---------- 录音 ----------
    private var recorder: WavRecorder? = null
    /** 开始录音；返回是否成功 */
    fun startRecording(context: android.content.Context): Boolean {
        val r = recorder ?: WavRecorder(context).also { recorder = it }
        return r.start(audioRate, _tunedFreq)
    }

    fun stopRecording(): String? = recorder?.stop()

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
            if (path != null) ui.pushStatus("录音已保存: ${path.substringAfterLast('\\').substringAfterLast('/')}")
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
            // 调谐在 DSP 线程做（与 bulk 读天然串行，I2C 写不与读竞争）
            source.centerFreq = c
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
        if (++fftCount % 4 == 0) { // ~15.5 行/秒 瀑布
            ui.pushWaterfallRow(out.copyOf())
        }
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
        if (frames > 0 && squelchOpen && !muted) {
            track.write(audioFrames, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            recorder?.write(audioFrames, frames * 2)
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
        if (frames > 0 && squelchOpen && !muted) {
            track.write(audioFrames, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            recorder?.write(audioFrames, frames * 2)
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
        if (frames > 0 && squelchOpen && !muted) {
            track.write(audioFrames, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            recorder?.write(audioFrames, frames * 2)
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
}

/** UI 桥：引擎 → UI 的单向推送（引擎线程调用） */
interface UiBridge {
    fun pushWaterfallRow(rowDb: FloatArray)
    fun pushStatus(text: String)
}
