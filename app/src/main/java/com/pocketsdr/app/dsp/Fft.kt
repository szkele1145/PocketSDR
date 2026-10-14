package com.pocketsdr.app.dsp

/**
 * FFT：radix-2 迭代实现（bit-reversal + 蝶形），纯 Kotlin。
 * 大小必须为 2 的幂。
 */
class Fft(private val n: Int) {
    val size: Int = n
    private val cos = FloatArray(n / 2)
    private val sin = FloatArray(n / 2)
    private val workRe = FloatArray(n)
    private val workIm = FloatArray(n)

    init {
        require(n and (n - 1) == 0) { "FFT 大小必须是 2 的幂: $n" }
        for (i in 0 until n / 2) {
            cos[i] = Math.cos(2.0 * Math.PI * i / n).toFloat()
            sin[i] = Math.sin(2.0 * Math.PI * i / n).toFloat()
        }
    }

    /**
     * 就地复数 FFT。re/im 长度必须为 n。
     * [inverse] = true 时做逆变换（含 1/n 归一化）。
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        // 位反转置换
        var i = 0
        for (j in 1 until n) {
            var bit = n shr 1
            while (i and bit != 0) {
                i = i xor bit
                bit = bit shr 1
            }
            i = i or bit
            if (j < i) {
                var t = re[j]; re[j] = re[i]; re[i] = t
                t = im[j]; im[j] = im[i]; im[i] = t
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var k = 0
            while (k < n) {
                var j = 0
                while (j < half) {
                    val c = cos[j * step]
                    val s = if (inverse) -sin[j * step] else sin[j * step]
                    val l = k + j
                    val r = k + j + half
                    val tr = re[r] * c + im[r] * s
                    val ti = im[r] * c - re[r] * s
                    re[r] = re[l] - tr
                    im[r] = im[l] - ti
                    re[l] += tr
                    im[l] += ti
                    j++
                }
                k += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1f / n
            for (m in 0 until n) { re[m] *= inv; im[m] *= inv }
        }
    }

    /**
     * 功率谱：输入 n 个复数 IQ 样本（交织 i0,q0,i1,q1...），输出 n 个点的功率
     * （bin k 对应频率 k*fs/n，含负频率，调用方用 fftshift 排列为 -fs/2..fs/2）。
     * 返回 [out] 供链式使用。
     */
    fun powerSpectrum(iq: FloatArray, out: FloatArray): FloatArray {
        // 拆交织：iq = [i0,q0,i1,q1,...] 长度 2n → workRe=I, workIm=Q
        var k = 0
        for (s in 0 until n) {
            workRe[s] = iq[k]
            workIm[s] = iq[k + 1]
            k += 2
        }
        transform(workRe, workIm)
        for (b in 0 until n) {
            out[b] = workRe[b] * workRe[b] + workIm[b] * workIm[b]
        }
        return out
    }
}
