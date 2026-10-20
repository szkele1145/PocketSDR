package com.pocketsdr.app.source.rtl

import kotlin.math.abs

/**
 * Fitipower FC0013 tuner 驱动（Kotlin 移植自 librtlsdr tuner_fc0013.c，
 * Hans-Frieder Vogt / Steve Markgraf，GPL-2.0）。
 */
class Fc0013(private val rtl: Rtl2832) {

    companion object {
        // LNA 增益表（tenths dB, reg 值）— 与 librtlsdr 相同
        private val LNA_GAINS = intArrayOf(
            -99, 0x02, -73, 0x03, -65, 0x05, -63, 0x04, -63, 0x00,
            -60, 0x07, -58, 0x01, -54, 0x06, 58, 0x0f, 61, 0x0e,
            63, 0x0d, 65, 0x0c, 67, 0x0b, 68, 0x0a, 70, 0x09, 71, 0x08,
            179, 0x17, 181, 0x16, 182, 0x15, 184, 0x14, 186, 0x13,
            188, 0x12, 191, 0x11, 197, 0x10
        )
        private const val GAIN_PAIRS = 24
    }

    private fun writeReg(reg: Int, value: Int): Boolean =
        rtl.i2cWriteReg(Rtl2832.FC0013_I2C_ADDR, reg, value)

    private fun readReg(reg: Int): Int =
        rtl.i2cReadReg(Rtl2832.FC0013_I2C_ADDR, reg)

    /**
     * 初始化失败时的可读原因（成功时为空串）。
     *
     * 以前 init() 只返回一个布尔值，失败时用户只看到「FC0013 初始化失败」，
     * 完全不知道是哪一步没成。现在把失败的寄存器地址带出来：
     * 0x00 写不进去 = I2C 链路没通（换线/供电）；其它寄存器 = 芯片状态异常。
     */
    @Volatile var initDetail: String = ""
        private set

    fun init(): Boolean {
        val reg = intArrayOf(
            0x00, 0x09, 0x16, 0x00, 0x00, 0x17, 0x02, 0x0a,
            0xff, 0x6e, 0xb8, 0x82, 0xfc, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x50, 0x01
        )
        reg[0x07] = reg[0x07] or 0x20
        reg[0x0c] = reg[0x0c] or 0x02
        for (i in 1 until reg.size) {
            if (!writeReg(i, reg[i])) {
                initDetail = if (i == 1) {
                    "写寄存器 0x01 失败——I2C 链路没通（供电不足/OTG 线接触不良，或芯片没上电）"
                } else {
                    "写寄存器 0x%02X 失败（值 0x%02X）——芯片未正常响应".format(i, reg[i])
                }
                return false
            }
        }
        initDetail = ""
        return true
    }

    /** RC 校准附加 */
    fun rcCalAdd(rcVal: Int): Boolean {
        if (!writeReg(0x10, 0x00)) return false
        val rcCal = readReg(0x10)
        if (rcCal < 0) return false
        val v = (rcCal and 0x0F) + rcVal
        if (!writeReg(0x0d, 0x11)) return false
        return if (v > 15) writeReg(0x10, 0x0f)
        else if (v < 0) writeReg(0x10, 0x00)
        else writeReg(0x10, v)
    }

    fun rcCalReset(): Boolean {
        if (!writeReg(0x0d, 0x01)) return false
        return writeReg(0x10, 0x00)
    }

    /** VHF track 设置（按频率段） */
    private fun setVhfTrack(freq: Long): Boolean {
        val tmp = readReg(0x1d)
        if (tmp < 0) return false
        val base = tmp and 0xE3
        val v = when {
            freq <= 177_500_000L -> base or 0x1C
            freq <= 184_500_000L -> base or 0x18
            freq <= 191_500_000L -> base or 0x14
            freq <= 198_500_000L -> base or 0x10
            freq <= 205_500_000L -> base or 0x0C
            freq <= 219_500_000L -> base or 0x08
            freq < 300_000_000L -> base or 0x04
            else -> base or 0x1C
        }
        return writeReg(0x1d, v)
    }

