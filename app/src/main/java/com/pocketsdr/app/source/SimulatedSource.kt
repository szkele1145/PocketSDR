package com.pocketsdr.app.source

import com.pocketsdr.app.dsp.Fft
import kotlin.math.PI
import kotlin.math.abs

/**
 * 仿真 SDR 源：生成与 RTL-SDR 相同格式的 IQ 流（float 交织 I,Q）。
 * FM 广播段布模拟台（含 19kHz 导频），航空段布 AM 台，业余 2m 布 NFM 台，加噪声底。
 *
 * 性能：三角函数全部走查表（LUT），手机 CPU 上每秒数千万次 Math.cos 是卡顿主因。
 */
class SimulatedSource(
    @Volatile override var centerFreq: Long,
    override val sampleRate: Float
) : IQSource {
    override val isReal = false

    companion object {
        /** sin/cos 查表：4096 点/2π，线性插值 */
        private const val LUT_BITS = 12
        private const val LUT_SIZE = 1 shl LUT_BITS          // 4096
        private const val LUT_MASK = LUT_SIZE - 1
        private const val TWO_PI = 2.0 * PI
        private val SIN_LUT = DoubleArray(LUT_SIZE + 1).also {
            for (i in 0..LUT_SIZE) it[i] = kotlin.math.sin(TWO_PI * i / LUT_SIZE)
        }

        /** 快速 sin：相位无需归一化（内部取模），精度 ~1e-6 足够音频 */
        fun lutSin(phase: Double): Double {
            val idx = (phase * (LUT_SIZE / TWO_PI)).toInt()
            val frac = phase * (LUT_SIZE / TWO_PI) - idx
            val i0 = idx and LUT_MASK
            return SIN_LUT[i0] + (SIN_LUT[i0 + 1] - SIN_LUT[i0]) * frac
        }

        fun lutCos(phase: Double): Double = lutSin(phase + PI / 2)
    }

    private class Station(
        val freq: Long,
        val type: Int, // 0=WFM 1=NFM 2=AM 3=LSB 4=USB
        val level: Float,
        val deviation: Float,
        val audioRate: Double
    ) {
        var carrierPhase = 0.0
        var audioPhase = 0.0
        var modPhase = 0.0
        var pilotPhase = 0.0   // 19kHz 导频相位（WFM 用）
        var pilotInteg = 0.0   // 导频 FM 相位积分
        /** 载波相位增量/样本（cache 时预计算，热循环直接读字段） */
        var phaseInc = 0.0
    }

    private val stations = ArrayList<Station>()

    // RDS 波形（96.6 台专用，预生成循环）
    private var rdsWave: FloatArray? = null
    private var rdsPos = 0

    private var noiseSeed = 0x9E3779B97F4A7C15uL
    private fun nextNoise(): Float {
        noiseSeed = noiseSeed xor (noiseSeed shl 13)
        noiseSeed = noiseSeed xor (noiseSeed shr 7)
        noiseSeed = noiseSeed xor (noiseSeed shl 17)
        return ((noiseSeed shr 40).toInt() / 8388608.0).toFloat()
    }

    private fun ensureStations() {
        if (stations.isNotEmpty()) return
        // 基音 200-450Hz（人声/音乐基频区，远离 19k 导频与 RDS）：
        // rad/样本 = 2π·f/fs，f=300Hz 时 ≈ 0.00184
        val baseF = listOf(220.0, 330.0, 440.0, 260.0, 370.0)
        fun add(freq: Long, type: Int, level: Float, dev: Float = 75_000f) {
            val f0 = baseF[stations.size % baseF.size]
            stations.add(Station(freq, type, level, dev, 2.0 * PI * f0 / sampleRate.toDouble()))
        }
        // FM 广播段
        add(98_100_000, 0, 0.9f); add(105_700_000, 0, 0.8f); add(103_900_000, 0, 0.95f)
        add(100_000_000, 0, 0.75f); add(96_600_000, 0, 1.0f); add(91_500_000, 0, 0.7f)
        add(107_700_000, 0, 0.65f); add(87_700_000, 0, 0.6f)
        // 航空 AM
        add(118_950_000, 2, 0.7f, 0f); add(121_500_000, 2, 0.8f, 0f); add(126_900_000, 2, 0.6f, 0f)
        // 业余 2m NFM
        add(145_500_000, 1, 0.5f, 5_000f); add(146_520_000, 1, 0.45f, 5_000f)
        // 短波 SSB 测试台（HF 直采/SSB 模式验证）：40m 语音 LSB + 20m USB
        add(7_074_000, 3, 0.85f, 0f); add(7_200_000, 3, 0.7f, 0f)
        add(14_300_000, 4, 0.8f, 0f)
        for ((idx, st) in stations.withIndex()) {
            st.audioPhase = idx * 1.7
            st.modPhase = idx * 0.9
        }
        // 96.6 台预生成 RDS 波形（"SIM RADIO"）
        if (rdsWave == null) {
            rdsWave = try {
                RdsEncoder("SIM RADIO").buildWave(sampleRate)
            } catch (_: Exception) { null }
        }
    }

    // 带内台缓存（centerFreq 变化时重算——把每样本的 fOff/phaseInc 判断提出循环）
    @Volatile private var cachedCenter = -1L
    private var inBandStations: List<Station> = emptyList()
    private var outBandAdvances: List<Pair<Station, Double>> = emptyList()

    private fun rebuildBandCache() {
        if (cachedCenter == centerFreq) return
        cachedCenter = centerFreq
        val halfFs = sampleRate / 2.0
        val twoPiOverFs = 2.0 * PI / sampleRate
        val inB = ArrayList<Station>(); val outB = ArrayList<Pair<Station, Double>>()
        for (st in stations) {
            val fOff = (st.freq - centerFreq).toDouble()
            st.phaseInc = fOff * twoPiOverFs
            if (abs(fOff) > halfFs) outB.add(st to st.phaseInc)
            else inB.add(st)
        }
        inBandStations = inB
        outBandAdvances = outB
    }

    override fun read(out: FloatArray, nSamples: Int): Int {
        ensureStations()
        rebuildBandCache()
        val fs = sampleRate
        val twoPiOverFs = 2.0 * PI / fs
        // 带外台相位批量推进（每样本仍要推进防回跳，但无需判断分支）
        val outAdv = outBandAdvances
        val inBand = inBandStations
        for (s in 0 until nSamples) {
            var accI = nextNoise() * 0.05
            var accQ = nextNoise() * 0.05
            for (st in outAdv) {
                st.first.carrierPhase += st.second
            }
            for (st in inBand) {
                st.audioPhase += st.audioRate
                if (st.audioPhase > TWO_PI) st.audioPhase -= TWO_PI
                // 音频用干净的亚 kHz 和弦（原 8-68kHz 混音砸进 19k 导频/RDS 频段 → 鉴频嘶嘶糊声）
                val audio = when (st.type) {
                    0 -> lutSin(st.audioPhase) * 0.5 + lutSin(st.audioPhase * 2.0) * 0.3 +
                        lutSin(st.audioPhase * 3.0) * 0.2          // WFM: 基音+八度+五度 (≈50-400Hz)
                    1 -> lutSin(st.audioPhase * 2.0) * 0.6 + lutSin(st.audioPhase * 3.0) * 0.4  // NFM: 双音
                    else -> lutSin(st.audioPhase * 1.5) * 0.6 + lutSin(st.audioPhase * 2.5) * 0.4  // AM/SSB
                }
                val instPhase: Double
                val amp: Double
                when (st.type) {
                    0 -> { // WFM + 19kHz 导频（独立相位积分，保证 PLL 可锁定）
                        st.modPhase += audio * st.deviation * twoPiOverFs
                        if (abs(st.modPhase) > 1e12) st.modPhase = 0.0
                        // 导频：10% 调制度，独立 19kHz 相位积分
                        st.pilotPhase += 19000.0 * twoPiOverFs
                        if (st.pilotPhase > TWO_PI) st.pilotPhase -= TWO_PI
                        st.pilotInteg += lutCos(st.pilotPhase) * 19000.0 * twoPiOverFs * 0.1
                        if (abs(st.pilotInteg) > 1e9) st.pilotInteg = 0.0  // 精度保护
                        instPhase = st.carrierPhase + st.modPhase + st.pilotInteg
                        amp = 0.16 * st.level
                        // 96.6 台叠加 RDS：MPX 域 57k DSB → FM 频偏积分（与导频同法）
                        if (st.freq == 96_600_000L && rdsWave != null) {
                            val w = rdsWave!!
                            st.pilotInteg += w[rdsPos] * st.deviation * twoPiOverFs
                            rdsPos = (rdsPos + 1) % w.size
                        }
                    }
                    1 -> { // NFM
                        st.modPhase += audio * st.deviation * twoPiOverFs
                        instPhase = st.carrierPhase + st.modPhase
                        amp = 0.12 * st.level
                    }
                    3, 4 -> { // SSB：解析信号直接合成（e^{j(ωc±ωa)t} = 单边带复信号）
                        // LSB: ωc−ωa；USB: ωc+ωa。相位积分生成解析信号，无镜像
                        val side = if (st.type == 3) -1.0 else 1.0
                        st.modPhase += side * st.audioRate * (if (st.type == 3) 19.0 else 23.0)
                        // audio 幅度调制包络（模拟语音起伏）
                        val env = 0.5 + 0.5 * lutSin(st.audioPhase * 0.7)
                        instPhase = st.carrierPhase + side * st.modPhase
                        amp = 0.0  // 不走通用 cos/sin 路径（下方直接加复信号）
                        val c = lutCos(instPhase) * 0.13 * st.level * env
                        val sv = lutSin(instPhase) * 0.13 * st.level * env
                        // SSB 解析信号：I=c, Q=s（LSB/USB 由相位方向天然区分）
                        accI += c
                        accQ += sv
                    }
                    else -> { // AM
                        instPhase = st.carrierPhase
                        amp = 0.12 * st.level * (0.55 + 0.45 * audio)
                    }
                }
                accI += lutCos(instPhase) * amp
                accQ += lutSin(instPhase) * amp
                st.carrierPhase += st.phaseInc
                if (st.carrierPhase > TWO_PI) st.carrierPhase -= TWO_PI
                if (st.carrierPhase < 0.0) st.carrierPhase += TWO_PI
            }
            out[s * 2] = accI.toFloat()
            out[s * 2 + 1] = accQ.toFloat()
        }
        return nSamples
    }
}
