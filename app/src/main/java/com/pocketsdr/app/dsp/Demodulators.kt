package com.pocketsdr.app.dsp

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 解调器集合。流式接口，供引擎在各自速率点调用。
 */
object Demodulators {

    /** 一阶去加重（WFM 用，50µs 中国/欧标） */
    class DeEmphasis(tauUs: Float, sampleRate: Float) {
        private var y = 0f
        private val a = kotlin.math.exp(-1f / (tauUs * 1e-6f * sampleRate))
        fun process(x: Float): Float {
            y = a * y + (1f - a) * x
            return y
        }
        fun reset() { y = 0f }
    }

    /**
     * FM 鉴频：四象限鉴相器 atan2(Q,I) 差分求瞬时频率。
     * 输出 dPhase/π ∈ [-1,1]，1.0 = ±fs/2 频偏。
     */
    class FmDemod {
        private var lastPhase = 0f
        fun demodulate(i: Float, q: Float): Float {
            val phase = atan2(q, i)
            var d = phase - lastPhase
            lastPhase = phase
            if (d > PI_F) d -= TWO_PI_F
            else if (d < -PI_F) d += TWO_PI_F
            return d / PI_F
        }
        fun reset() { lastPhase = 0f }
    }

    /**
     * WFM 立体声解码：19kHz 导频 PLL 锁相 → 38kHz 开关解调 L-R。
     * 输入：鉴频后的多路复用信号 m(t) @ [sampleRate]。
     * m(t) = (L+R)/2 + (L-R)·cos(2π·38k·t) + 0.1·cos(2π·19k·t)
     *
     * PLL：鉴相 m_pilot × cos(φ)，比例+积分环路控制 VCO。
     */
    class StereoDecoder(private val sampleRate: Float) {
        // 导频提取（Q=12，BW≈1.6kHz）
        private val pilotBp = Biquad.Bandpass(19000f, sampleRate, q = 12f)
        // PLL 环路滤波
        private val loopLp = Biquad.Lowpass(400f, sampleRate)
        // 差信号/音频低通（抗 38k 交叉项）
        private val subLp = Biquad.Lowpass(15000f, sampleRate)
        private val sumLpL = Biquad.Lowpass(15000f, sampleRate)
        private val sumLpR = Biquad.Lowpass(15000f, sampleRate)

        private var phase = 0.0            // 19k VCO 相位
        private var freqInteg = 0.0        // 频率积分项
        private val baseInc = TWO_PI * 19000.0 / sampleRate
        private var pilotEnv = 0f          // 导频包络（锁定指示）
        private var lockSmooth = 0f

        /** 锁定强度 0..1（UI 显示立体声指示用） */
        val stereoLock: Float get() = lockSmooth

        /**
         * 解码一帧。返回 (L, R)。
         * 无导频（单声道台）时自动退化为 L=R=m。
         */
        fun decode(m: Float): Pair<Float, Float> {
            val pilot = pilotBp.process(m)
            // 鉴相 + 包络
            val pd = pilot * cos(phase).toFloat()
            val err = loopLp.process(pd)
            pilotEnv = abs(pilot)
            // 环路：比例 + 慢积分
            freqInteg = freqInteg * 0.9999 + err * 8e-6
            phase += baseInc + err * 0.05 + freqInteg
            if (phase > TWO_PI) phase -= TWO_PI
            if (phase < 0.0) phase += TWO_PI
            lockSmooth = if (pilotEnv > PILOT_ON) {
                (lockSmooth + PILOT_ON / pilotEnv * 0.02f).coerceAtMost(1f)
            } else lockSmooth * 0.995f

            if (lockSmooth < 0.3f) {
                // 单声道
                return m to m
            }
            // 38k 开关解调：m × cos(2φ) → LP = (L-R)/2
            val sub = subLp.process((m * cos(2.0 * phase)).toFloat())
            val l = sumLpL.process(m + 2f * sub)
            val r = sumLpR.process(m - 2f * sub)
            return l to r
        }

        fun reset() {
            pilotBp.reset(); loopLp.reset(); subLp.reset()
            sumLpL.reset(); sumLpR.reset()
            phase = 0.0; freqInteg = 0.0; lockSmooth = 0f
        }

        companion object {
            private const val TWO_PI = 2.0 * Math.PI
            private const val PILOT_ON = 0.008f
        }
    }

    /** NFM 解调：同鉴频器（窄偏在引擎里标度） */
    class NfmDemod internal constructor() {
        private val core = FmDemod()
        fun demodulate(i: Float, q: Float): Float = core.demodulate(i, q) * 0.4f
        fun reset() = core.reset()
    }

    /** AM 包络检波 + 慢速 AGC */
    class AmDemod {
        private var agc = 1f
        private var envAvg = 0f
        fun demodulate(i: Float, q: Float): Float {
            val env = sqrtApprox(i * i + q * q)
            envAvg = envAvg * 0.999f + env * 0.001f
            // 慢速 AGC：目标平均包络 0.3
            if (envAvg > 1e-6f) {
                val err = 0.3f / envAvg
                agc += (err - 1f) * 0.001f
                if (agc > 100f) agc = 100f
                if (agc < 0.01f) agc = 0.01f
            }
            return env * agc
        }
        fun reset() { agc = 1f; envAvg = 0f }
    }

    /** 平方根快速近似（两次牛顿迭代，音频精度足够） */
    fun sqrtApprox(x: Float): Float {
        if (x <= 0f) return 0f
        val half = x * 0.5f
        var i = java.lang.Float.floatToIntBits(x)
        i = 0x5f3759df - (i shr 1)
        var y = java.lang.Float.intBitsToFloat(i)
        y = y * (1.5f - half * y * y)
        y = y * (1.5f - half * y * y)
        return x * y
    }

    private const val PI_F = Math.PI.toFloat()
    private const val TWO_PI_F = (2.0 * Math.PI).toFloat()
}
