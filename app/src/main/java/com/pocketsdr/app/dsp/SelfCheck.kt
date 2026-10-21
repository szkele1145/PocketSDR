package com.pocketsdr.app.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP 链路自检。
 *
 * ## 为什么需要它
 *
 * 真机到货后如果听到的声音有杂音/嘶嘶声/串台，排查的第一件事是
 * 「软件出问题了还是硬件出问题了」。这个自检就是回答这个问题的：
 * 它在**不接硬件**的情况下，用已知的合成信号跑一遍 DSP 链路的每一环，
 * 逐项给出 PASS/FAIL 和实测数字。
 *
 * - 全部 PASS → 软件侧没问题，杂音来自天线/供电/USB/硬件
 * - 有 FAIL → 软件侧确实有问题，且会直接指出是哪一环
 *
 * ## 为什么放在 dsp 包里
 *
 * 这里只用纯 Kotlin，不碰 android.*，所以同一份代码有两个消费者：
 *
 * - App 内的「🩺 DSP 自检」按钮（用户点一下就看到结果）
 * - `tools/simcheck`（桌面上跑，改完 DSP 立刻能验）
 *
 * 两边共用一份实现，避免「桌面验证通过、手机上却不一致」这种事。
 *
 * ## 线程模型
 *
 * 立体声那项要跑 1 秒仿真（25.6 万次迭代），不能放在 UI 线程。
 * 调用方负责放到后台线程。
 */
object SelfCheck {

    /** 单项检查结果 */
    data class Item(
        val name: String,
        val passed: Boolean,
        /** 实测数字（人话），如「抑制 42dB」 */
        val detail: String,
        /** 失败时的原因 */
        val reason: String? = null
    )

    data class Report(val items: List<Item>) {
        val passedCount: Int get() = items.count { it.passed }
        val allPassed: Boolean get() = items.all { it.passed }
    }

    /**
     * 跑完整自检。
     *
     * @param onProgress 每完成一项回调一次（项序号从 0 开始），供 UI 显示进度
     */
    fun runAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Report {
        val items = ArrayList<Item>(6)
        val total = 6

        items.add(checkFftBin()); onProgress(1, total)
        items.add(checkDecimator()); onProgress(2, total)
        items.add(checkFmDemod()); onProgress(3, total)
        items.add(checkSsb()); onProgress(4, total)
        items.add(checkStereo()); onProgress(5, total)
        items.add(checkAudioPath()); onProgress(6, total)

        return Report(items)
    }

    // ------------------------------------------------------------------
    // 1) FFT：单频音的峰值必须落在正确的 bin 上
    //
    // FFT 算错的话，频谱上所有东西的位置都是错的——搜台找不准、
    // 噪声底统计失真、峰值判据失效。这是整条链的地基。
    // ------------------------------------------------------------------
    private fun checkFftBin(): Item {
        val n = 256
        val fft = Fft(n)
        val fs = 1024f
        val f0 = 128f          // bin 32
        val iq = FloatArray(n * 2)
        for (s in 0 until n) {
            val ph = 2.0 * PI * f0 * s / fs
            iq[s * 2] = cos(ph).toFloat()
            iq[s * 2 + 1] = sin(ph).toFloat()
        }
        val out = FloatArray(n)
        fft.powerSpectrum(iq, out)
        var maxBin = 0
        var maxV = 0f
        for (k in 0 until n) {
            if (out[k] > maxV) { maxV = out[k]; maxBin = k }
        }
        return if (maxBin == 32) {
            Item("FFT 频率定位", true, "128Hz 落在 bin $maxBin")
        } else {
            Item("FFT 频率定位", false, "峰值在 bin $maxBin",
                "128Hz 信号的峰值跑到了 bin $maxBin，FFT 变换有问题")
        }
    }

