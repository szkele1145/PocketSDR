package com.pocketsdr.app.dsp

import kotlin.math.PI

/**
 * sin/cos 查表（LUT）：DSP 热路径用（每秒数百万次调用），
 * 比 Math.sin 快 ~10 倍，线性插值精度 ~1e-6（音频/通信足够）。
 */
object TrigLut {
    private const val BITS = 12
    private const val SIZE = 1 shl BITS          // 4096
    private const val MASK = SIZE - 1
    private const val TWO_PI = 2.0 * PI
    private const val SCALE = SIZE / TWO_PI

    private val SIN = DoubleArray(SIZE + 1).also {
        for (i in 0..SIZE) it[i] = kotlin.math.sin(TWO_PI * i / SIZE)
    }

    /** 快速 sin：相位任意值（内部取模） */
    fun sin(phase: Double): Double {
        val p = phase * SCALE
        val idx = p.toInt()
        val frac = p - idx
        val i0 = idx and MASK
        return SIN[i0] + (SIN[i0 + 1] - SIN[i0]) * frac
    }

    fun cos(phase: Double): Double = sin(phase + PI / 2)
}
