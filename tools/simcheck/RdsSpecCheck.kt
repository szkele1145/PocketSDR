import com.pocketsdr.app.source.RdsEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * RDS 位布局的独立交叉验证。
 *
 * ## 为什么要有这个测试
 *
 * v2.78.0 里踩过一个典型的坑：**编码器和解码器都把 RDS 的「段地址」写在了
 * B 块的 bit 1-0**，而 IEC 62106 规定段地址在 **bit 4-2**（bit 1-0 是
 * C1/C0 交通信息标志，真机上恒为 0）。
 *
 * 因为编码器和解码器「自洽」，桌面自检一路绿灯——但真机上所有段的段地址
 * 都会被读成 0，台名只能显示前 2 个字符。
 *
 * 这类 bug 用「同一套代码自己验自己」永远发现不了。必须有一个**独立的、
 * 只按规范手写的参考实现**来交叉验证位布局。
 *
 * 本文件里的参考解码器**完全独立于 RdsDecoder / RdsEncoder**：
 *  - 自己写正交解调 + 自己的巴特沃斯低通（不 import 项目的 Biquad）
 *  - 自己算 CRC（多项式写成移位形式，不复用项目的常量）
 *  - 自己按 IEC 62106 Table 9 解释 B 块的每一个字段
 *  - 自己穷举载波相位和 bit 相位
 *
 * 它只用来确认「项目里的编码器产生的波形，按规范独立解出来也是对的」。
 */
fun main() {
    val fs = 256_000f
    val expected = "POCKETSD"
    val rdsOnce = RdsEncoder(expected).buildWave(fs)

    // 波形要**循环播放**才有足够的 group：编码器一轮只发 4 个 segment
    // （= 4 个 group），而且第一个 bit 因为没有前值可比会被跳过，
    // 导致第 1 个 group 必然错位 —— 一轮只够解出 3 个 group，
    // 凑不齐 4 个 PS 段。真实电台是连续发射的，所以这里循环 6 遍。
    val loops = 6
    val rds = FloatArray(rdsOnce.size * loops)
    for (k in 0 until loops) {
        System.arraycopy(rdsOnce, 0, rds, k * rdsOnce.size, rdsOnce.size)
    }

    println("=== RDS 位布局交叉验证（独立参考实现）===")

    // ---- 1) 正交解调 + 低通，拿到基带双相波形 ----
    // 载波相位未知 → 穷举。bit 相位也未知 → 穷举。
    val n = rds.size
    val carrierStep = 30.0            // 载波相位步进（度）
    var bestName = ""
    var bestGroups = 0
    var bestCarrier = 0.0
    var bestBitPhase = 0

    var carrier = 0.0
    while (carrier < 360.0) {
        val base = demodulate(rds, fs, carrier)
        for (bitPhase in 0 until 215) {
            val g = decodeGroups(base, fs, bitPhase)
            if (g.size > bestGroups) {
                bestGroups = g.size
                bestName = decodePs(g)
                bestCarrier = carrier
                bestBitPhase = bitPhase
            }
        }
        carrier += carrierStep
    }

    println("  最佳: 载波相位 ${bestCarrier}°  bit 相位 $bestBitPhase  group 数 $bestGroups")
    println("  参考实现解出的台名: '$bestName'")

    if (bestName == expected) {
        println("PASS: 项目编码器的波形按 IEC 62106 独立解出来是 '$expected'")
        println("      —— 位布局（B 块段地址在 bit 4-2 等）与规范一致")
        return
    }
    println("FAIL: 独立参考实现解出 '$bestName'，期望 '$expected'")
    println("      —— 位布局与规范不一致（编码器和解码器可能都错了但自洽）")
    kotlin.system.exitProcess(1)
}

/**
 * 正交解调 + 两级巴特沃斯低通，得到基带波形。
 *
 * 载波初相由 carrierDeg 给出。RDS 是抑制载波的 DSB，正交解调后
 * I 路 = base(t)·cos(φ)，所以载波相位未知时 I 路会被压成 cos(φ) 倍，
 * 甚至为 0——这正是要穷举载波相位的原因。
 */
