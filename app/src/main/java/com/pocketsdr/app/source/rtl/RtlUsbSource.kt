package com.pocketsdr.app.source.rtl

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.pocketsdr.app.SdrLog
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

    /** 探测到的 tuner 名称（FC0013 / R820T / UNKNOWN），供自检报告展示 */
    val tunerName: String get() = tunerType.name

    /** 探测过程说明：命中时是「第几轮命中」，失败时是「为什么失败」 */
    val probeDetail: String get() = rtl?.probeDetail ?: ""

    /** demod（基带）寄存器写失败次数：>0 说明基带配置没完全生效 */
    val demodWriteFailures: Int get() = rtl?.demodWriteFailures ?: 0

    /** 累计调谐失败次数（供上层判断「这次调谐成没成」） */
    override val tuneFailureCount: Int get() = tuneFailures

    /**
     * I2C 累计重试次数。
     *
     * 这是 USB 链路稳定性的硬指标：0 = 一次都没失败过（链路很稳）；
     * 偶发几次 = 供电/接触有轻微抖动但被重试救回来了；
     * 几十上百次 = 供电不足或 OTG 线接触不良，该换线了。
     */
    val i2cRetries: Int get() = rtl?.i2cRetryCount ?: 0

    /** USB 设备标识：VID:PID + 产品名，自检报告用 */
    val usbDeviceInfo: String
        get() = "VID=%04X PID=%04X %s".format(
            device.vendorId, device.productId,
            runCatching { device.productName ?: "?" }.getOrDefault("?"))

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
        SdrLog.i("USB", "打开设备 vid=${String.format("%04X", device.vendorId)} pid=${String.format("%04X", device.productId)}")
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
        val claimOk = conn.claimInterface(iface, true)
        SdrLog.i("USB", "claim 接口0: $claimOk (false=被内核驱动占用)")
        // claim 失败绝不能继续：后续 I2C/批量传输会全部超时，
        // 用户看到的却是「OTG 线/供电问题」，排查方向完全被带偏。
        check(claimOk) { "USB 接口被内核驱动占用（无法 claim），换一台设备或关闭其它占用 USB 的应用" }
        rtl = Rtl2832(conn, ep)
        check(rtl.initBaseband()) { "RTL2832 基带初始化失败" }
        SdrLog.i("RTL", "基带初始化 OK")
        tunerType = rtl.probeTuner()
        SdrLog.i("RTL", "tuner 探测: $tunerType")
        when (tunerType) {
            RtlTuner.FC0013 -> {
                rtl.setI2cRepeater(true)
                val t = Fc0013(rtl)
                // repeater 必须在任何退出路径上复位：留在 on 状态的话，
                // 后续所有块寄存器读写都会被转到 I2C 总线上，行为完全错乱。
                val initOk = try {
                    t.init()
                } finally {
                    rtl.setI2cRepeater(false)
                }
                check(initOk) { "FC0013 初始化失败：${t.initDetail.ifEmpty { "未知原因" }}" }
                tuner = t
                SdrLog.i("FC0013", "初始化 OK")
            }
            RtlTuner.R820T ->
                throw UnsupportedOperationException("检测到 R820T 芯片，本版只适配 FC0013（可换用「直接采样」模式收 HF/短波）")
            RtlTuner.UNKNOWN ->
                throw UnsupportedOperationException(
                    "未识别的 tuner：${rtl.probeDetail.ifEmpty { "探测 FC0013/R820T 均无响应，检查天线/供电/OTG" }}"
                )
        }
        val realRate = rtl.setSampleRate(requestedRate)
        check(realRate > 0) { "设置采样率失败（demod 寄存器写入未成功）——USB 通信异常，可换线/换充电口后重试" }
        sampleRate = realRate.toFloat()
        SdrLog.i("RTL", "采样率请求 $requestedRate 实际 ${sampleRate.toLong()}")
        rtl.resetBuffer()
        rtl.setI2cRepeater(true)
        val tuneOk = try {
            tuner!!.setParams(centerFreq)
        } finally {
            rtl.setI2cRepeater(false)
        }
        check(tuneOk) { "FC0013 调谐失败（${centerFreq / 1000} kHz）——I2C 通信未成功，可换线/换充电口后重试" }
        SdrLog.i("FC0013", "初始调谐 ${centerFreq / 1000} kHz OK")
    }

    // bulk 读失败计数（连续超时诊断）
    private var readTimeouts = 0
    /** 触发端点复位的连续超时阈值：1s 超时 × 8 ≈ 8 秒无数据 */
    private val resetAfterTimeouts = 8
    /** 累计复位次数（超过上限说明设备真的不行了，别无限重试） */
    private var resetCount = 0
    private val maxResets = 3

    // ---------- USB 健康状态（UI 可见）----------
    /** 累计读超时次数（供电不稳/OTG 线接触不良会持续增长） */
    @Volatile var totalReadTimeouts = 0
        private set
    /** 累计端点复位次数（≥1 就说明断流发生过） */
    @Volatile var totalResets = 0
        private set
    /** 最近一次数据到达时间戳（ms，SystemClock.elapsedRealtime） */
    @Volatile var lastDataMs = 0L
        private set

    override fun read(out: FloatArray, nSamples: Int): Int {
        if (closed) return -1
        var produced = 0
        while (produced < nSamples) {
            if (usbPos >= usbLen) {
                val n = rtl.bulkRead(usbBuf, usbBufSize, 1000)
                if (n <= 0) {
                    if (closed) return -1
                    readTimeouts++
                    totalReadTimeouts++
                    if (readTimeouts == 1 || readTimeouts % 20 == 0) {
                        SdrLog.w("USB", "bulk 读超时 x${readTimeouts}（OTG 线/供电问题？）")
                    }
                    // 连续超时到阈值：复位采样端点再试。
                    // 旧代码在这里无条件 continue，端点一旦卡住就永远读不到数据，
                    // 而 applyPendingTune() 在 DSP 循环头部——结果是频谱冻结、
                    // 改频率毫无反应，用户只能重启应用。现在主动自救。
                    if (readTimeouts >= resetAfterTimeouts) {
                        if (resetCount < maxResets) {
                            resetCount++
                            totalResets++
                            readTimeouts = 0
                            SdrLog.w("USB", "连续无数据，复位采样端点（第 ${resetCount}/${maxResets} 次）")
                            try {
                                rtl.resetBuffer()
                                usbPos = 0
                                usbLen = 0
                            } catch (e: Exception) {
                                SdrLog.e("USB", "端点复位失败: ${e.message?.take(40)}")
                                return -1
                            }
                        } else {
                            SdrLog.e("USB", "复位 ${maxResets} 次仍无数据，判定设备已失效")
                            return -1
                        }
                    }
                    continue
                }
                readTimeouts = 0
                usbPos = 0
                usbLen = n
                lastDataMs = android.os.SystemClock.elapsedRealtime()
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

    /**
     * 调谐失败累计次数。
     *
     * 以前调谐失败是完全静默的：setParams 的返回值被丢掉，异常也被 catch 吞掉。
     * 后果是「改了频率但还是原来的台」——用户看到的是频率数字变了、声音没变，
     * 完全猜不到是硬件没接受这次调谐。改成可见 + 可计数后，
     * 自检报告里出现「调谐失败 N 次」就能一眼定位。
     */
    @Volatile var tuneFailures = 0
        private set

    /** 硬件设置（增益/增益模式）失败累计次数 */
    @Volatile var hwSettingFailures = 0
        private set

    private fun retune() {
        if (closed) return
        try {
            if (directSampling != 0) {
                // 直接采样模式：调谐 = IF 频率
                rtl.setIfFreq(centerFreq)
                return
            }
            val t = tuner ?: return
            // offset tuning：tuner 调到 f − offs（信号在 IF offs 处，基带由 demod 下变频）
            val tuneTo = if (offsetTuning) centerFreq - offsFreq else centerFreq
            // 调谐失败要重试：I2C 偶发抖动（供电/接触）会让一次调谐打空，
            // 而用户看到的只是「频率数字变了、声音没变」，根本无从判断。
            var ok = false
            rtl.setI2cRepeater(true)
            try {
                for (attempt in 0 until 2) {
                    ok = t.setParams(tuneTo)
                    if (ok) break
                    tuneFailures++
                    SdrLog.w("RTL", "调谐 ${tuneTo / 1000} kHz 失败，重试 ${attempt + 1}/2")
                    try { Thread.sleep(20) } catch (_: InterruptedException) {}
                }
            } finally {
                // repeater 必须复位：留在 on 状态会让后续块寄存器读写全部错乱
                rtl.setI2cRepeater(false)
            }
            if (!ok) SdrLog.e("RTL", "调谐 ${tuneTo / 1000} kHz 最终失败")
        } catch (e: Exception) {
            tuneFailures++
            SdrLog.e("RTL", "调谐异常: ${e.message?.take(40)}")
        }
    }

    /** 设 LNA 增益（tenths dB，FC0013 内部取最近档）；返回是否被硬件接受 */
    fun setLnaGain(tenthsDb: Int): Boolean {
        if (closed) return false
        return try {
            rtl.setI2cRepeater(true)
            val ok = try {
                tuner?.setLnaGain(tenthsDb) ?: false
            } finally {
                rtl.setI2cRepeater(false)
            }
            if (!ok) {
                // 以前失败是完全静默的：用户调了增益却不知道有没有生效
                hwSettingFailures++
                SdrLog.w("RTL", "LNA 增益设置失败 ($tenthsDb)")
            }
            ok
        } catch (e: Exception) {
            hwSettingFailures++
            SdrLog.e("RTL", "LNA 增益异常: ${e.message?.take(40)}")
            false
        }
    }

    /** 手动增益模式开关（true=手动按 setLnaGain 的值）；返回是否被硬件接受 */
    fun setGainMode(manual: Boolean): Boolean {
        if (closed) return false
        return try {
            rtl.setI2cRepeater(true)
            val ok = try {
                tuner?.setGainMode(manual) ?: false
            } finally {
                rtl.setI2cRepeater(false)
            }
            if (!ok) {
                hwSettingFailures++
                SdrLog.w("RTL", "增益模式设置失败 ($manual)")
            }
            ok
        } catch (e: Exception) {
            hwSettingFailures++
            SdrLog.e("RTL", "增益模式异常: ${e.message?.take(40)}")
            false
        }
    }

    /** RTL2832 内部 AGC 开关 */
    fun setRtlAgc(on: Boolean): Boolean {
        if (closed) return false
        val ok = rtl.setRtlAgc(on)
        SdrLog.i("RTL", "内部 AGC ${if (on) "on" else "off"}: $ok")
        return ok
    }

    // ---------- 频率校准（PPM）----------
    /**
     * 晶振误差补偿。FC0013 这类廉价棒子的 28.8MHz 晶振常偏几十 ppm，
     * 直接后果是「明明调到 98.1MHz 却听不清」——广播有 200kHz 带宽兜着，
     * 窄带 NFM/SSB 就完全跑掉了。
     *
     * 改 xtal 会同时影响采样率与 PLL 分频，所以必须重算采样率并重新调谐。
     * 用 PPM 而不是直接写 Hz：ppm 与中心频率无关，一次标定全频段通用。
     */
    @Volatile var ppm: Int = 0
        private set

    /** 设置 PPM 校准（-200..200），返回是否生效 */
    fun setPpm(value: Int): Boolean {
        if (closed) return false
        val v = value.coerceIn(-200, 200)
        val oldXtal = rtl.rtlXtal
        val oldPpm = ppm
        return try {
            rtl.setI2cRepeater(false)
            rtl.setXtalHz(Rtl2832.DEFAULT_XTAL + (Rtl2832.DEFAULT_XTAL * v / 1_000_000L))
            // xtal 变了 → 采样率寄存器过时，必须重算；重算会软复位，需再复位端点
            val real = rtl.setSampleRate(sampleRate.toLong())
            if (real <= 0L) {
                // 采样率没写进去，但 xtal 已经改了——状态不一致，必须回滚，
                // 否则设备会一直用一个「算错的晶振」而毫无察觉。
                rtl.setXtalHz(oldXtal)
                rtl.setSampleRate(sampleRate.toLong())
                rtl.resetBuffer()
                SdrLog.e("RTL", "PPM 校准失败：采样率寄存器写入未成功，已回滚晶振")
                ppm = oldPpm
                return false
            }
            rtl.resetBuffer()
            retune()
            ppm = v
            SdrLog.i("RTL", "PPM=$v 校准后采样率=$real")
            true
        } catch (e: Exception) {
            // 异常路径同样回滚：xtal 已改而采样率可能没改，状态不一致
            try {
                rtl.setXtalHz(oldXtal)
                rtl.setSampleRate(sampleRate.toLong())
                rtl.resetBuffer()
            } catch (_: Exception) {}
            ppm = oldPpm
            SdrLog.e("RTL", "PPM 校准失败: ${e.message?.take(40)}，已回滚")
            false
        }
    }

    /** 当前生效的晶振频率（Hz），调试用 */
    val xtalHz: Long get() = rtl.rtlXtal

    // ---------- 直接采样模式（HF 短波）----------
    @Volatile var directSampling = 0
        private set

    /** offset tuning：tuner 调到 f±offs、IF=offs，躲开 DC 的 1/f 噪声（librtlsdr 同逻辑） */
    @Volatile private var offsetTuning = false
    private var offsFreq = 0L

    fun setOffsetTuning(on: Boolean): Boolean {
        if (closed || directSampling != 0) return false
        offsetTuning = on
        offsFreq = if (on) (sampleRate.toLong() / 2) * 170 / 100 else 0L
        try {
            rtl.setI2cRepeater(false)
            rtl.setIfFreq(offsFreq)
            retune()  // 重新调谐（含 offset 偏移）
            SdrLog.i("SRC", "offset tuning ${if (on) "on offs=${offsFreq / 1000}kHz" else "off"}")
            return true
        } catch (e: Exception) {
            SdrLog.e("SRC", "offset tuning 失败: ${e.message}")
            return false
        }
    }

    /** 开/关直接采样（0=关 1=I-ADC 2=Q-ADC）。开时调谐改走 IF 路径。 */
    fun setDirectSampling(mode: Int): Boolean {
        if (closed) return false
        return try {
            val ok = rtl.setDirectSampling(mode, tunerExit = null, tunerInit = {
                // 恢复 tuner：重新初始化 FC0013
                rtl.setI2cRepeater(true)
                try {
                    val initOk = tuner?.init() ?: false
                    if (!initOk) {
                        hwSettingFailures++
                        SdrLog.e("RTL", "恢复 tuner 失败: ${tuner?.initDetail ?: "无 tuner"}")
                    }
                } finally {
                    rtl.setI2cRepeater(false)
                }
            })
            if (ok) {
                directSampling = mode
                if (mode != 0) {
                    // 直接采样下调谐 = 设 IF 频率
                    rtl.setIfFreq(centerFreq)
                    SdrLog.i("SRC", "直接采样 $mode, IF=${centerFreq / 1000} kHz")
                } else {
                    // 恢复 tuner 调谐
                    retune()
                }
            }
            ok
        } catch (e: Exception) {
            SdrLog.e("SRC", "直接采样切换失败: ${e.message}")
            false
        }
    }

    override fun close() {
        closed = true
        try { rtl.deinit() } catch (_: Exception) {}
        try { conn.releaseInterface(device.getInterface(0)) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
