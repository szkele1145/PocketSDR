package com.pocketsdr.app.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * 复数抽取器：低通 FIR + 整数倍抽取，多相优化（只在输出点做卷积）。
 */
class ComplexDecimator(
    val factor: Int,
    taps: Int,
    /** 归一化截止（相对输入采样率，0..0.5） */
    cutoff: Float
) {
    private val n = taps
    private val coef = FloatArray(taps)
    private val ringI = FloatArray(taps)
    private val ringQ = FloatArray(taps)
    private var wpos = 0   // 下一个写入位置
    private var phase = 0  // 输入相位计数

    init {
        require(factor in 2..64)
        val m = taps - 1
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val w = 0.54 - 0.46 * kotlin.math.cos(2.0 * PI * i / m)
            coef[i] = (sinc * w).toFloat()
            sum += coef[i]
        }
        val s = sum.toFloat()
        for (i in 0 until taps) coef[i] /= s
    }

    class Out { @JvmField var i = 0f; @JvmField var q = 0f }

    private val outBuf = Out()

    /**
     * 输入一个复样本。每 [factor] 个输入产出一个输出。
     * 返回非 null 表示有输出。
     */
    fun process(i: Float, q: Float): Out? {
        ringI[wpos] = i
        ringQ[wpos] = q
        wpos = if (wpos + 1 == n) 0 else wpos + 1
        if (++phase < factor) return null
        phase = 0
        // 卷积：ring 从 wpos（最老）到 wpos-1（最新），coef[0] 对应最老
        var accI = 0f
        var accQ = 0f
        var idx = wpos
        for (k in 0 until n) {
            val c = coef[k]
            accI += c * ringI[idx]
            accQ += c * ringQ[idx]
            idx = if (idx + 1 == n) 0 else idx + 1
        }
        outBuf.i = accI
        outBuf.q = accQ
        return outBuf
    }

    fun reset() {
        java.util.Arrays.fill(ringI, 0f)
        java.util.Arrays.fill(ringQ, 0f)
        wpos = 0
        phase = 0
    }
}

/** 实数抽取器（音频降采样用） */
class RealDecimator(val factor: Int, taps: Int, cutoff: Float) {
    private val n = taps
    private val coef = FloatArray(taps)
    private val ring = FloatArray(taps)
    private var wpos = 0
    private var phase = 0

    init {
        val m = taps - 1
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val w = 0.54 - 0.46 * kotlin.math.cos(2.0 * PI * i / m)
            coef[i] = (sinc * w).toFloat()
            sum += coef[i]
        }
        val s = sum.toFloat()
        for (i in 0 until taps) coef[i] /= s
    }

    fun process(x: Float): Float? {
        ring[wpos] = x
        wpos = if (wpos + 1 == n) 0 else wpos + 1
        if (++phase < factor) return null
        phase = 0
        var acc = 0f
        var idx = wpos
        for (k in 0 until n) {
            acc += coef[k] * ring[idx]
            idx = if (idx + 1 == n) 0 else idx + 1
        }
        return acc
    }

    fun reset() {
        java.util.Arrays.fill(ring, 0f)
        wpos = 0
        phase = 0
    }
}