    // ------------------------------------------------------------------
    // 2) 抽取器：带内要过，带外要压
    //
    // 抽取器是「把 1.024MHz 缩到 256kHz」的那一环。带外压不住的话，
    // 整个 FM 广播段会互相混叠，听起来是一片糊。
    // ------------------------------------------------------------------
    private fun checkDecimator(): Item {
        val fsIn = 1_024_000.0
        val decIn = ComplexDecimator(4, 65, 0.09f)
        var maxIn = 0f
        for (s in 0 until 16_384) {
            val r = decIn.process(sin(2 * PI * 5000 * s / fsIn).toFloat(), 0f)
            if (r != null && s > 4096) {
                val a = abs(r.i)
                if (a > maxIn) maxIn = a
            }
        }
        val decOut = ComplexDecimator(4, 65, 0.09f)
        var maxOut = 0f
        for (s in 0 until 16_384) {
            val r = decOut.process(sin(2 * PI * 120_000 * s / fsIn).toFloat(), 0f)
            if (r != null && s > 4096) {
                val a = abs(r.i)
                if (a > maxOut) maxOut = a
            }
        }
        val suppDb = 20 * (ln((maxIn / maxOut.coerceAtLeast(1e-9f)).toDouble()) / LN10).toFloat()
        return if (maxIn > 0.8f && suppDb > 10f) {
            Item("抽取器带外抑制", true, "带内通过 ${"%.2f".format(maxIn)}，带外抑制 ${suppDb.toInt()}dB")
        } else {
            Item("抽取器带外抑制", false, "带内 ${"%.2f".format(maxIn)}，抑制 ${suppDb.toInt()}dB",
                if (maxIn <= 0.8f) "带内信号被削掉了（幅度只有 ${"%.2f".format(maxIn)}）"
                else "带外信号只被压了 ${suppDb.toInt()}dB，混叠会漏进信号带")
        }
    }

    // ------------------------------------------------------------------
    // 3) FM 解调：恒定频偏必须解出恒定输出
    //
    // 解调器的增益系数错了，音频就会偏（失真）或忽大忽小。
    // ------------------------------------------------------------------
    private fun checkFmDemod(): Item {
        val demod = Demodulators.FmDemod()
        val fs = 256_000.0
        val dev = 75_000.0
        var phase = 0.0
        var last = 0f
        for (s in 0 until 10_000) {
            phase += 2.0 * PI * dev / fs
            val v = demod.demodulate(cos(phase).toFloat(), sin(phase).toFloat())
            if (s > 100) last = v
        }
        val expected = (dev / (fs / 2)).toFloat()
        val err = abs(last - expected)
        return if (err < 0.02f) {
            Item("FM 解调增益", true, "75kHz 频偏 → ${"%.3f".format(last)}（期望 ${"%.3f".format(expected)}）")
        } else {
            Item("FM 解调增益", false, "输出 ${"%.3f".format(last)}，期望 ${"%.3f".format(expected)}",
                "解调增益差了 ${"%.3f".format(err)}，音频会失真")
        }
    }

    // ------------------------------------------------------------------
    // 4) SSB：USB 要通过，LSB（镜像）要被抑制
    //
    // 镜像抑制不住的话，SSB 电台会同时听到上下边带，对方的声音和自己的
    // 声音叠在一起，完全没法通话。
    // ------------------------------------------------------------------
    private fun checkSsb(): Item {
        val fs = 32_000.0
        val f0 = 1000.0
        fun envelope(upper: Boolean): Float {
            val ssb = HilbertFir.Companion.SsbDemod(65)
            var minA = Float.MAX_VALUE
            var maxA = -Float.MAX_VALUE
            for (s in 0 until 8192) {
                val ph = 2.0 * PI * f0 * s / fs
                val v = ssb.demodulate(cos(ph).toFloat(), sin(ph).toFloat(), upper)
                val a = abs(v)
                if (s > 2048) {
                    if (a < minA) minA = a
                    if (a > maxA) maxA = a
                }
            }
            return (minA + maxA) / 2
        }
        val usb = envelope(true)
        val lsb = envelope(false)
        val rejDb = 20 * (ln((usb / lsb.coerceAtLeast(1e-9f)).toDouble()) / LN10).toFloat()
        return if (usb > lsb * 3 + 0.3f) {
            Item("SSB 镜像抑制", true, "镜像抑制 ${rejDb.toInt()}dB")
        } else {
            Item("SSB 镜像抑制", false, "镜像抑制只有 ${rejDb.toInt()}dB",
                "上下边带分不开，对方的声音会和自己的声音叠在一起")
        }
    }

