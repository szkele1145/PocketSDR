package com.pocketsdr.app

import kotlin.math.abs

/**
 * FM 波段自动搜台器。
 *
 * 策略：按 100kHz 网格步进扫描（广播台间隔 100/200kHz），
 * 每个网格点静默调谐 → 测 IF 能量（静噪 dBFS）→ 记录信号强度曲线，
 * 局部极大且超过阈值的点即为电台。
 *
 * 在引擎 DSP 线程上运行（start 后引擎暂停正常解调输出，静噪期间不写音频）。
 */
class FmScanner(
    private val engine: SdrEngine,
    private val onProgress: (percent: Int, found: Int) -> Unit,
    private val onDone: (stations: List<Station>) -> Unit
) {
    data class Station(val freq: Long, val levelDb: Float)

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** 扫 [from]..[to]（Hz），步进 [step]。必须在引擎启动前/独立线程调用。 */
    fun scan(from: Long, to: Long, step: Long = 100_000, thresholdDb: Float = -55f) {
        cancelled = false
        Thread({
            val results = ArrayList<Station>()
            val oldFreq = engine.tunedFreq
            val oldSquelch = engine.squelchDb
            val oldMode = engine.mode
            engine.muted = true  // 扫描静音（防跳频爆音）
            try {
                engine.selectMode(SdrEngine.Mode.NFM)  // 窄带测信号更准
                engine.squelchDb = -120f               // 静噪全开防丢样本
                val total = ((to - from) / step).toInt()
                var idx = 0
                var prev1 = -120f
                var prev2 = -120f
                var prevFreq = from - step
                var f = from
                while (f <= to && !cancelled) {
                    engine.tuneWithinSpan(f)
                    Thread.sleep(90)  // 等 DSP 稳定 + 取 2 个能量统计块
                    val db = engine.signalDb
                    // 局部极大：中间点比两侧都高 + 超阈值
                    if (prev2 < prev1 && prev1 > db && prev1 > thresholdDb) {
                        results.add(Station(prevFreq, prev1))
                        onProgress((idx * 100 / total).coerceIn(0, 100), results.size)
                    }
                    prev2 = prev1
                    prev1 = db
                    prevFreq = f
                    f += step
                    idx++
                }
                // 边界：最后一点
                if (prev1 > prev2 && prev1 > thresholdDb) {
                    results.add(Station(prevFreq, prev1))
                }
            } catch (_: InterruptedException) {
            } finally {
                // 恢复
                engine.muted = false
                engine.selectMode(oldMode)
                engine.squelchDb = oldSquelch
                engine.tuneWithinSpan(oldFreq)
                onDone(results)
            }
        }, "FmScan").apply { isDaemon = true }.start()
    }
}
