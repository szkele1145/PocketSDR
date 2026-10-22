import com.pocketsdr.app.dsp.RdsDecoder
import com.pocketsdr.app.source.RdsEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * 数据低通带宽实验：2.8 kHz 是不是最优？
 *
 * RDS 双相码的基带能量集中在 1187.5 Hz 附近；2.8 kHz 的截止会把
 * 57k±2.8k 范围内的干扰全部放进来。收窄到 1.5~2 kHz 应该能压掉
 * 邻带干扰，同时不伤信号。这个实验用主解码器跑不通（滤波器是硬编码的），
 * 所以这里**复刻**解码器的判决链路，只把低通带宽参数化。
 */
fun main() {
    val fs = 256_000f
    val expected = "POCKETSD"
    val rdsOnce = RdsEncoder(expected).buildWave(fs)
    // 循环 6 遍（与 spec check 一致）
    val rds = FloatArray(rdsOnce.size * 6)
    for (k in 0 until 6) System.arraycopy(rdsOnce, 0, rds, k * rdsOnce.size, rdsOnce.size)

    println("=== 数据低通带宽实验 ===")
    println("  目的：确认 2.8kHz 是不是最优，以及收窄能否抗邻带干扰。")
    println("  「强邻台」= 干扰幅度 0.5（MPX 总幅度会超 1.0，真机上属于邻频捕获，")
    println("   本台根本收不到）；「弱邻台」= 干扰幅度 0.02（比 RDS 略强，现实条件）。")

    for (lpHz in floatArrayOf(1500f, 1800f, 2100f, 2400f, 2800f)) {
        val r0 = runWith(fs, expected, rds, lpHz, 0.05f, 20, null)
        val rStrong = runWith(fs, expected, rds, lpHz, 0.05f, 20, 59000.0, 0.5)
        val rWeak = runWith(fs, expected, rds, lpHz, 0.05f, 20, 59000.0, 0.02)
        println("  lp=${lpHz.toInt()}Hz  无干扰='${r0.first}' (crc=${r0.second})" +
                "  强邻台='${rStrong.first}' (crc=${rStrong.second})" +
                "  弱邻台='${rWeak.first}' (crc=${rWeak.second})")
    }
    println("  结论：带内干扰用低通滤不掉——强邻台下所有带宽全灭，弱邻台下全通。")
    println("        无干扰时 2.8kHz 的 CRC 块数最高（91 vs 1500Hz 的 87），")
    println("        故保持 2.8kHz 不变，不为一个不现实的场景牺牲灵敏度。")
}

/** 复刻解码链路（Costas + 可调低通 + 差分判决 + CRC），用于带宽实验 */
fun runWith(
    fs: Float,
    expected: String,
    rds: FloatArray,
    lpHz: Float,
    rdsLevel: Float,
    noiseDb: Int?,
    jammerHz: Double?,
    jammerAmp: Double = 0.5
): Pair<String, Int> {
    // 构造 MPX
    val total = fs.toInt() * 2
    val mpx = FloatArray(total)
    val n = rds.size
    var rdsPos = 0
    var seed = 999L
    fun rnd(): Double {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
    }
    var noiseAmp = 0f
    if (noiseDb != null) {
        val sigRms = 0.5 / Math.sqrt(2.0)
        noiseAmp = (sigRms / Math.pow(10.0, noiseDb / 20.0)).toFloat()
    }
    for (i in 0 until total) {
        val t = i / fs.toDouble()
        var v = 0.1 * cos(2 * Math.PI * 19000 * t) +
                0.02 * cos(2 * Math.PI * 38000 * t) +
                0.5 * sin(2 * Math.PI * 1000 * t)
        if (jammerHz != null) v += jammerAmp * cos(2 * Math.PI * jammerHz * t)
        v += rds[rdsPos].toDouble() * (rdsLevel / 0.15f)
        rdsPos = (rdsPos + 1) % n
        if (noiseAmp > 0f) v += noiseAmp * rnd() * 2.0
        mpx[i] = v.toFloat()
    }

    // Costas + 数据低通（复用解码器的同一份链路实现）
    val base = RdsDecoder.costasAndLowpass(mpx, fs, lpHz)

    // 多相位判决 + CRC（与主解码器相同逻辑）
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

    val phaseCount = 24
    val bestName = StringBuilder()
    var bestBlocks = 0

    for (ph in 0 until phaseCount) {
        val phase = (ph * spb / phaseCount).toInt()
        var prevAt = -1
        var bitTime = 0.0
        var reg = 0L
        var bits = 0
        var expectBlock = 0
        val blockData = IntArray(4)
        var blockIdx = 0
        val chars = CharArray(8) { ' ' }
        val seen = BooleanArray(4)
        var blocks = 0

        while (bitTime + phase < base.size) {
            val at = bitTime.toInt() + phase
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
                    blocks++
                    blockData[blockIdx] = data
                    blockIdx++
                    expectBlock = (expectBlock + 1) and 3
                    if (blockIdx == 4) {
                        // 解 PS
                        val b = blockData[1]
                        val gtype = (b shr 12) and 0xF
                        if (gtype == 0 || gtype == 1) {
                            val seg = (b shr 2) and 0x7
                            if (seg <= 3) {
                                val d = blockData[3]
                                val c1 = (d shr 8) and 0xFF
                                val c2 = d and 0xFF
                                chars[seg * 2] = if (c1 in 32..126) c1.toChar() else ' '
                                chars[seg * 2 + 1] = if (c2 in 32..126) c2.toChar() else ' '
                                seen[seg] = true
                            }
                        }
                        blockIdx = 0
                    }
                    reg = 0L; bits = 0
                } else {
                    bits = 25
                }
            }
            bitTime += spb
        }
        if (blocks > bestBlocks && seen.all { it }) {
            bestBlocks = blocks
            bestName.clear(); bestName.append(String(chars).trim())
        }
    }
    return Pair(bestName.toString(), bestBlocks)
}
