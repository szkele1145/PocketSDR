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

    /**
     * 累计调谐失败次数。
     *
     * 调谐在硬件上可能失败（I2C 抖动、供电不足），而 source.centerFreq 是
     * 一个属性赋值、拿不到成功与否。调用方通过比较这个计数在调谐前后是否
     * 增长，来判断「这次调谐到底成没成」——否则界面显示的频率和硬件实际
     * 工作的频率会脱钩，用户听到的还是上一个台。
     */
    val tuneFailureCount: Int get() = 0
}
