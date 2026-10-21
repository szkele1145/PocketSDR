package com.pocketsdr.app.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解调器数学验证（纯 JVM）。
 */
class DemodulatorTest {

    @Test
    fun `FmDemod 恒定频偏产生恒定输出`() {
        // fs=256k, 频偏 75kHz → 归一化输出 75000/128000 ≈ 0.586
        val demod = Demodulators.FmDemod()
        val fs = 256_000.0
        val dev = 75_000.0
        var phase = 0.0
        var last = 0f
        for (s in 0 until 10_000) {
            phase += 2.0 * PI * dev / fs
            val v = demod.demodulate(cos(phase).toFloat(), sin(phase).toFloat())
            if (s > 100) last = v  // 跳过暂态
        }
        val expected = (dev / (fs / 2)).toFloat()
        assertTrue("实际 $last 期望 ~$expected", abs(last - expected) < 0.02f)
    }

    @Test
    fun `Hilbert 变换输出相位滞后 90 度`() {
        // H(sin) = -cos（-90° 移相）
        val fir = HilbertFir(65)
        val align = HilbertFir.Aligner(fir.groupDelay)
        val fs = 32_000.0
        val f0 = 1000.0
        // 稳态后比较 align(I) 与 H(Q)：当 I=cos, Q=sin 时
        // SSB USB = I - H(Q)；用纯音验证包络恒定
        val ssb = HilbertFir.Companion.SsbDemod(65)
        var minA = Float.MAX_VALUE
        var maxA = -Float.MAX_VALUE
        for (s in 0 until 8192) {
            val ph = 2.0 * PI * f0 * s / fs
            val v = ssb.demodulate(cos(ph).toFloat(), sin(ph).toFloat(), upper = true)
            val a = abs(v)
            if (s > 2048) {  // 跳过滤波器暂态
                if (a < minA) minA = a
                if (a > maxA) maxA = a
            }
        }
        // USB 音 (f0=1k) 应通过：包络稳定在 ~sqrt(2)/1 附近
        // LSB 应抑制：包络接近 0
        val ssb2 = HilbertFir.Companion.SsbDemod(65)
        var minB = Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        for (s in 0 until 8192) {
            val ph = 2.0 * PI * f0 * s / fs
            val v = ssb2.demodulate(cos(ph).toFloat(), sin(ph).toFloat(), upper = false)
            val a = abs(v)
            if (s > 2048) {
                if (a < minB) minB = a
                if (a > maxB) maxB = a
            }
        }
        // USB 包络均值应远大于 LSB（镜像抑制）
        val usbAvg = (minA + maxA) / 2
        val lsbAvg = (minB + maxB) / 2
        assertTrue("USB $usbAvg 应显著大于 LSB $lsbAvg", usbAvg > lsbAvg * 3 + 0.3f)
    }

    @Test
    fun `立体声解码器锁定导频并分离 L R`() {
        // 构造 MPX：L=0.8 sin(2π·400t), R=0.2 sin(2π·400t)（不同幅度便于验证分离）
        // m = (L+R) + (L-R)·cos(2π·38k t) + 0.1·cos(2π·19k t)
        val fs = 256_000.0
        val dec = Demodulators.StereoDecoder(256_000f)
        var sumL = 0.0
        var sumR = 0.0
        var n = 0
        var phase38 = 0.0
        var phase19 = 0.0
        for (s in 0 until 256_000) {  // 1 秒
            val t = s / fs
            val l = 0.8 * sin(2 * PI * 400 * t)
            val r = 0.2 * sin(2 * PI * 400 * t)
            phase38 += 2 * PI * 38000 / fs
            if (phase38 > 2 * PI) phase38 -= 2 * PI
            phase19 += 2 * PI * 19000 / fs
            if (phase19 > 2 * PI) phase19 -= 2 * PI
            val mpx = ((l + r) / 2 + (l - r) * cos(phase38) * 2 + 0.1 * cos(phase19)).toFloat()
            val (ol, or) = dec.decode(mpx)
            if (s > 128_000) {  // PLL 锁定后统计
                sumL += ol * ol
                sumR += or * or
                n++
            }
        }
        val rmsL = kotlin.math.sqrt(sumL / n)
        val rmsR = kotlin.math.sqrt(sumR / n)
        // 400Hz 时 L 应比 R 强得多（L=0.8, R=0.2）
        assertTrue("L RMS=$rmsL 应大于 R RMS=$rmsR 的 2 倍", rmsL > rmsR * 2)
        assertTrue("应已锁定立体声 (lock=${dec.stereoLock})", dec.stereoLock > 0.5f)
    }

    @Test
    fun `抽取器抑制带外信号`() {
        // 原来的写法把 5kHz 放 I 通道、120kHz 放 Q 通道，却只量 r.i——
        // 量到的是带内音自己的幅度（~1.0），不是带外残留，所以这个用例
        // 永远失败。正确做法是分开量：只喂带内音看通过多少，只喂带外音看漏多少。
        val fsIn = 1_024_000.0
        val decIn = ComplexDecimator(4, 65, 0.09f)
        var maxIn = 0f
        for (s in 0 until 16_384) {
            val r = decIn.process(sin(2 * PI * 5000 * s / fsIn).toFloat(), 0f)
            if (r != null && s > 4096) {
                val a = kotlin.math.abs(r.i)
                if (a > maxIn) maxIn = a
            }
        }
        val decOut = ComplexDecimator(4, 65, 0.09f)
        var maxOut = 0f
        for (s in 0 until 16_384) {
            val r = decOut.process(sin(2 * PI * 120_000 * s / fsIn).toFloat(), 0f)
            if (r != null && s > 4096) {
                val a = kotlin.math.abs(r.i)
                if (a > maxOut) maxOut = a
            }
        }
        assertTrue("带内 5kHz 应基本无损通过（幅度 $maxIn）", maxIn > 0.8f)
        // 120kHz 离 92kHz 截止不远，抑制会弱一些，但必须明显低于带内
        val suppressionDb = 20 * kotlin.math.log10(maxIn / maxOut.coerceAtLeast(1e-9f))
        assertTrue("带外抑制只有 $suppressionDb dB（带内 $maxIn 带外 $maxOut）", suppressionDb > 10f)
    }
}
