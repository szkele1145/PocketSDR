package com.pocketsdr.app.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * FIR Hilbert 变换（±90° 全通移相，线性相位，奇对称系数）。
 *
 * 用于 SSB 相位法解调：
 *   USB: audio = I - H(Q)
 *   LSB: audio = I + H(Q)
 *
 * 系数（中心抽头为 0 的奇对称 FIR）：h[n] = 2/(πn)·sin²(πn/2)，加 Hamming 窗。
 * 延迟 (taps-1)/2 个样本——SSB 解调的 I 路需配相同延迟对齐。
 */
class HilbertFir(val taps: Int) {
    private val h = FloatArray(taps)
    private val delay = FloatArray(taps)
    private var pos = 0
    val groupDelay: Int = (taps - 1) / 2

    init {
        require(taps % 2 == 1 && taps in 3..255) { "taps 必须为奇数" }
        val m = taps - 1
        for (n in 0 until taps) {
            val k = n - m / 2   // 中心对称偏移
            // 环形卷积方向修正（经 DspVerify 验证）：奇对称系数在此遍历方向下需取负
            var c = if (k % 2 == 0) 0f else (-2f / (PI.toFloat() * k))
            // Hamming 窗
            val w = 0.54 - 0.46 * kotlin.math.cos(2.0 * PI * n / m)
            h[n] = c * w.toFloat()
        }
    }

    fun process(x: Float): Float {
        delay[pos] = x
        pos = if (pos + 1 == taps) 0 else pos + 1
        var acc = 0f
        var idx = pos
        for (k in 0 until taps) {
            acc += h[k] * delay[idx]
            idx = if (idx + 1 == taps) 0 else idx + 1
        }
        return acc
    }

    /** 简单延迟线（I 路对齐 Hilbert 的群延迟用） */
    class Aligner(private val delaySamples: Int) {
        private val buf = FloatArray(delaySamples + 1)
        private var pos = 0

        fun process(x: Float): Float {
            val out = buf[pos]
            buf[pos] = x
            pos = (pos + 1) % buf.size
            return out
        }

        fun reset() { java.util.Arrays.fill(buf, 0f); pos = 0 }
    }

    fun reset() {
        java.util.Arrays.fill(delay, 0f)
        pos = 0
    }

    companion object {
        /** 相位法 SSB 解调器：32ksps 复 IQ 进，音频出 */
        class SsbDemod(taps: Int = 65) {
            private val hilb = HilbertFir(taps)
            private val alignI = Aligner(hilb.groupDelay)

            /** [upper] = true USB，false LSB */
            fun demodulate(i: Float, q: Float, upper: Boolean): Float {
                val di = alignI.process(i)
                val hq = hilb.process(q)
                return if (upper) di - hq else di + hq
            }

            fun reset() { hilb.reset(); alignI.reset() }
        }
    }
}
