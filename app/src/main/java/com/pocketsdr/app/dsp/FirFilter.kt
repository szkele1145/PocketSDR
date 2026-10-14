package com.pocketsdr.app.dsp

/**
 * FIR 低通滤波器，用于解调前的信道滤波与降采样抽取。
 * 窗函数法（Hamming）设计，系数在构造时预计算。
 */
class FirFilter(
    /** 滤波器长度（抽头数，奇数最佳） */
    taps: Int,
    /** 归一化截止频率（相对采样率，0..0.5） */
    cutoff: Float
) {
    private val h = FloatArray(taps)
    private val delay = FloatArray(taps)
    private var pos = 0
    val length = taps

    init {
        require(taps in 3..1024) { "taps 越界" }
        require(cutoff > 0f && cutoff < 0.5f) { "cutoff 必须在 (0,0.5)" }
        val m = taps - 1
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - m / 2.0
            var sinc = when {
                x == 0.0 -> 2.0 * cutoff
                else -> Math.sin(2.0 * Math.PI * cutoff * x) / (Math.PI * x)
            }
            // Hamming 窗
            val w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / m)
            h[i] = (sinc * w).toFloat()
            sum += h[i]
        }
        // 归一化直流增益为 1
        val s = sum.toFloat()
        for (i in 0 until taps) h[i] /= s
    }

    /** 单样本流式滤波 */
    fun process(x: Float): Float {
        delay[pos] = x
        pos = if (pos + 1 == h.size) 0 else pos + 1
        var acc = 0f
        var idx = pos
        for (k in h.indices) {
            acc += h[k] * delay[idx]
            idx = if (idx + 1 == h.size) 0 else idx + 1
        }
        return acc
    }

    /** 批量滤波 [inArr[0..len) → outArr[0..len)，无状态残留差异 */
    fun processBlock(inArr: FloatArray, outArr: FloatArray, len: Int) {
        for (i in 0 until len) outArr[i] = process(inArr[i])
    }

    fun reset() {
        java.util.Arrays.fill(delay, 0f)
        pos = 0
    }
}

/** 复数 FIR（I/Q 各一路实系数），用于信道滤波与抽取 */
class ComplexFirFilter(taps: Int, cutoff: Float) {
    private val iCh = FirFilter(taps, cutoff)
    private val qCh = FirFilter(taps, cutoff)

    fun process(i: Float, q: Float): Pair<Float, Float> = iCh.process(i) to qCh.process(q)
    fun reset() { iCh.reset(); qCh.reset() }
}

/**
 * 简单但有效的抽取器：FIR 低通 + 整数倍抽取。
 * 用于把 1.024Msps 的基带降到解调器可用的低速率。
 */
class Decimator(
    /** 抽取因子 */
    val factor: Int,
    taps: Int = 33,
    cutoff: Float = 0.45f / factor
) {
    private val lp = ComplexFirFilter(taps, cutoff)
    private var cnt = 0

    /** 输入一个复样本，返回是否产出一个抽取后样本（产出时写入 [out]） */
    fun process(i: Float, q: Float, out: ComplexOut): Boolean {
        val (fi, fq) = lp.process(i, q)
        if (++cnt >= factor) {
            cnt = 0
            out.i = fi; out.q = fq
            return true
        }
        return false
    }

    fun reset() { lp.reset(); cnt = 0 }

    class ComplexOut { @JvmField var i = 0f; @JvmField var q = 0f }
}
