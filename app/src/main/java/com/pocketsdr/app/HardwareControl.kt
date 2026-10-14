package com.pocketsdr.app

import com.pocketsdr.app.source.rtl.RtlUsbSource

/**
 * 硬件源控制门面：给 UI 暴露增益控制。
 * 仿真源下 no-op。
 */
class HardwareControl(private val engine: SdrEngine) {

    /** 可用的 LNA 增益档（tenths dB），FC0013 标准表 */
    val lnaGains: IntArray = intArrayOf(
        -99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65,
        67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197
    )

    /** 当前是否真实硬件 */
    val isReal: Boolean get() = engine.isRealSource

    /** 设 LNA 增益（tenths dB，取最近档） */
    fun setLnaGain(tenthsDb: Int): Boolean {
        val src = engine.sourceOrNull as? RtlUsbSource ?: return false
        src.setLnaGain(tenthsDb)
        return true
    }

    /** 手动/自动增益切换 */
    fun setManualGain(manual: Boolean): Boolean {
        val src = engine.sourceOrNull as? RtlUsbSource ?: return false
        src.setGainMode(manual)
        return true
    }
}
