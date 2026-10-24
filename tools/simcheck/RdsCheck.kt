import com.pocketsdr.app.dsp.RdsDecoder
import com.pocketsdr.app.source.RdsEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * RDS 端到端验证（编码器 → 57k DSB MPX → 解码器）。
 *
 * RDS 是 FM 广播显示台名的功能，但解码器从来没被验证过：
 * 里面有 Costas 环、位同步、差分双相判决、CRC 校验，
 * 任何一环错了都表现为「锁不上台名」，而真机还没到货，没法听。
 *
 * 这里用工程里现成的 RdsEncoder 生成标准 RDS 波形，混进模拟真实
 * MPX 的环境里（导频 + 立体声差分 + 音频），再送进 RdsDecoder，
 * 看它能不能把台名解出来。
 *
 * 注意：RDS 的 PS（节目名）字段规定就是 **8 个字符**，测试台名必须是 8 字符。
 */
fun main() {
    val fs = 256_000f
    val expected = "POCKETSD"   // 8 字符，RDS PS 字段上限
    val expectedRt = "HELLO FROM POCKETSDR"
    // AF 测试向量：98.0/99.5/101.3/104.7 MHz（4 个，占 2 个 PS 段的 C 块）
    val expectedAf = listOf(98_000, 99_500, 101_300, 104_700)

    // 4 秒的 MPX
    val n = (fs * 4.0f).toInt()
    val mpx = FloatArray(n)

    // 57k DSB 波形：**一次性生成足够长的连续波形**，不再循环拼接。
    // 循环拼接会在接缝处让 57k 载波相位跳变、并插入多余的比特，接缝
    // 处那一组必然作废（曾经表现为台名永远缺 PS 段 0，显示 "CKETSD"）。
    // 真实电台是连续发射的，生成连续波形才和真实情况一致。
    val one = RdsEncoder(expected, expectedRt, expectedAf).buildBaseband(fs)
    val enc = RdsEncoder(expected, expectedRt, expectedAf)
    val loops = (n.toDouble() / one.size).toInt() + 1
    val rds = enc.buildWave(fs, loops)
    var rdsPos = 0

    // 19kHz 导频 + 38kHz 副载波（立体声用）+ 1kHz 音频
    for (i in 0 until n) {
        val t = i / fs.toDouble()
        var v = 0.0
        // 导频 0.1
        v += 0.1 * cos(2 * Math.PI * 19000 * t)
        // 38k 副载波（差分通道，幅度很小）
        v += 0.02 * cos(2 * Math.PI * 38000 * t)
        // 音频
        v += 0.5 * sin(2 * Math.PI * 1000 * t)
        // RDS（波形足够长，超出就直接结束——不循环）
        if (rdsPos < rds.size) v += rds[rdsPos].toDouble()
        rdsPos++
        mpx[i] = v.toFloat()
    }

    val dec = RdsDecoder(fs)
    for (i in 0 until n) dec.process(mpx[i])

    println("stationName = '${dec.stationName}'")
    println("radioText   = '${dec.radioText}'")
    println("pty         = ${dec.pty}")
    println("rtSegments  = ${dec.rtSegments}")
    println("psSegments  = ${dec.psSegments}")
    println("crcBlocksOk = ${dec.crcBlocksOk}")
    println("dataLock    = ${dec.dataLock}")
    println("af          = ${dec.afFrequenciesKhz}")
    if (System.getenv("RDS_DEBUG") != null) println(dec.debugDump())
    val okName = dec.stationName.trim() == expected && dec.dataLock
    val okRt = dec.radioText.trim() == expectedRt
    val okPty = dec.pty == 1   // 编码器发的 PTY=1（新闻）
    val okAf = dec.afFrequenciesKhz == expectedAf
    println(if (okName) "PASS: RDS 台名解出正确" else "FAIL: 台名没解出来（期望 '$expected'）")
    println(if (okRt) "PASS: RDS RadioText 解出正确" else "FAIL: RT 没解出来（期望 '$expectedRt'，实得 '${dec.radioText.trim()}'）")
    println(if (okPty) "PASS: RDS PTY 解出正确 (${dec.pty})" else "FAIL: PTY 不对（期望 1，实得 ${dec.pty}）")
    println(if (okAf) "PASS: RDS AF 解出正确 ${dec.afFrequenciesKhz}" else "FAIL: AF 不对（期望 $expectedAf，实得 ${dec.afFrequenciesKhz}）")
    kotlin.system.exitProcess(if (okName && okRt && okPty && okAf) 0 else 1)
}
