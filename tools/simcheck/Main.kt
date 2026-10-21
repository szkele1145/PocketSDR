import com.pocketsdr.app.dsp.Fft
import com.pocketsdr.app.source.SimulatedSource
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.log10

/**
 * 仿真源增益模型的 JVM 验证（复刻 SdrEngine 的谱统计逻辑）。
 *
 * 引擎里的那段统计依赖 android（UiBridge），没法在 JVM 上直接跑，
 * 所以这里用同款数学单独实现一份：
 *   - updateSpectrum：4 段 FFT 平均 + 10*log10(bin 功率/scaleInv)
 *   - updateNoiseFloor：64 个等间隔 bin、排序后 25% 分位作底噪、最大值作峰值
 *
 * GainScanner 的判据就建立在这两个数上：
 *   - SNR = peak - floor，推荐 = 不过载前提下的最高 SNR
 *   - 削顶 = peak >= -3 dBFS
 */
fun main() {
    val src = SimulatedSource(98_100_000, 1_024_000f)
    val fftSize = 1024
    val fft = Fft(fftSize)
    val fftPower = FloatArray(fftSize)
    val scaleInv = (1f / 4f) / (fftSize * fftSize / 4f)
    val half = fftSize / 2
    val iq = FloatArray(16384 * 2)
    val gains = intArrayOf(-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197)

    println("%7s | %8s | %8s | %6s | %s".format("gain", "floor", "peak", "SNR", "clip(>=-3dBFS)"))
    val measured = ArrayList<Triple<Int, Float, Float>>()
    for (g in gains) {
        src.setLnaGain(g)
        src.setGainMode(true)
        // 热身：让相位推进器与查表进入稳态（引擎里有 resetNoiseStats 跳 4 帧的同类处理）
        repeat(6) { src.read(iq, 16384) }
        java.util.Arrays.fill(fftPower, 0f)
        var base = 0
        val segStart = 16384 / 4
        repeat(4) {
            fft.powerSpectrumSeg(iq, base, fftPower)
            base += segStart
        }
        val dbs = FloatArray(64)
        val step = fftSize / 64
        var c = 0
        var k = 0
        while (k < fftSize && c < 64) {
            val srcBin = (k + half) and (fftSize - 1)
            val db = 10f * log10((fftPower[srcBin] * scaleInv).toDouble() + 1e-12).toFloat()
            dbs[c++] = db
            k += step
        }
        val sorted = dbs.copyOf()
        Arrays.sort(sorted)
        val floor = sorted[(c / 4).coerceIn(0, c - 1)]
        val peak = sorted.max()
        val snr = peak - floor
        val clipped = peak >= -3f
        measured.add(Triple(g, floor, peak))
        println("%6.1f | %7.1f | %7.1f | %5.1f | %s".format(g / 10f, floor, peak, snr, if (clipped) "CLIP" else ""))
    }

    // == 复刻 GainScanner 的完整判定链（v2.75.0 起：SNR 回落为主判据） ==
    val CLIP_DB = -3f
    val SNR_DROP_DB = 3f
    val snrs = measured.map { it.third - it.second }
    val maxSnr = snrs.max()
    println()
    println("== 过载判定（SNR 回落 > %.0fdB 或 峰值 >= %.0fdBFS）==".format(SNR_DROP_DB, CLIP_DB))
    var bestGain = 0
    var bestSnr = -1e9f
    for ((i, m) in measured.withIndex()) {
        val snr = snrs[i]
        val over = m.third >= CLIP_DB || snr < maxSnr - SNR_DROP_DB
        println("%6.1f dB   SNR %5.1f   %s".format(m.first / 10f, snr, if (over) "过载" else "可用"))
        // 可用区里 SNR 最高者；同分取更高增益（与 GainScanner 的 compareBy 一致）
        if (!over && (snr > bestSnr || (snr == bestSnr && m.first > bestGain))) {
            bestSnr = snr
            bestGain = m.first
        }
    }
    println("推荐档位 = %.1f dB (SNR %.1f)".format(bestGain / 10f, bestSnr))
    val recommendedMidBand = bestGain in 58..71
    println(if (recommendedMidBand) "PASS：推荐落在中段（不过载 + 高 SNR）" else "FAIL：推荐落在 $bestGain（期望 58..71）")

    println()
    println("== 自动模式：改增益不应改变输出 ==")
    src.setGainMode(false)
    val buf = FloatArray(4096 * 2)
    fun power(): Double {
        src.read(buf, 4096)
        var p = 0.0
        for (v in buf) p += v.toDouble() * v
        return p / 4096
    }
    src.setLnaGain(-99); repeat(2) { src.read(buf, 4096) }; val p1 = power()
    src.setLnaGain(197); repeat(2) { src.read(buf, 4096) }; val p2 = power()
    val ratioDb = 10 * log10(p2 / p1)
    println("低档功率=%.6f 高档功率=%.6f 差=%.2f dB（|差|<1 才算通过）%s".format(p1, p2, ratioDb, if (abs(ratioDb) < 1.0) " PASS" else " FAIL"))
}
