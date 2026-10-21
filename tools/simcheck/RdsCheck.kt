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

    // 2 秒的 MPX
    val n = (fs * 2.0f).toInt()
    val mpx = FloatArray(n)

    // RDS 波形（57k DSB）
    val rds = RdsEncoder(expected).buildWave(fs)
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
        // RDS
        v += rds[rdsPos].toDouble()
        rdsPos = (rdsPos + 1) % rds.size
        mpx[i] = v.toFloat()
    }

    val dec = RdsDecoder(fs)
    for (i in 0 until n) dec.process(mpx[i])

    println("stationName = '${dec.stationName}'")
    println("dataLock    = ${dec.dataLock}")
    val ok = dec.stationName.trim() == expected && dec.dataLock
    println(if (ok) "PASS: RDS 台名解出正确" else "FAIL: 台名没解出来（期望 '$expected'）")
    kotlin.system.exitProcess(if (ok) 0 else 1)
}
