package com.pocketsdr.app.source.rtl

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.pocketsdr.app.SdrLog

/** RTL tuner 类型（独立顶级枚举，避免 companion 嵌套解析问题） */
enum class RtlTuner { UNKNOWN, FC0013, R820T }

/**
 * RTL2832U 基带控制器（Kotlin 移植自 librtlsdr，Steve Markgraf 等，GPL-2.0）。
 *
 * 所有寄存器操作经 USB vendor control transfer：
 *  - 块寄存器：value=addr, index=block<<8（读）/ block<<8|0x10（写）
 *  - demod 页寄存器：value=(addr<<8)|0x20, index=page（读）/ 0x10|page（写）
 *
 * @param bulkInEp 数据流端点（接口 0 的 bulk-in，一般 0x81）
 */
class Rtl2832(private val conn: UsbDeviceConnection, private val bulkInEp: UsbEndpoint) {

    companion object {
        // USB 控制传输
        private const val CTRL_IN = 0xC0   // vendor | endpoint_in
        private const val CTRL_OUT = 0x40 // vendor | endpoint_out
        private const val CTRL_TIMEOUT_MS = 300

        // USB 块寄存器（block=USBB=1）
        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_MAXPKT = 0x2158
        private const val USB_EPA_CTL = 0x2148

        // 系统块（SYSB=2）
        private const val DEMOD_CTL = 0x3000
        private const val DEMOD_CTL_1 = 0x300b
        private const val GPO = 0x3001
        private const val GPD = 0x3004
        private const val GPOE = 0x3003

        // 块编号
        private const val DEMODB = 0
        private const val USBB = 1
        private const val SYSB = 2
        private const val IICB = 6

        const val DEFAULT_XTAL = 28_800_000L

        // 默认 FIR 系数（librtlsdr 的 DAB/FM 默认值）
        private val FIR_DEFAULT = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,   // int8
            101, 156, 215, 273, 327, 372, 404, 421  // int12
        )

        // FC0013 常量
        const val FC0013_I2C_ADDR = 0xC6
        const val FC0013_CHECK_ADDR = 0x00
        const val FC0013_CHECK_VAL = 0xA3

