package com.pocketsdr.app.dsp

import kotlin.math.tan

/**
 * biquad 二阶 IIR 节。RBJ Audio EQ Cookbook 公式。
 * 流式单样本接口。
 */
sealed class Biquad {
    protected var b0 = 1f; protected var b1 = 0f; protected var b2 = 0f
    protected var a1 = 0f; protected var a2 = 0f
    protected var x1 = 0f; protected var x2 = 0f
    protected var y1 = 0f; protected var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

    class Lowpass(fc: Float, fs: Float, q: Float = 0.7071f) : Biquad() {
        init {
            val w = 2.0 * Math.PI * fc / fs
            val cw = Math.cos(w); val sw = Math.sin(w)
            val alpha = sw / (2.0 * q)
            b0 = ((1.0 - cw) / 2.0).toFloat()
            b1 = (1.0 - cw).toFloat()
            b2 = ((1.0 - cw) / 2.0).toFloat()
            a1 = (-2.0 * cw).toFloat()
            a2 = ((1.0 + alpha)).toFloat()
            val g = (1.0 + alpha).toFloat()
            b0 /= g; b1 /= g; b2 /= g; a1 /= g; a2 /= g
        }
    }

    class Highpass(fc: Float, fs: Float, q: Float = 0.7071f) : Biquad() {
        init {
            val w = 2.0 * Math.PI * fc / fs
            val cw = Math.cos(w); val sw = Math.sin(w)
            val alpha = sw / (2.0 * q)
            b0 = ((1.0 + cw) / 2.0).toFloat()
            b1 = (-(1.0 + cw)).toFloat()
            b2 = ((1.0 + cw) / 2.0).toFloat()
            a1 = (-2.0 * cw).toFloat()
            a2 = ((1.0 + alpha)).toFloat()
            val g = (1.0 + alpha).toFloat()
            b0 /= g; b1 /= g; b2 /= g; a1 /= g; a2 /= g
        }
    }

    class Bandpass(fc: Float, fs: Float, q: Float = 30f) : Biquad() {
        init {
            val w = 2.0 * Math.PI * fc / fs
            val cw = Math.cos(w); val sw = Math.sin(w)
            val alpha = sw / (2.0 * q)
            b0 = (sw / 2.0).toFloat()
            b1 = 0f
            b2 = -(sw / 2.0).toFloat()
            a1 = (-2.0 * cw).toFloat()
            a2 = (1.0 + alpha).toFloat()
            val g = (1.0 + alpha).toFloat()
            b0 /= g; b1 /= g; b2 /= g; a1 /= g; a2 /= g
        }
    }
}