    /**
     * 设置频率与带宽。fc0013_set_params 完整移植。
     * xtal = tuner 时钟（RTL 桥接默认 28.8MHz / 2 = 14.4MHz 参考时钟）。
     */
    fun setParams(freq: Long, bandwidth: Long = 6_000_000L): Boolean {
        val xtalHalf = (rtl.rtlXtal / 2).toLong()
        if (!setVhfTrack(freq)) return false

        // VHF/UHF/GPS 滤波通路
        if (freq < 300_000_000L) {
            var t = readReg(0x07)
            if (t < 0) return false
            if (!writeReg(0x07, t or 0x10)) return false
            t = readReg(0x14)
            if (t < 0) return false
            if (!writeReg(0x14, t and 0x1F)) return false
        } else if (freq <= 862_000_000L) {
            var t = readReg(0x07)
            if (t < 0) return false
            if (!writeReg(0x07, t and 0xEF)) return false
            t = readReg(0x14)
            if (t < 0) return false
            if (!writeReg(0x14, (t and 0x1F) or 0x40)) return false
        } else {
            var t = readReg(0x07)
            if (t < 0) return false
            if (!writeReg(0x07, t and 0xEF)) return false
            t = readReg(0x14)
            if (t < 0) return false
            if (!writeReg(0x14, (t and 0x1F) or 0x20)) return false
        }

        // 分频比选择
        var multi = 96; var reg5 = 0x82; var reg6 = 0x00
        if (freq < 37_084_000L) { multi = 96; reg5 = 0x82; reg6 = 0x00 }
        else if (freq < 55_625_000L) { multi = 64; reg5 = 0x02; reg6 = 0x02 }
        else if (freq < 74_167_000L) { multi = 48; reg5 = 0x42; reg6 = 0x00 }
        else if (freq < 111_250_000L) { multi = 32; reg5 = 0x82; reg6 = 0x02 }
        else if (freq < 148_334_000L) { multi = 24; reg5 = 0x22; reg6 = 0x00 }
        else if (freq < 222_500_000L) { multi = 16; reg5 = 0x42; reg6 = 0x02 }
        else if (freq < 296_667_000L) { multi = 12; reg5 = 0x12; reg6 = 0x00 }
        else if (freq < 445_000_000L) { multi = 8; reg5 = 0x22; reg6 = 0x02 }
        else if (freq < 593_334_000L) { multi = 6; reg5 = 0x0a; reg6 = 0x00 }
        else if (freq < 950_000_000L) { multi = 4; reg5 = 0x12; reg6 = 0x02 }
        else { multi = 2; reg5 = 0x0a; reg6 = 0x02 }

        val fVco = freq * multi

        if (fVco >= 3_060_000_000L) {
            reg6 = reg6 or 0x08
        }

        // XDIV
        var xdiv = (fVco / xtalHalf).toInt()
        if (fVco - xdiv * xtalHalf >= xtalHalf / 2) xdiv++

        var pm = xdiv / 8
        var am = xdiv - 8 * pm
        if (am < 2) { am += 8; pm-- }

        val reg1: Int; val reg2: Int
        if (pm > 31) {
            reg1 = am + 8 * (pm - 31)
            reg2 = 31
        } else {
            reg1 = am
            reg2 = pm
        }
        if (reg1 > 15 || reg2 < 0x0B) return false  // 无效 PLL 组合

        reg6 = reg6 or 0x20  // fix clock out

        // XIN（Delta Sigma 小数部分）
        var xin = (((fVco - (fVco / xtalHalf) * xtalHalf) / 1000) shl 15) / (xtalHalf / 1000)
        if (xin >= 16384) xin += 32768
        val reg3 = xin shr 8
        val reg4 = xin and 0xFF

        // 带宽位
        reg6 = reg6 and 0x3F
        when (bandwidth) {
            6_000_000L -> reg6 = reg6 or 0x80
            7_000_000L -> reg6 = reg6 or 0x40
            else -> {}
        }

        reg5 = reg5 or 0x07  // modified for Realtek demod

        // 写 PLL 寄存器
        if (!writeReg(1, reg1)) return false
        if (!writeReg(2, reg2)) return false
        if (!writeReg(3, reg3.toInt())) return false
        if (!writeReg(4, reg4.toInt())) return false
        if (!writeReg(5, reg5)) return false
        if (!writeReg(6, reg6)) return false

        // multi==64 时开 LNA 电源
        val tmp = readReg(0x11)
        if (tmp < 0) return false
        val r = if (multi == 64) tmp or 0x04 else tmp and 0xFB
        if (!writeReg(0x11, r)) return false

        // VCO 校准
        if (!writeReg(0x0e, 0x80)) return false
        if (!writeReg(0x0e, 0x00)) return false
        if (!writeReg(0x0e, 0x00)) return false
        val vco = readReg(0x0e)
        if (vco < 0) return false

        // VCO 选择修正
        val vcoSel = (vco and 0x3F)
        if (fVco >= 3_060_000_000L) {
            if (vcoSel > 0x3C) {
                if (!writeReg(0x06, reg6 and 0x08.inv())) return false
                if (!writeReg(0x0e, 0x80)) return false
                if (!writeReg(0x0e, 0x00)) return false
            }
        } else {
            if (vcoSel < 0x02) {
                if (!writeReg(0x06, reg6 or 0x08)) return false
                if (!writeReg(0x0e, 0x80)) return false
                if (!writeReg(0x0e, 0x00)) return false
            }
        }
        return true
    }

    /** 手动/自动增益模式 */
    fun setGainMode(manual: Boolean): Boolean {
        val t = readReg(0x0d)
        if (t < 0) return false
        val v = if (manual) t or 0x08 else t and 0x08.inv()
        if (!writeReg(0x0d, v)) return false
        // 固定 IF 增益
        return writeReg(0x13, 0x0a)
    }

    /** 设 LNA 增益（tenths dB） */
    fun setLnaGain(gain: Int): Boolean {
        var t = readReg(0x14)
        if (t < 0) return false
        t = t and 0xE0
        for (i in 0 until GAIN_PAIRS) {
            if (LNA_GAINS[i * 2] >= gain || i + 1 == GAIN_PAIRS) {
                t = t or LNA_GAINS[i * 2 + 1]
                break
            }
        }
        return writeReg(0x14, t)
    }
}
