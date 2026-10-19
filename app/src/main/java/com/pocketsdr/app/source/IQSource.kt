package com.pocketsdr.app.source

/**
 * SDR IQ 源统一接口：仿真源与真实 RTL-SDR 实现同一契约。
 */
interface IQSource {
    /** 当前采样率 Hz */
    val sampleRate: Float

    /** 当前中心频率 Hz */
    var centerFreq: Long

    /**
     * 阻塞读取 [nSamples] 个复样本写入 [out]（长度 ≥ 2*nSamples，交织 I,Q）。
     * 返回实际样本数，-1 表示流结束。
     */
    fun read(out: FloatArray, nSamples: Int): Int

    /** 释放资源 */
    fun close() {}

    /** 源是否基于真实硬件 */
    val isReal: Boolean
}
