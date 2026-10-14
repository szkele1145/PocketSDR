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
        if (ok) resetDspState()
        return ok
    }

    val hardwareDirectSampling: Int
        get() = (source as? com.pocketsdr.app.source.rtl.RtlUsbSource)?.directSampling ?: 0

    /** offset tuning（躲开 DC 的 1/f 噪声），仿真源 no-op */
    fun setHardwareOffsetTuning(on: Boolean): Boolean {
        val src = source as? com.pocketsdr.app.source.rtl.RtlUsbSource ?: return false
        val ok = src.setOffsetTuning(on)
        if (ok) resetDspState()
        return ok
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
    // 级 A：1.024M → 256k（÷4），截止 92kHz，129 抽头（阻带 105kHz < 128kHz 奈奎斯特）
    private val stageA = ComplexDecimator(4, 129, 0.09f)
    // 级 B：256k → 32k（÷8），截止 11.8kHz，阻带 15kHz < 16kHz
    private val stageB = ComplexDecimator(8, 129, 0.046f)

    // ---------- 解调器 ----------
    private val wfmDemod = Demodulators.FmDemod()
    private val stereo = Demodulators.StereoDecoder(256_000f)  // 解码留在 256k（38k 子载波需要 fs>76k）
    private val audioDecL = RealDecimator(8, 129, 0.049f)       // 256k → 32k L
    private val audioDecR = RealDecimator(8, 129, 0.049f)       // 256k → 32k R
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
    private val fftDb = FloatArray(fftSize)

    // 瀑布行累积计数
    private var fftCount = 0

    // ---------- 静噪 ----------
    private var squelchOpen = false
    @Volatile var signalDb = -120f
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

    // ---------- 公共 API ----------
    fun start() {
        if (running) return
        running = true
        audioTrack = buildAudioTrack()
        dspThread = Thread({ dspLoop() }, "SdrDsp").apply {
            priority = Thread.MAX_PRIORITY - 1
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
        _tunedFreq = freqHz
        val span = source.sampleRate
        if (abs(freqHz - _centerFreq) > span / 3) {
            _centerFreq = freqHz
            source.centerFreq = freqHz
        }
        resetDspState()
    }

    /** 直接调中心（频谱拖动用），tuned 跟随平移保持相对偏移 */
    fun recenter(f: Long) {
        val delta = f - _centerFreq
        _centerFreq = f
        _tunedFreq += delta
        source.centerFreq = f
        resetDspState()
    }

    /** 只调收听点，不动源中心（频谱内点选） */
    fun tuneWithinSpan(freqHz: Long) {
        _tunedFreq = freqHz
        resetDspState()
    }

    fun selectMode(m: Mode) {
        mode = m
        resetDspState()
    }

    private fun resetDspState() {
        stageA.reset(); stageB.reset()
        audioDecL.reset(); audioDecR.reset()
        wfmDemod.reset(); nfmDemod.reset(); amDemod.reset()
        nfmAudioLp.reset(); amAudioHpf.reset()
        ssbDemod.reset(); ssbAudioLp.reset(); ssbAudioHpf.reset()
        ssbAgc.reset()
        stereo.reset()
        ncoPhase = 0.0
    }

    // ---------- DSP 主循环 ----------
    private fun dspLoop() {
        while (running) {
            val got = source.read(iqBuf, BLOCK)
            if (got <= 0) break
            updateSpectrum(got)
            demodulate(got)
        }
    }

    private fun updateSpectrum(n: Int) {
        // 用块内第一段 1024 样本做 FFT（16ms 一次，62fps）
        fft.powerSpectrum(iqBuf, fftPower)
        val scaleInv = 1f / (fftSize * fftSize / 4f)
        val half = fftSize / 2
        val out = if (specFlip) specB else specA
        val smooth = specSmooth   // 谱线专用平滑缓冲
        for (k in 0 until fftSize) {
            val src = (k + half) and (fftSize - 1)
            val db = 10f * (Math.log10((fftPower[src] * scaleInv).toDouble() + 1e-12)).toFloat()
            out[k] = db           // 瀑布/原始谱：瞬时值
            // 谱线 EMA：上升快跟、下降慢衰减（SDR# 风格余晖）
            val prev = smooth[k]
            smooth[k] = if (db > prev) db * 0.55f + prev * 0.45f
                        else db * 0.12f + prev * 0.88f
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
            val c = cos(ncoPhase).toFloat()
            val sn = (-sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val d = stageA.process(mixedI, mixedQ) ?: continue
            cnt++
            if (cnt <= S_ENERGY_N) energy += (d.i * d.i + d.q * d.q).toDouble()
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
            val c = cos(ncoPhase).toFloat()
            val sn = (-sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val a = stageA.process(mixedI, mixedQ) ?: continue
            val d = stageB.process(a.i, a.q) ?: continue
            cnt++
            if (cnt <= S_ENERGY_N) energy += (d.i * d.i + d.q * d.q).toDouble()
            val v = when (m) {
                Mode.NFM -> nfmAudioLp.process(nfmDemod.demodulate(d.i, d.q)) * volume * 32767f * 2.5f
                else -> amAudioHpf.process(amDemod.demodulate(d.i, d.q)) * volume * 32767f * 2f
            }.coerceIn(-32767f, 32767f)
            audioFrames[frames * 2] = v.toInt().toShort()
            audioFrames[frames * 2 + 1] = v.toInt().toShort()
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
            val c = cos(ncoPhase).toFloat()
            val sn = (-sin(ncoPhase)).toFloat()
            val mixedI = i * c - q * sn
            val mixedQ = i * sn + q * c
            ncoPhase += lo
            if (ncoPhase > TWO_PI) ncoPhase -= TWO_PI
            if (ncoPhase < 0.0) ncoPhase += TWO_PI
            val a = stageA.process(mixedI, mixedQ) ?: continue
            val d = stageB.process(a.i, a.q) ?: continue
            cnt++
            if (cnt <= S_ENERGY_N) energy += (d.i * d.i + d.q * d.q).toDouble()
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

    private fun applySquelch(avgPower: Double) {
        val db = 10.0 * Math.log10(avgPower + 1e-12)
        signalDb = db.toFloat()
        squelchOpen = when {
            squelchOpen -> db > squelchDb - 2.0   // 迟滞
            else -> db > squelchDb
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
            .setBufferSizeInBytes(audioRate / 10 * 4) // 100ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        return track
    }

    companion object {
        private const val BLOCK = 16384                    // 16ms @1.024Msps
        private const val TWO_PI = 2.0 * PI
        private const val S_ENERGY_N = 512                 // 能量统计样本上限
        // 鉴频输出已归一化 1.0=±128kHz（256ksps/2），广播满频偏 ±75kHz → ×1.7 满幅
        private const val WFM_DEV_SCALE = 1.7f

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
