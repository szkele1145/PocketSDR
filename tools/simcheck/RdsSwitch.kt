import com.pocketsdr.app.dsp.RdsDecoder
import com.pocketsdr.app.source.RdsEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「换台不串台名」的回归测试。
 *
 * ## 为什么要这个测试
 *
 * v2.83.0 修了一个**不报错、只是结果错**的缺陷：换台时没有清 RDS 状态。
 * 后果是上一个台的台名会挂着，更糟的是两个台的段混在同一张投票表里，
 * 可能拼出一个**根本不存在的混合台名**。
 *
 * 这类缺陷现有测试全都抓不到：
 *  - 单台端到端测试（RdsCheck）只测一个台，不换台；
 *  - 抗噪测试（RdsRobust）每个用例都新建解码器，不存在跨台污染。
 *
 * 所以专门写一个「A 台 → B 台」的用例。关键是要**证明这个测试抓得住
 * 问题**：一半断言「清状态后必须干净」，另一半断言「不清状态确实会被
 * 污染」。只断言前者是不够的——如果污染本来就不会发生，那条断言永远
 * 为真，等于没测。
 */
fun main() {
    val fs = 256_000f

    // 两个字符集完全不重叠的台名，便于程序判断有没有混
    val nameA = "AAAAAAAA"
    val rtA = "STATION A TEXT"
    val nameB = "BBBBBBBB"
    val rtB = "STATION B TEXT"

    val baseA = RdsEncoder(nameA, rtA).buildBaseband(fs, 6)
    val baseB = RdsEncoder(nameB, rtB).buildBaseband(fs, 6)

    // 每个台喂 3 秒（足够凑齐 4 段 PS）
    val n = (fs * 3.0f).toInt()

    println("=== RDS 换台污染测试 ===")

    // ---------- 场景 1：不清状态，直接切台（对照，应当被污染） ----------
    val a1 = RdsDecoder(fs)
    feed(a1, baseA, n, fs)
    val dirtyNameA = a1.stationName.trim()
    val dirtyAtSwitch = a1.stationName.trim()   // 切台那一刻还挂着的旧名
    feed(a1, baseB, n, fs)
    val dirtyNameB = a1.stationName.trim()

    // ---------- 场景 2：切台时调 resetAll（应当干净） ----------
    val a2 = RdsDecoder(fs)
    feed(a2, baseA, n, fs)
    val cleanNameA = a2.stationName.trim()
    a2.resetAll()
    val cleanAtSwitch = a2.stationName.trim()
    feed(a2, baseB, n, fs)
    val cleanNameB = a2.stationName.trim()

    println("  [不清状态] 台 A 解出 '$dirtyNameA'，切台瞬间仍显示 '$dirtyAtSwitch'")
    println("  [不清状态] 切到 B 后最终 '$dirtyNameB'")
    println("  [清状态  ] 台 A 解出 '$cleanNameA'，resetAll 后立刻 '$cleanAtSwitch'")
    println("  [清状态  ] 切到 B 后最终 '$cleanNameB'")

    var fail = 0

    // 前置条件：台 A 必须先解出来，否则整个用例没有意义
    if (cleanNameA != nameA) {
        fail++
        println("  FAIL  前置：台 A 没解出来（'$cleanNameA' != '$nameA'），用例无效")
    }

    // 断言 1：resetAll 必须立刻清空台名（不留旧台名）
    if (cleanAtSwitch.isEmpty()) {
        println("  PASS  resetAll 立刻清空台名")
    } else {
        fail++
        println("  FAIL  resetAll 之后台名没有立刻清空（仍是 '$cleanAtSwitch'）")
    }

    // 断言 2：清状态后切台，解出的必须是纯 B
    if (cleanNameB == nameB) {
        println("  PASS  清状态后切台解出纯 '$nameB'")
    } else {
        fail++
        println("  FAIL  清状态后切台解出 '$cleanNameB'，期望 '$nameB'")
    }

    // 断言 3：清状态后不能残留任何 A 的字符
    if (cleanNameB.contains('A')) {
        fail++
        println("  FAIL  清状态后台名里仍混进 A 台的字符：'$cleanNameB'")
    }

    // 断言 4（对照）：不清状态时**确实会**被污染。
    // 这一条是「测试的有效性证明」：如果不清状态也能得到纯 B，说明本用例
    // 根本没造出污染条件，断言 1/2 就失去意义。
    if (dirtyNameB != nameB) {
        println("  PASS  对照：不清状态时结果被污染（'$dirtyNameB' != '$nameB'）")
    } else {
        fail++
        println("  FAIL  对照：不清状态也解出纯 B —— 用例没造出污染条件，需调整台名或时长")
    }

    if (fail > 0) {
        println("== RDS 换台污染测试失败：$fail 项 ==")
        kotlin.system.exitProcess(1)
    }
    println("== RDS 换台污染测试全部通过 ==")
}

/** 把基带波形按真实 MPX 环境（19k 导频 + 38k 副载波 + 1k 音频 + 57k RDS）喂给解码器 */
private fun feed(dec: RdsDecoder, base: FloatArray, n: Int, fs: Float) {
    val w = 2.0 * Math.PI * 57000 / fs
    for (i in 0 until n) {
        val t = i / fs.toDouble()
        var v = 0.1 * cos(2 * Math.PI * 19000 * t) +
                0.02 * cos(2 * Math.PI * 38000 * t) +
                0.5 * sin(2 * Math.PI * 1000 * t)
        if (i < base.size) v += base[i] * cos(w * i) * 0.075
        dec.process(v.toFloat())
    }
}