fun demodulate(x: FloatArray, fs: Float, carrierDeg: Double): FloatArray {
    val fc = 2400f                       // 低通截止（覆盖 1187.5 bps 足够）
    val wc = tan(PI * fc.toDouble() / fs.toDouble()).toFloat()
    val norm = 1f / (1f + sqrt(2f) * wc + wc * wc)
    val b0 = wc * wc * norm
    val b1 = 2f * b0
    val b2 = b0
    val a1 = 2f * (wc * wc - 1f) * norm
    val a2 = (1f - sqrt(2f) * wc + wc * wc) * norm

    // 两个 biquad 的状态
    var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
    var x3 = 0f; var x4 = 0f; var y3 = 0f; var y4 = 0f
    val out = FloatArray(x.size)
    val ph = carrierDeg * PI / 180.0
    val w = 2.0 * PI * 57000 / fs.toDouble()

    for (i in x.indices) {
        val v = x[i].toDouble()
        val iMix = (v * cos(w * i + ph)).toFloat()
        // 第一级 biquad
        val y = b0 * iMix + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = iMix; y2 = y1; y1 = y
        // 第二级 biquad
        val y2n = b0 * y + b1 * x3 + b2 * x4 - a1 * y3 - a2 * y4
        x4 = x3; x3 = y; y4 = y3; y3 = y2n
        out[i] = y2n
    }
    return out
}

/**
 * 按 IEC 62106 从波形里解出 group 列表。
 *
 * 差分双相判决：比较当前采样与「前一个 bit 同相位采样」的符号，反号 = 1。
 * 位时钟是标准的 1187.5 bps → 每 bit spb = fs/1187.5 个采样（小数）。
 * bit 位置必须用小数累加，取整会让判决点逐 bit 漂移。
 */
fun decodeGroups(base: FloatArray, fs: Float, bitPhase: Int): List<IntArray> {
    val spb = fs / 1187.5f
    val offsets = intArrayOf(0x000, 0x0FC, 0x0D9, 0x1D9)
    val poly = 0b10100101101

    fun crc10(data: Int): Int {
        var rem = data shl 10
        for (i in 25 downTo 10) {
            if (((rem shr i) and 1) == 1) rem = rem xor (poly shl (i - 10))
        }
        return rem and 0x3FF
    }

    val groups = ArrayList<IntArray>()
    var expectBlock = 0
    var reg = 0L
    var bits = 0
    val blockData = IntArray(4)
    var blockIdx = 0
    var prevAt = -1
    var bitTime = 0.0

    while (bitTime + bitPhase < base.size) {
        val at = bitTime.toInt() + bitPhase
        if (prevAt < 0) { prevAt = at; bitTime += spb; continue }
        val cur = base[at]
        val prv = base[prevAt]
        prevAt = at
        val bit = if (cur * prv < 0f) 1 else 0
        reg = (reg shl 1) or bit.toLong()
        bits++
        if (bits >= 26) {
            val w26 = (reg and 0x3FFFFFFL).toInt()
            val data = (w26 shr 10) and 0xFFFF
            val check = w26 and 0x3FF
            if ((crc10(data) xor offsets[expectBlock]) == check) {
                blockData[blockIdx] = data
                blockIdx++
                expectBlock = (expectBlock + 1) and 3
                if (blockIdx == 4) {
                    groups.add(blockData.copyOf())
                    blockIdx = 0
                }
                reg = 0L; bits = 0
            } else {
                bits = 25
            }
        }
        bitTime += spb
    }
    return groups
}

/**
 * 按 IEC 62106 从 group 列表里取 PS 字符。
 *
 * B 块（Table 9）：
 *   bit 15-12 group type, bit 11 B0, bit 10 A0, bit 9-5 PTY,
 *   **bit 4-2 段地址**, bit 1-0 C1/C0
 *
 * D 块：0A/0B 组的 PS 字符（2 个 ASCII）。
 */
fun decodePs(groups: List<IntArray>): String {
    val chars = CharArray(8) { ' ' }
    val seen = BooleanArray(4)
    for (g in groups) {
        val b = g[1]
        val gtype = (b shr 12) and 0xF
        if (gtype != 0 && gtype != 1) continue     // 只有 0A/0B 才有 PS
        val segment = (b shr 2) and 0x7            // **段地址在 bit 4-2**
        if (segment > 3) continue
        val d = g[3]
        val c1 = (d shr 8) and 0xFF
        val c2 = d and 0xFF
        chars[segment * 2] = if (c1 in 32..126) c1.toChar() else ' '
        chars[segment * 2 + 1] = if (c2 in 32..126) c2.toChar() else ' '
        seen[segment] = true
    }
    if (!seen.all { it }) return ""
    return String(chars).trim()
}
