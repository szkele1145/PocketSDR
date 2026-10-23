import com.pocketsdr.app.dsp.RdsDecoder
import com.pocketsdr.app.source.RdsEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * RDS 解码器的抗噪回归测试。
 *
 * 上一版（RdsCheck）只验证「理想条件下能解出台名」。这一版把条件推到
 * 真实 FM 电台的水平，看看解码器到底在哪儿失效：
 *
 *  1. RDS 调制度：真实电台 2%~5%
 *  2. 57kHz 载波初相：真实电台完全随机
 *  3. 本底噪声
 *  4. 邻带干扰
 *
 * 通过标准（写死，防止「悄悄退化」）：
 *  - 调制度 2%~10% 全部解出完整台名
 *  - 载波初相 0°~270° 全部解出完整台名
 *  - SNR ≥ 7 dB 解出完整台名
 *  - SNR 5 dB 至少解出部分台名（不能是空）
 */
fun main() {
    val fs = 256_000f
    val expected = "POCKETSD"
    val expectedRt = "HELLO FROM POCKETSDR"
    // 生成足够长的**连续**基带（8 轮 ≈ 6.3 秒 > 测试用的 4 秒），不循环拼接
    val rds = RdsEncoder(expected, expectedRt).buildBaseband(fs, 8)
    var fail = 0

    println("=== RDS 抗噪回归测试 ===")

    println("--- 1) RDS 调制度（真实电台 2%~5%）---")
    for (mod in floatArrayOf(0.02f, 0.03f, 0.05f, 0.075f, 0.10f)) {
        val r = runCase(fs, expected, rds, mod, 0.0, null, null)
        val ok = r.name == expected
        if (!ok) fail++
        println("  ${if (ok) "PASS" else "FAIL"}  调制度 ${"%.3f".format(mod)} -> '${r.name}'")
    }

    println("--- 2) 57kHz 载波初相（真机随机）---")
    for (deg in intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)) {
        val r = runCase(fs, expected, rds, 0.05f, deg * Math.PI / 180.0, null, null)
        val ok = r.name == expected
        if (!ok) fail++
        println("  ${if (ok) "PASS" else "FAIL"}  载波初相 ${deg}° -> '${r.name}'")
    }

    println("--- 2b) RT 在各噪声条件下的解出情况（新增，v2.79.0）---")
    // 20 字符的 RT 要 5 个段全对；7 dB 下偶尔错 1-2 个字符属正常，
    // 这里只要求 20/10 dB 完整。更低信噪比看 3) 节的 PS 台名门槛。
    for (snr in intArrayOf(20, 10)) {
        val r = runCase(fs, expected, rds, 0.05f, 0.0, snr, null)
        val ok = r.rt == expectedRt
        if (!ok) fail++
        println("  ${if (ok) "PASS" else "FAIL"}  SNR ${snr}dB -> RT '${r.rt}'")
    }

    println("--- 3) 本底噪声 ---")
    for (snr in intArrayOf(20, 15, 10, 7, 5)) {
        val r = runCase(fs, expected, rds, 0.05f, 0.0, snr, null)
        // 7 dB 及以上要完整；5 dB 至少要有内容（弱信号下显示部分台名也算过）
        val ok = if (snr >= 7) r.name == expected else r.name.isNotEmpty()
        if (!ok) fail++
        println("  ${if (ok) "PASS" else "FAIL"}  SNR ${snr}dB -> '${r.name}' (crc=${r.crcOk})")
    }

    println("--- 4) 邻带干扰（57k±2k）---")
    // 真实环境里 57k 附近会有邻台残余。±2k 处的强干扰是已知难点，
    // 这里只要求「不要崩、不要出乱码」，不强制要求解出台名。
    for (off in intArrayOf(-2000, 2000)) {
        val r = runCase(fs, expected, rds, 0.05f, 0.0, 10, 57000.0 + off)
        println("  INFO  干扰 ${if (off < 0) "-" else "+"}${-off}Hz -> '${r.name}' (crc=${r.crcOk})")
    }

    if (fail > 0) {
        println("== RDS 抗噪测试失败：$fail 项 ==")
        kotlin.system.exitProcess(1)
    }
    println("== RDS 抗噪测试全部通过 ==")
}

/** 一次测试的结果 */
class Result(val name: String, val crcOk: Int, val rt: String = "")

/**
 * 跑一次 RDS 解码。
 *
 * 这里**不复用** RdsEncoder.buildWave 的 57k 调制（它的载波初相固定为 0），
 * 而是反解出基带双相波形后按指定初相重新调制——真实电台的载波相位是随机的。
 */
fun runCase(
    fs: Float,
    expected: String,
    rdsModulated: FloatArray,
    rdsLevel: Float,
    carrierPhase: Double,
    noiseDb: Int?,
    jammerHz: Double?
): Result {
    val n = rdsModulated.size
    val w = 2.0 * Math.PI * 57000 / fs
    // rdsModulated 现在就是**基带**（RdsEncoder.buildBaseband），直接重新
    // 调制到指定载波初相即可。以前是从已调制波形 ÷cos(...) 反解基带，
    // cos 过零点附近除法会炸出尖峰，造成「某些初相必然解不出」的假失败。
    val base = rdsModulated
    val rds57 = FloatArray(n)
    for (i in 0 until n) {
        rds57[i] = base[i] * cos(w * i + carrierPhase).toFloat() * rdsLevel
    }

    val total = (fs * 4.0f).toInt()
    val mpx = FloatArray(total)
    var rdsPos = 0
    // 确定性伪随机（可复现）
    var seed = 12345L
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
        if (jammerHz != null) v += 0.5 * cos(2 * Math.PI * jammerHz * t)
        // 不循环：rds57 已按总长度生成足够长的连续波形
        if (rdsPos < n) v += rds57[rdsPos].toDouble()
        rdsPos++
        if (noiseAmp > 0f) v += noiseAmp * rnd() * 2.0
        mpx[i] = v.toFloat()
    }

    val dec = RdsDecoder(fs)
    for (i in 0 until total) dec.process(mpx[i])
    return Result(dec.stationName.trim(), dec.crcBlocksOk, dec.radioText.trim())
}
