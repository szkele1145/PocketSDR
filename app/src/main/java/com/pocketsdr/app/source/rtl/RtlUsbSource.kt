package com.pocketsdr.app.source.rtl

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.pocketsdr.app.source.IQSource

/**
 * RTL-SDR USB 源：Rtl2832 基带 + FC0013 tuner → IQSource。
 *
 * 上层（MainActivity）负责 USB 权限授予后传入已 claim 的连接。
 * 读取用同步 bulk transfer（在引擎 DSP 线程上阻塞读）。
 *
 * RTL2832U 输出 unsigned 8bit IQ 交织，归一化到 [-1,1)。
 */
class RtlUsbSource(
    private val conn: UsbDeviceConnection,
    val device: UsbDevice,
    requestedRate: Long
) : IQSource {

    override val isReal = true

    private val rtl: Rtl2832
    private var tuner: Fc0013? = null
    private val tunerType: RtlTuner

    @Volatile override var centerFreq: Long = 98_000_000
        set(value) {
            field = value
            retune()
        }
    override val sampleRate: Float

    private val usbBufSize = 65536           // 64KB = 32768 复样本 @8bit
    private val usbBuf = ByteArray(usbBufSize)
    private var usbPos = 0
    private var usbLen = 0

    private var closed = false

    init {
        require(device.interfaceCount > 0) { "USB 接口数 0" }
        val iface = device.getInterface(0)
        var ep: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val e = iface.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                e.direction == UsbConstants.USB_DIR_IN) {
                ep = e
                break
            }
        }
        checkNotNull(ep) { "未找到 bulk-in 端点" }
        conn.claimInterface(iface, true)
        rtl = Rtl2832(conn, ep)
        check(rtl.initBaseband()) { "RTL2832 基带初始化失败" }
        tunerType = rtl.probeTuner()
        when (tunerType) {
            RtlTuner.FC0013 -> {
                rtl.setI2cRepeater(true)
                val t = Fc0013(rtl)
                check(t.init()) { "FC0013 初始化失败" }
                tuner = t
                rtl.setI2cRepeater(false)
            }
            RtlTuner.R820T ->
                throw UnsupportedOperationException("R820T 暂不支持，本版只适配 FC0013")
            RtlTuner.UNKNOWN ->
                throw UnsupportedOperationException("未识别的 tuner")
        }
        sampleRate = rtl.setSampleRate(requestedRate).toFloat()
        rtl.resetBuffer()
        rtl.setI2cRepeater(true)
        check(tuner!!.setParams(centerFreq)) { "FC0013 调谐失败" }
        rtl.setI2cRepeater(false)
    }

    override fun read(out: FloatArray, nSamples: Int): Int {
        if (closed) return -1
        var produced = 0
        while (produced < nSamples) {
            if (usbPos >= usbLen) {
                val n = rtl.bulkRead(usbBuf, usbBufSize, 1000)
                if (n <= 0) {
                    if (closed) return -1
                    continue
                }
                usbPos = 0
                usbLen = n
            }
            val avail = (usbLen - usbPos) / 2
            if (avail == 0) { usbPos = usbLen; continue }
            val take = minOf(avail, nSamples - produced)
            for (s in 0 until take) {
                out[(produced + s) * 2] = ((usbBuf[usbPos].toInt() and 0xFF) - 128) / 128f
                out[(produced + s) * 2 + 1] = ((usbBuf[usbPos + 1].toInt() and 0xFF) - 128) / 128f
                usbPos += 2
            }
            produced += take
        }
        return produced
    }

    private fun retune() {
        if (closed) return
        val t = tuner ?: return
        try {
            rtl.setI2cRepeater(true)
            t.setParams(centerFreq)
            rtl.setI2cRepeater(false)
        } catch (_: Exception) {}
    }

    override fun close() {
        closed = true
        try { rtl.deinit() } catch (_: Exception) {}
        try { conn.releaseInterface(device.getInterface(0)) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
