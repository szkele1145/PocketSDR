import com.pocketsdr.app.source.rtl.Fc0013Pll

/**
 * FC0013 PLL 算式的交叉验证。
 *
 * ## 为什么值得单独测
 *
 * 这套算式算错了不会崩，只会表现为「能连上、频谱里什么都没有」或者
 * 「收到的台频率偏了」。它依赖真实 USB 设备，没有硬件时一行都跑不到，
 * 而真机联调时又最难排查——所以必须在桌面端先把它钉住。
 *
 * ## 基准值怎么来的
 *
 * 不是用本项目的 Kotlin 实现算的（那样只是自己验自己）。基准值是**照
 * librtlsdr `src/tuner_fc0013.c` 的 `fc0013_set_params()` 用 PowerShell
 * 独立实现一遍**算出来的——不同语言、不同代码路径，才算交叉验证。
 *
 * 覆盖：FM 广播（88.5/98/100/108 MHz）、航空段（118 MHz）、业余 2m
 * （145 MHz）、ISM 433.92 MHz、以及 27 MHz（走 multi=96 的最低频段）。
 */
fun main() {
    println("=== FC0013 PLL 算式交叉验证 ===")

    // freq, multi, r1, r2, r3, r4, r5, r6（带宽按默认 6MHz）
    data class Case(
        val freq: Long, val multi: Int,
        val r1: Int, val r2: Int, val r3: Int, val r4: Int, val r5: Int, val r6: Int
    )

    val cases = listOf(
        Case(88_500_000L, 32, 0x05, 0x18, 0xD5, 0x55, 0x87, 0xA2),
        Case(98_000_000L, 32, 0x02, 0x1B, 0xE3, 0x8E, 0x87, 0xAA),
        Case(100_000_000L, 32, 0x06, 0x1B, 0x1C, 0x71, 0x87, 0xAA),
        Case(108_000_000L, 32, 0x08, 0x1D, 0x00, 0x00, 0x87, 0xAA),
        Case(118_000_000L, 24, 0x05, 0x18, 0xD5, 0x55, 0x27, 0xA0),
        Case(145_000_000L, 24, 0x02, 0x1E, 0xD5, 0x55, 0x27, 0xA8),
        Case(433_920_000L, 8, 0x09, 0x1D, 0x08, 0x88, 0x27, 0xAA),
        Case(27_000_000L, 96, 0x04, 0x16, 0x00, 0x00, 0x87, 0xA0)
    )

    var fail = 0
    for (c in cases) {
        val got = Fc0013Pll.compute(c.freq)
        if (got == null) {
            fail++
            println("  FAIL  ${c.freq} Hz 算不出合法 PLL 组合")
            continue
        }
        val expected = intArrayOf(c.r1, c.r2, c.r3, c.r4, c.r5, c.r6)
        val actual = intArrayOf(got.r1, got.r2, got.r3, got.r4, got.r5, got.r6)
        var bad = -1
        for (k in 0 until 6) if (expected[k] != actual[k]) { bad = k; break }
        val ok = (bad < 0) && got.multi == c.multi
        if (ok) {
            println("  PASS  ${c.freq / 1000}kHz  multi=${got.multi} " +
                    "regs=" + actual.joinToString(" ") { "%02X".format(it) })
        } else {
            fail++
            val regName = if (bad >= 0) "r${bad + 1}" else "multi"
            println("  FAIL  ${c.freq / 1000}kHz  $regName 不符：" +
                    "实际 multi=${got.multi} regs=" + actual.joinToString(" ") { "%02X".format(it) } +
                    "，期望 multi=${c.multi} regs=" + expected.joinToString(" ") { "%02X".format(it) })
        }
    }

    // 边界：低到调不了的频率应当返回 null 而不是瞎算一个值出来。
    // 0 Hz 和负数在真机上不会出现，但能从侧面证明算式有合法性检查。
    val bogus = Fc0013Pll.compute(0L)
    if (bogus == null) {
        println("  PASS  0 Hz 被拒绝（返回 null）")
    } else {
        fail++
        println("  FAIL  0 Hz 竟然算出了寄存器值：multi=${bogus.multi}")
    }

    if (fail > 0) {
        println("== FC0013 PLL 验证失败：$fail 项 ==")
        kotlin.system.exitProcess(1)
    }
    println("== FC0013 PLL 验证全部通过（基准值来自 librtlsdr 独立实现）==")
}
