package com.pocketsdr.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * USB 设备诊断。
 *
 * 存在的理由：非官方 RTL-SDR 棒子的 VID/PID 五花八门（同一颗 FC0013 会被几十种
 * PID 封装），一旦落在白名单之外，应用会「完全看不见设备」——
 * 用户只会觉得「插了没反应」，却拿不到任何可排查的信息。
 *
 * 这里把系统枚举到的设备原样摊开（VID/PID、授权状态、有无 bulk-in 端点），
 * 并配合「强制尝试」入口，让非白名单设备也有机会直接连上。
 */
object UsbDiag {

    /** 单条 USB 设备信息（纯快照，无状态） */
    data class Entry(
        val vid: Int,
        val pid: Int,
        val name: String,
        val known: Boolean,
        val granted: Boolean,
        val bulkIn: Boolean,
        val interfaces: Int
    ) {
        /** 形如 0BDA:2838，直接抄给开发者即可定位设备 */
        val id: String get() = "%04X:%04X".format(vid, pid)
    }

    /**
     * 白名单，必须与 res/xml/device_filter.xml 完全一致。
     * 两处不一致会出现最迷惑的现象：系统自动拉起应用，应用却拒绝认设备。
     */
    fun isKnown(vid: Int, pid: Int): Boolean = when (vid) {
        0x0bda -> pid == 0x2832 || pid == 0x2838 || pid == 0x2831
        0x0413 -> pid == 0x6680
        else -> false
    }

    /** 是否有 bulk-in 端点：RTL2832 的 IQ 数据就走这个，没有则必然连不上 */
    private fun hasBulkIn(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) return true
            }
        }
        return false
    }

    /** 枚举当前所有 USB 设备；任何异常都不该让设置页崩掉 */
    fun snapshot(usb: UsbManager): List<Entry> = try {
        usb.deviceList.values.map { d ->
            Entry(
                vid = d.vendorId,
                pid = d.productId,
                name = d.deviceName ?: "?",
                known = isKnown(d.vendorId, d.productId),
                granted = usb.hasPermission(d),
                bulkIn = hasBulkIn(d),
                interfaces = d.interfaceCount
            )
        }.sortedBy { it.id }
    } catch (_: Exception) {
        emptyList()
    }

    /** 按 VID/PID 找回设备（「尝试连接」用；拔出后返回 null） */
    fun find(usb: UsbManager, vid: Int, pid: Int): UsbDevice? =
        try {
            usb.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
        } catch (_: Exception) {
            null
        }

    /** 一句话结论，给用户看的排查方向 */
    fun advice(e: Entry): String = when {
        !e.bulkIn -> "无 bulk-in 端点，不是 SDR 设备"
        !e.granted -> "等待系统授权弹窗，未授权则点「连接」"
        e.known -> "已知型号，可直接连接"
        else -> "型号未知，点「强制尝试」按 RTL-SDR 试连"
    }
}
