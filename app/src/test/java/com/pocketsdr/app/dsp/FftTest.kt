package com.pocketsdr.app.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFT 正确性测试（纯 JVM）。
 */
class FftTest {

    @Test
    fun `单频音峰值在正确 bin`() {
        val n = 256
        val fft = Fft(n)
        val fs = 1024f
        val f0 = 128f   // bin 32
        val iq = FloatArray(n * 2)
        for (s in 0 until n) {
            val ph = 2.0 * PI * f0 * s / fs
            iq[s * 2] = kotlin.math.cos(ph).toFloat()
            iq[s * 2 + 1] = kotlin.math.sin(ph).toFloat()
        }
        val out = FloatArray(n)
        fft.powerSpectrum(iq, out)
        var maxBin = 0
        var maxV = 0f
        for (k in 0 until n) {
            if (out[k] > maxV) { maxV = out[k]; maxBin = k }
        }
        assertEquals(32, maxBin)
    }

    @Test
    fun `直流信号峰值在 bin0`() {
        val n = 64
        val fft = Fft(n)
        val iq = FloatArray(n * 2) { if (it % 2 == 0) 1f else 0f }
        val out = FloatArray(n)
        fft.powerSpectrum(iq, out)
        assertTrue("bin0 应为峰值", out[0] > out[1])
        assertTrue("bin0 应远大于其他", out[0] > 100f)
    }

    @Test
    fun `负频率峰值在镜像 bin`() {
        // -fs/8 信号 → bin n - n/8
        val n = 128
        val fft = Fft(n)
        val fs = 1000f
        val f0 = -125f  // -fs/8
        val iq = FloatArray(n * 2)
        for (s in 0 until n) {
            val ph = 2.0 * PI * f0 * s / fs
            iq[s * 2] = kotlin.math.cos(ph).toFloat()
            iq[s * 2 + 1] = kotlin.math.sin(ph).toFloat()
        }
        val out = FloatArray(n)
        fft.powerSpectrum(iq, out)
        var maxBin = 0
        var maxV = 0f
        for (k in 0 until n) {
            if (out[k] > maxV) { maxV = out[k]; maxBin = k }
        }
        assertEquals(n - 16, maxBin)  // -125Hz → bin 128-16=112
    }
}
