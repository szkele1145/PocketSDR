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
    enum class Mode(val label: String) { WFM("WFM"), NFM("NFM"), AM("AM") }

    // ---------- UI 可写状态 ----------
    @Volatile var mode: Mode = Mode.WFM
    @Volatile var volume: Float = 0.8f
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
    private val stereo = Demodulators.StereoDecoder(256_000f)
    private val deempL = Demodulators.DeEmphasis(50f, 32_000f)
    private val deempR = Demodulators.DeEmphasis(50f, 32_000f)
    private val nfmDemod = Demodulators.NfmDemod()
    private val amDemod = Demodulators.AmDemod()
    private val nfmAudioLp = Biquad.Lowpass(3400f, 32_000f)
    private val amAudioHpf = Biquad.Highpass(120f, 32_000f)

    // ---------- 音频 ----------
    private val audioRate = 32_000
    // WFM 音频抽取：256k → 32k（÷8），截止 12.5kHz，阻带 15.8kHz < 16kHz
    private val audioDecL = RealDecimator(8, 129, 0.049f)
    private val audioDecR = RealDecimator(8, 129, 0.049f)
    private var audioTrack: AudioTrack? = null

    // ---------- 频谱显示（双缓冲交替，UI 即时读） ----------
    private val specA = FloatArray(fftSize)
    private val specB = FloatArray(fftSize)
    private var specFlip = false
    val latestSpectrum: FloatArray get() = if (specFlip) specA else specB
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
        for (k in 0 until fftSize) {
            val src = (k + half) and (fftSize - 1)
            fftDb[k] = 10f * (Math.log10((fftPower[src] * scaleInv).toDouble() + 1e-12)).toFloat()
        }
        System.arraycopy(fftDb, 0, out, 0, fftSize)
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
            // 鉴频：输出 1.0 = ±128kHz
            val mp = wfmDemod.demodulate(d.i, d.q) * WFM_DEV_SCALE
            val (l, r) = stereo.decode(mp)
            val la = audioDecL.process(l)
            val ra = audioDecR.process(r)
            if (la != null && ra != null) {
                // 去加重在 32k 音频域（CCITT/中国 50µs）
                val fl = deempL.process(la) * volume * 32767f
                val fr = deempR.process(ra) * volume * 32767f
                audioFrames[frames * 2] = fl.coerceIn(-32767f, 32767f).toInt().toShort()
                audioFrames[frames * 2 + 1] = fr.coerceIn(-32767f, 32767f).toInt().toShort()
                frames++
            }
        }
        applySquelch(energy / kotlin.math.max(cnt, 1))
        if (frames > 0 && squelchOpen) {
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
        if (frames > 0 && squelchOpen) {
            track.write(audioFrames, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            recorder?.write(audioFrames, frames * 2)
        }
    }

    /** 静噪门：IF 功率 dBFS 与阈值比较（带 20% 迟滞防抖） */
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
