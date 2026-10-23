package com.pocketsdr.app.source.rtl

/**
 * FC0013 的 PLL / 分频寄存器计算。
 *
 * ## 为什么单独抽出来
 *
 * 这套算式是全项目**最不容易验证、出错又最致命**的一段：算错了不会崩，
 * 只会表现为「能连上、频谱里啥也没有」或者「收到的台频率偏了」。而它
 * 依赖 [Rtl2832]（要真实 USB 设备），在没有硬件时一行都跑不到。
 *
 * 抽成不依赖 Android 的纯函数之后，桌面端可以拿 librtlsdr 的算式独立
 * 实现一遍做交叉验证（见 `tools/simcheck/Fc0013PllCheck.kt`）。
 *
 * ## 算式来源
 *
 * 逐行对应 librtlsdr 的 `src/tuner_fc0013.c` → `fc0013_set_params()`：
 *  - 按频段选分频比 multi 和 reg5/reg6 初值
 *  - f_vco = freq × multi，≥3.06 GHz 时置 reg6 bit3 并记 vco_select
 *  - xdiv = round(f_vco / (xtal/2))
 *  - pm = xdiv/8，am = xdiv%8；am<2 时借位（am+=8，pm-=1）
 *  - pm>31 时把溢出部分加回 am，pm 封顶 31
 *  - 合法组合要求 reg1 ≤ 15 且 reg2 ≥ 0x0b，否则该频率调不了
 *  - XIN = 小数部分，左移 15 位再除以 (xtal/2/1000)，≥16384 时 +32768
 *  - 带宽位：6 MHz → reg6 bit7，7 MHz → reg6 bit6，8 MHz → 都不置
 *  - reg5 低 3 位恒置（Realtek 解调器要求）
 */
object Fc0013Pll {

    /** tuner 参考时钟默认 28.8 MHz（RTL2832U 桥接晶振） */
    const val DEFAULT_XTAL = 28_800_000L

    /** 算出来要写进 tuner 的寄存器值 */
    data class Regs(
        /** 分频比（96/64/48/32/24/16/12/8/6/4/2），64 时要额外开 LNA 电源 */
        val multi: Int,
        val r1: Int,
        val r2: Int,
        val r3: Int,
        val r4: Int,
        val r5: Int,
        val r6: Int,
        /** true = f_vco ≥ 3.06 GHz，需要走 VCO 高段校准 */
        val vcoSelect: Boolean
    )

    /**
     * 计算某频率对应的寄存器值。
     *
     * @return null 表示这个频率找不到合法 PLL 组合（librtlsdr 同样返回 -1）
     */
    fun compute(freq: Long, xtal: Long = DEFAULT_XTAL, bandwidth: Long = 6_000_000L): Regs? {
        val xtalHalf = xtal / 2
        if (xtalHalf <= 0 || freq <= 0) return null

        // 按频段选分频比
        val multi: Int
        var reg5: Int
        var reg6: Int
        when {
            freq < 37_084_000L -> { multi = 96; reg5 = 0x82; reg6 = 0x00 }
            freq < 55_625_000L -> { multi = 64; reg5 = 0x02; reg6 = 0x02 }
            freq < 74_167_000L -> { multi = 48; reg5 = 0x42; reg6 = 0x00 }
            freq < 111_250_000L -> { multi = 32; reg5 = 0x82; reg6 = 0x02 }
            freq < 148_334_000L -> { multi = 24; reg5 = 0x22; reg6 = 0x00 }
            freq < 222_500_000L -> { multi = 16; reg5 = 0x42; reg6 = 0x02 }
            freq < 296_667_000L -> { multi = 12; reg5 = 0x12; reg6 = 0x00 }
            freq < 445_000_000L -> { multi = 8; reg5 = 0x22; reg6 = 0x02 }
            freq < 593_334_000L -> { multi = 6; reg5 = 0x0a; reg6 = 0x00 }
            freq < 950_000_000L -> { multi = 4; reg5 = 0x12; reg6 = 0x02 }
            else -> { multi = 2; reg5 = 0x0a; reg6 = 0x02 }
        }

        val fVco = freq * multi
        val vcoSelect = fVco >= 3_060_000_000L
        if (vcoSelect) reg6 = reg6 or 0x08

        // VCO 分频值（四舍五入）
        var xdiv = fVco / xtalHalf
        if (fVco - xdiv * xtalHalf >= xtalHalf / 2) xdiv++

        var pm = (xdiv / 8).toInt()
        var am = (xdiv - 8L * pm).toInt()
        if (am < 2) {
            am += 8
            pm--
        }

        val reg1: Int
        val reg2: Int
        if (pm > 31) {
            reg1 = am + 8 * (pm - 31)
            reg2 = 31
        } else {
            reg1 = am
            reg2 = pm
        }
        if (reg1 > 15 || reg2 < 0x0B) return null

        reg6 = reg6 or 0x20  // librtlsdr 注释：fix clock out

        // XIN：VCO 小数部分
        val rem = fVco - (fVco / xtalHalf) * xtalHalf
        var xin = ((rem / 1000) shl 15) / (xtalHalf / 1000)
        if (xin >= 16384) xin += 32768
        val reg3 = ((xin shr 8) and 0xFF).toInt()
        val reg4 = (xin and 0xFF).toInt()

        // 带宽位（先清掉 bit7/6 再按带宽置位）
        reg6 = reg6 and 0x3F
        when (bandwidth) {
            6_000_000L -> reg6 = reg6 or 0x80
            7_000_000L -> reg6 = reg6 or 0x40
            else -> {}
        }

        reg5 = reg5 or 0x07  // Realtek 解调器要求

        return Regs(multi, reg1, reg2, reg3, reg4, reg5, reg6, vcoSelect)
    }
}