        // R820T 常量
        const val R820T_I2C_ADDR = 0x3A
        const val R820T_CHECK_ADDR = 0x00
        const val R820T_CHECK_VAL = 0x69
    }

    var rtlXtal = DEFAULT_XTAL
        private set

    /**
     * 设置晶振频率（ppm 校准用）。只改数值，采样率/PLL 由调用方重算。
     * 值域限制在 ±200ppm，防止滑条抖动或非法输入把 PLL 推到无意义的分频。
     */
    fun setXtalHz(hz: Long) {
        val lo = DEFAULT_XTAL - DEFAULT_XTAL / 5000   // -200ppm
        val hi = DEFAULT_XTAL + DEFAULT_XTAL / 5000   // +200ppm
        rtlXtal = hz.coerceIn(lo, hi)
    }

    /** demod 写后固定回读一次（librtlsdr 行为，保证时序） */
    private var lastError = 0

    // ---------- 底层寄存器访问 ----------
    private fun readArray(block: Int, addr: Int, data: ByteArray, len: Int): Int {
        val index = block shl 8
        return conn.controlTransfer(CTRL_IN, 0, addr, index, data, len, CTRL_TIMEOUT_MS)
    }

    private fun writeArray(block: Int, addr: Int, data: ByteArray, len: Int): Int {
        val index = (block shl 8) or 0x10
        return conn.controlTransfer(CTRL_OUT, 0, addr, index, data, len, CTRL_TIMEOUT_MS)
    }

    /** 读块寄存器（1 或 2 字节），返回无符号 16 位值 */
    fun readReg(block: Int, addr: Int, len: Int): Int {
        val data = ByteArray(2)
        val r = conn.controlTransfer(CTRL_IN, 0, addr, block shl 8, data, len, CTRL_TIMEOUT_MS)
        if (r < 0) { lastError = r; return -1 }
        return ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    }

    /** 写块寄存器（1 或 2 字节） */
    fun writeReg(block: Int, addr: Int, value: Int, len: Int): Int {
        val data = ByteArray(2)
        if (len == 1) {
            data[0] = (value and 0xFF).toByte()
        } else {
            data[0] = ((value shr 8) and 0xFF).toByte()
            data[1] = (value and 0xFF).toByte()
        }
        val index = (block shl 8) or 0x10
        return conn.controlTransfer(CTRL_OUT, 0, addr, index, data, len, CTRL_TIMEOUT_MS)
    }

    /** demod 页寄存器读 */
    fun demodReadReg(page: Int, addr: Int, len: Int): Int {
        val data = ByteArray(2)
        val a = (addr shl 8) or 0x20
        val r = conn.controlTransfer(CTRL_IN, 0, a, page, data, len, CTRL_TIMEOUT_MS)
        if (r < 0) { lastError = r; return -1 }
        return ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    }

    /** demod 页寄存器写（写后回读同步） */
    fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int): Boolean {
        val data = ByteArray(2)
        if (len == 1) {
            data[0] = (value and 0xFF).toByte()
        } else {
            data[0] = ((value shr 8) and 0xFF).toByte()
            data[1] = (value and 0xFF).toByte()
        }
        val a = (addr shl 8) or 0x20
        val index = 0x10 or page
        val r = conn.controlTransfer(CTRL_OUT, 0, a, index, data, len, CTRL_TIMEOUT_MS)
        demodReadReg(0x0A, 0x01, 1) // librtlsdr：写后固定回读
        return r == len
    }

    // ---------- I2C ----------
    private var i2cRepeaterOn = -1

    fun setI2cRepeater(on: Boolean) {
        val want = if (on) 1 else 0
        if (want == i2cRepeaterOn) return
        i2cRepeaterOn = want
        demodWriteReg(1, 0x01, if (on) 0x18 else 0x10, 1)
    }

    fun i2cWrite(addr: Int, data: ByteArray): Int = writeArray(IICB, addr, data, data.size)

    fun i2cRead(addr: Int, len: Int): ByteArray? {
        val buf = ByteArray(len)
        val r = readArray(IICB, addr, buf, len)
        return if (r >= 0) buf else null
    }

    // ---------- I2C 重试 ----------
    // 廉价 RTL 棒 + 手机 OTG 的组合上，USB 控制传输偶发失败是常态（供电抖动、
    // 线接触不良、内核 USB 栈繁忙）。librtlsdr 在 rtlsdr_i2c_write_bin/read 里
    // 也做了重试。没有重试的话，一次瞬时抖动就会让整个连接以
    // 「FC0013 初始化失败」告终——而硬件其实好好的，只是那一次没传过去。
    private val I2C_RETRIES = 3

    /** tuner 探测轮数：首次插入时第一轮 I2C 常打空，多试几轮能救回假故障 */
    private val PROBE_ROUNDS = 3

    /** 累计 I2C 重试次数：自检报告里能看到，就能判断 USB 链路稳不稳 */
    @Volatile var i2cRetryCount = 0
        private set

    /**
     * demod（基带）寄存器写失败累计次数。
     *
     * I2C 写的是 tuner，demod 写的是 RTL2832 自身——两条不同的通路。
     * demod 写失败意味着基带配置没生效（采样率、IF 频率、软复位…），
     * 现象是频谱看起来正常但频率/带宽全错，非常难自查。
     */
    @Volatile var demodWriteFailures = 0
        private set

    private fun i2cRetry(what: String, attempt: Int) {
        i2cRetryCount++
        SdrLog.i("RTL", "I2C $what 失败，重试 ${attempt + 1}/$I2C_RETRIES")
        try { Thread.sleep(5) } catch (_: InterruptedException) {}
    }

    fun i2cWriteReg(i2cAddr: Int, reg: Int, value: Int): Boolean {
        val d = byteArrayOf(reg.toByte(), value.toByte())
        for (attempt in 0 until I2C_RETRIES) {
            if (i2cWrite(i2cAddr, d) >= 0) return true
            i2cRetry("写 0x%02X".format(reg), attempt)
        }
        return false
    }

    fun i2cReadReg(i2cAddr: Int, reg: Int): Int {
        // 注意：原来这里把 i2cWrite 的返回值丢掉了——写失败也照样去读，
        // 读回来的是上一次的残留值。探测 tuner 时这会误判：
        // 读到陈旧的 0xA3 就以为「是 FC0013」，其实这一轮通信根本没成功。
        for (attempt in 0 until I2C_RETRIES) {
            if (i2cWrite(i2cAddr, byteArrayOf(reg.toByte())) < 0) {
                i2cRetry("写 0x%02X".format(reg), attempt)
                continue
            }
            val b = i2cRead(i2cAddr, 1)
            if (b != null) return b[0].toInt() and 0xFF
            i2cRetry("读 0x%02X".format(reg), attempt)
        }
        return -1
    }

    // ---------- GPIO ----------
    private fun setGpioBit(gpio: Int, on: Boolean) {
        val bit = 1 shl gpio
        var r = readReg(SYSB, GPO, 1)
        if (r < 0) return
        r = if (on) r or bit else r and bit.inv()
        writeReg(SYSB, GPO, r, 1)
    }

    private fun setGpioOutput(gpio: Int) {
        val bit = 1 shl gpio
        var r = readReg(SYSB, GPD, 1)
        writeReg(SYSB, GPD, r and bit.inv(), 1)
        r = readReg(SYSB, GPOE, 1)
        writeReg(SYSB, GPOE, r or bit, 1)
    }

    // ---------- 初始化 ----------
    fun initBaseband(): Boolean {
        // USB 初始化
        if (writeReg(USBB, USB_SYSCTL, 0x09, 1) < 0) return false
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        // demod 上电
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)
        // 软复位
        demodWriteReg(1, 0x01, 0x14, 1)
        demodWriteReg(1, 0x01, 0x10, 1)
        // 关频谱反转与邻道抑制
        demodWriteReg(1, 0x15, 0x00, 1)
        // 清 DDC/IF 频率寄存器
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1)
        setFir()
        // SDR 模式，关 DAGC
        demodWriteReg(0, 0x19, 0x05, 1)
        // FSM 状态保持
        demodWriteReg(1, 0x93, 0xf0, 1)
        demodWriteReg(1, 0x94, 0x0f, 1)
        // 关 AGC
        demodWriteReg(1, 0x11, 0x00, 1)
        demodWriteReg(1, 0x04, 0x00, 1)
        // 关 PID 过滤
        demodWriteReg(0, 0x61, 0x60, 1)
        // 默认 ADC I/Q 数据通路
        demodWriteReg(0, 0x06, 0x80, 1)
        // Zero-IF 模式 + DC 消除 + IQ 补偿
        demodWriteReg(1, 0xb1, 0x1b, 1)
        // 关 4.096MHz 时钟输出
        demodWriteReg(0, 0x0d, 0x83, 1)
        return true
    }

    private fun setFir(): Boolean {
        val fir = ByteArray(20)
        // int8[8]
        for (i in 0 until 8) {
            val v = FIR_DEFAULT[i]
            if (v < -128 || v > 127) return false
            fir[i] = v.toByte()
        }
        // int12[8] → 12bit 紧凑格式
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]
            val v1 = FIR_DEFAULT[8 + i + 1]
            if (v0 < -2048 || v0 > 2047 || v1 < -2048 || v1 > 2047) return false
            val base = 8 + i * 3 / 2
            fir[base] = (v0 shr 4).toByte()
            fir[base + 1] = (((v0 shl 4) or ((v1 shr 8) and 0x0F)) and 0xFF).toByte()
            fir[base + 2] = (v1 and 0xFF).toByte()
            i += 2
        }
        for (k in fir.indices) {
            if (!demodWriteReg(1, 0x1c + k, fir[k].toInt(), 1)) return false
        }
        return true
    }

    /** 探测 tuner（只探测 FC0013 与 R820T，覆盖用户硬件与最常见棒子） */
    /**
     * 探测 tuner 型号。
     *
     * 为什么要多轮：首次插入的设备最容易探测失败——供电刚稳、OTG 刚协商完，
     * 第一轮 I2C 传输经常打空。这时候直接判 UNKNOWN，用户看到的是
     * 「未识别的 tuner」，会以为棒子坏了，而实际上只是那一瞬间没传过去。
     * 多试几轮（每次之间让 I2C 状态复位并稍等）能救回绝大多数这种假故障。
     *
     * 探测结果与失败原因都记在 [probeDetail] 里，供上层给出可操作的提示。
     */
    fun probeTuner(): RtlTuner {
        var lastFc = -1
        var lastR820 = -1
        for (round in 0 until PROBE_ROUNDS) {
            if (round > 0) {
                // 复位 I2C 状态并稍等，让上一轮可能残留的状态清干净
                setI2cRepeater(false)
                setI2cRepeater(true)
                try { Thread.sleep(30) } catch (_: InterruptedException) {}
            }
            setI2cRepeater(true)
            lastFc = i2cReadReg(FC0013_I2C_ADDR, FC0013_CHECK_ADDR)
            if (lastFc == FC0013_CHECK_VAL) {
                setI2cRepeater(false)
                probeDetail = "FC0013（第 ${round + 1} 轮探测命中）"
                return RtlTuner.FC0013
            }
            lastR820 = i2cReadReg(R820T_I2C_ADDR, R820T_CHECK_ADDR)
            if (lastR820 == R820T_CHECK_VAL) {
                setI2cRepeater(false)
                probeDetail = "R820T（第 ${round + 1} 轮探测命中）"
                return RtlTuner.R820T
            }
            setI2cRepeater(false)
            SdrLog.w("RTL", "tuner 探测第 ${round + 1} 轮未命中 (FC=0x%02X R820=0x%02X)".format(lastFc, lastR820))
        }
        // 区分两种失败：链路完全不通 vs 通了但型号不认识
        probeDetail = if (lastFc < 0 || lastR820 < 0) {
            "I2C 通信无响应（$PROBE_ROUNDS 轮都没读到数据）——多半是供电不足或 OTG 线接触不良，换根线/换个充电口试试"
        } else {
            "读到了数据但型号不认识（FC0013 标识=0x%02X，R820T 标识=0x%02X）——本版只适配 FC0013".format(lastFc, lastR820)
        }
        return RtlTuner.UNKNOWN
    }

    /** 探测过程的人类可读说明（成功时是命中信息，失败时是原因） */
    @Volatile var probeDetail: String = ""
        private set

    // ---------- 直接采样模式（HF 短波，librtlsdr rtlsdr_set_direct_sampling 移植）----------
    // on: 0=关闭(tuner 正常), 1=I-ADC 直接采样, 2=Q-ADC 直接采样
    /**
     * 设置直接采样模式。on=1/2 时：关 Zero-IF、只开对应 ADC 通路、
     * 调谐由 IF 频率寄存器实现（tuner 不参与）。on=0 恢复正常 tuner 模式。
     */
    fun setDirectSampling(on: Int, tunerExit: (() -> Unit)?, tunerInit: (() -> Unit)?): Boolean {
        setI2cRepeater(false)
        var r = true
        if (on == 1 || on == 2) {
            tunerExit?.invoke()  // tuner 退出（FC0013 无 standby，简单跳过）
        }
        if (on != 0) {
            // 关 Zero-IF
            r = r && demodWriteReg(1, 0xb1, 0x1a, 1)
            // 关频谱反转
            r = r && demodWriteReg(1, 0x15, 0x00, 1)
            // 只开 I-ADC 输入
            r = r && demodWriteReg(0, 0x08, 0x4d, 1)
            // I/Q ADC 交换（选输入口）
            r = r && demodWriteReg(0, 0x06, if (on == 2) 0x90 else 0x80, 1)
            SdrLog.i("RTL", "直接采样模式 on=$on")
        } else {
            // 恢复 tuner 模式
            tunerInit?.invoke()
            // IF=0（Zero-IF）
            setIfFreq(0)
            // 开 I+Q ADC
            r = r && demodWriteReg(0, 0x08, 0xcd, 1)
            // 开 Zero-IF
            r = r && demodWriteReg(1, 0xb1, 0x1b, 1)
            // 默认 ADC 数据通路
            r = r && demodWriteReg(0, 0x06, 0x80, 1)
            SdrLog.i("RTL", "恢复 tuner 模式")
        }
        return r
    }

    /** 设置 IF 频率（直接采样下调谐 = IF 频率，Zero-IF 模式 = 0） */
    fun setIfFreq(freq: Long): Boolean {
        setI2cRepeater(false)
        // if_freq = -(freq * 2^22 / xtal)
        val ifFreq = (-freq * (1L shl 22) / rtlXtal).toInt()
        val hi = (ifFreq shr 16) and 0x3F
        val mid = (ifFreq shr 8) and 0xFF
        val lo = ifFreq and 0xFF
        var r = demodWriteReg(1, 0x19, hi, 1)
        r = r and demodWriteReg(1, 0x1a, mid, 1)
        r = r and demodWriteReg(1, 0x1b, lo, 1)
        return r
    }

    /**
     * 设置采样率，返回实际速率 Hz（如请求 1.024M 得到精确 1024000）。
     *
     * 返回 0 表示设置失败：demod 寄存器写不进去时，采样率仍是旧值，
     * 而 App 会以为设置成功了——结果频谱显示的频率和带宽都是错的，
     * 却没有任何提示。这在 USB 供电不稳的设备上真会发生。
     */
    fun setSampleRate(sampRate: Long): Long {
        setI2cRepeater(false)
        // rsamp_ratio = (xtal * 2^22) / rate，低 26 位对齐 4
        var rsampRatio = ((rtlXtal shl 22) / sampRate).toInt() and 0x0FFFFFFC
        val realRatio = rsampRatio or ((rsampRatio and 0x08000000) shl 1)
        val realRate = (rtlXtal.toDouble() * (1L shl 22)) / realRatio.toDouble()
        val hi = (rsampRatio shr 16) and 0xFFFF
        val lo = rsampRatio and 0xFFFF
        var ok = demodWriteReg(1, 0x9f, hi, 2)
        ok = ok and demodWriteReg(1, 0xa1, lo, 2)
        // 软复位生效
        ok = ok and demodWriteReg(1, 0x01, 0x14, 1)
        ok = ok and demodWriteReg(1, 0x01, 0x10, 1)
        if (!ok) {
            demodWriteFailures++
            SdrLog.e("RTL", "设置采样率失败（demod 寄存器写入未成功）")
            return 0L
        }
        return realRate.toLong()
    }

    /** RTL2832 内部 AGC（testmode/AGC 寄存器页 0）：on=0x25 off=0x05 */
    fun setRtlAgc(on: Boolean): Boolean {
        setI2cRepeater(false)
        return demodWriteReg(0, 0x19, if (on) 0x25 else 0x05, 1)
    }

    /** 复位采样流端点（读流开始前调用） */
    fun resetBuffer() {
        setI2cRepeater(false)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x0000, 2)
    }

    /** 同步 bulk 读（数据流端点），返回读到的字节数 */
    fun bulkRead(buf: ByteArray, len: Int, timeoutMs: Int): Int {
        return conn.bulkTransfer(bulkInEp, buf, len, timeoutMs)
    }

    fun deinit() {
        setI2cRepeater(false)
        writeReg(SYSB, DEMOD_CTL, 0x20, 1)
    }
}