    // ------------------------------------------------------------------
    // 5) 立体声：导频锁定 + L/R 分离
    //
    // 导频锁不住，立体声台会变成单声道杂音（或者左右反相）。
    // ------------------------------------------------------------------
    private fun checkStereo(): Item {
        val fs = 256_000.0
        val dec = Demodulators.StereoDecoder(256_000f)
        var sumL = 0.0
        var sumR = 0.0
        var n = 0
        var phase38 = 0.0
        var phase19 = 0.0
        for (s in 0 until 256_000) {   // 1 秒
            val t = s / fs
            val l = 0.8 * sin(2 * PI * 400 * t)
            val r = 0.2 * sin(2 * PI * 400 * t)
            phase38 += 2 * PI * 38000 / fs
            if (phase38 > 2 * PI) phase38 -= 2 * PI
            phase19 += 2 * PI * 19000 / fs
            if (phase19 > 2 * PI) phase19 -= 2 * PI
            val mpx = ((l + r) / 2 + (l - r) * cos(phase38) * 2 + 0.1 * cos(phase19)).toFloat()
            val (ol, or) = dec.decode(mpx)
            if (s > 128_000) {        // PLL 锁定后再统计
                sumL += ol * ol
                sumR += or * or
                n++
            }
        }
        val rmsL = sqrt(sumL / n)
        val rmsR = sqrt(sumR / n)
        val sep = if (rmsR > 1e-9) rmsL / rmsR else 999.0
        val locked = dec.stereoLock > 0.5f
        return if (locked && rmsL > rmsR * 2) {
            Item("立体声解码", true, "导频锁定 ${"%.2f".format(dec.stereoLock)}，左右分离 ${"%.1f".format(sep)}:1")
        } else {
            Item("立体声解码", false,
                "导频锁定 ${"%.2f".format(dec.stereoLock)}，左右分离 ${"%.1f".format(sep)}:1",
                if (!locked) "19kHz 导频锁不住，立体声台会变成杂音"
                else "左右声道分不开，L/R 能量比只有 ${"%.1f".format(sep)}:1")
        }
    }

    // ------------------------------------------------------------------
    // 6) 音频通路：解调输出能被增益控制稳定住
    //
    // AGC 不收敛的话，弱台听不见、强台炸耳朵——这是最常见的
    // 「软件版本没写好」的表现。
    // ------------------------------------------------------------------
    private fun checkAudioPath(): Item {
        val fs = 32_000f
        val agc = AudioAgc(fs)
        // 一段先弱后强的信号：AGC 应该把输出电平拉齐
        var peak = 0f
        for (s in 0 until 200_000) {
            val env = if (s < 100_000) 0.02f else 0.6f
            val v = sin(2 * PI * 1000 * s / fs).toFloat() * env
            val o = agc.process(v)
            val a = abs(o)
            if (a > peak) peak = a
        }
        val ok = peak in 0.01f..1.0f && !peak.isNaN()
        return if (ok) {
            Item("音频通路增益控制", true, "输出峰值 ${"%.3f".format(peak)}")
        } else {
            Item("音频通路增益控制", false, "输出峰值 ${"%.3f".format(peak)}",
                "增益控制没有把电平稳住，弱台听不见或强台爆音")
        }
    }

    private const val LN10 = 2.302585092994046
}
