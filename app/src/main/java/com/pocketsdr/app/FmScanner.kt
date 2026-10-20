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
 *
 * 数据有效性是这个类的生命线：扫出来的每个台都是「某个频率上的能量」，
 * 而能量必须对应**真的调到了那个频率**。硬件调谐是会失败的（I2C 抖动、
 * 供电不足），以前调谐返回值被丢弃，于是失败时读到的是上一个频点的能量——
 * 用户拿到的是一份频点错位、电平虚假的电台列表，而且毫无提示。
 * 现在每次调谐后都检查 [SdrEngine.lastTuneOk]，失败的那一点直接丢弃并计数。
 */
class FmScanner(
    private val engine: SdrEngine,
    private val onProgress: (percent: Int, found: Int) -> Unit,
    private val onDone: (stations: List<Station>) -> Unit
) {
    data class Station(val freq: Long, val levelDb: Float)

    /** 本次扫描实际使用的阈值与噪声底（UI 可显示，便于真机判断灵敏度） */
    @Volatile var usedThresholdDb: Float = -55f
        private set
    @Volatile var noiseFloorDb: Float = -120f
        private set

    /** 本次扫描中有多少个频点因为调谐失败被丢弃（>0 说明硬件链路不稳） */
    @Volatile var tuneFailures: Int = 0
        private set

    /** 扫描是否被用户取消（区别于「正常扫完」） */
    @Volatile var wasCancelled: Boolean = false
        private set

    /**
     * 扫描是否因异常/硬件不通而失败。
     *
     * 与「取消」区分开：取消是用户主动的，失败是出问题的。
     * UI 在 onDone 回调里读这个标志，把原因告诉用户。
     */
    @Volatile var scanFailed: Boolean = false
        private set

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /**
     * 扫 [from]..[to]（Hz），步进 [step]。
     * [thresholdDb] 传 null 时自适应：先粗测噪声底，再以「噪声底 + 余量」为门限。
     * 真机噪声底与增益跟仿真完全不同，固定阈值会一个台都搜不到（或全是台）。
     */
    @JvmOverloads
    fun scan(
        from: Long,
        to: Long,
        step: Long = 100_000,
        thresholdDb: Float? = null,
        measureMode: SdrEngine.Mode = SdrEngine.Mode.NFM
    ) {
        cancelled = false
        wasCancelled = false
        scanFailed = false
        tuneFailures = 0
        Thread({
            val results = ArrayList<Station>()
            val oldFreq = engine.tunedFreq
            val oldSquelch = engine.squelchDb
            val oldMode = engine.mode
            // 静音要快照恢复：原来无条件在 finally 里置 false，
            // 用户自己按了静音的话，扫完台就被强制解除静音了——
            // 声音状态和界面上的静音开关对不上。
            val oldMuted = engine.muted
            engine.muted = true  // 扫描静音（防跳频爆音）
            try {
                // 测量模式按波段选：FM 广播用窄带更准，航空对讲是 AM，
                // 对讲机才是 NFM。以前写死 NFM，扫航空频段会一个台都搜不到。
                engine.selectMode(measureMode)
                // 模式切换是异步的（DSP 线程块头应用），不等待的话第一个
                // 测到的能量还是旧模式的，读数没有意义。
                Thread.sleep(150)
                engine.squelchDb = -120f               // 静噪全开防丢样本

                // ---- 噪声底粗测：整个波段均匀取 12 个点取中位数 ----
                val probeN = 12
                val probes = FloatArray(probeN)
                for (i in 0 until probeN) {
                    if (cancelled) break
                    val f = from + (to - from) * i / probeN
                    engine.tuneWithinSpan(f)
                    Thread.sleep(70)
                    if (!engine.lastTuneOk) {
                        // 调谐没成：这个点的能量属于别的频率，不能进噪声底统计
                        tuneFailures++
                        probes[i] = Float.NaN
                        continue
                    }
                    probes[i] = engine.signalDb
                }
                // 只用调谐成功的点算底噪；一个都没成功就说明链路根本不通
                val valid = probes.filter { !it.isNaN() }.sorted()
                if (valid.isEmpty()) {
                    scanFailed = true
                    SdrLog.e("SCAN", "所有探测点调谐都失败，扫描无法进行")
                    return@Thread
                }
                // 取 25% 分位而非中位数：有强台时中位数会被拉高，分位更接近真实底噪
                val floor = valid[(valid.size / 4).coerceIn(0, valid.size - 1)]
                noiseFloorDb = floor
                // 余量 8dB：足以跳过底噪起伏，又不至于漏掉中等强度的台
                val thr = thresholdDb ?: (floor + 8f)
                usedThresholdDb = thr
                SdrLog.i("SCAN", "噪声底 ${"%.1f".format(floor)}dB → 门限 ${"%.1f".format(thr)}dB")

                val total = ((to - from) / step).toInt().coerceAtLeast(1)
                var idx = 0
                var prev1 = -120f
                var prev2 = -120f
                var prevFreq = from - step
                var f = from
                // 进度必须与「找到几个台」解耦。
                // 以前只在发现局部极大时才上报进度：扫 206 个点若只找到 3 个台，
                // 进度条会从 0 直接跳到 15%，其余时间纹丝不动——看着像卡死了。
                // 这里每前进约 2% 就报一次，扫到哪儿一目了然。
                var lastPct = -1
                while (f <= to && !cancelled) {
                    engine.tuneWithinSpan(f)
                    Thread.sleep(90)  // 等 DSP 稳定 + 取 2 个能量统计块
                    if (!engine.lastTuneOk) {
                        // 调谐失败：这个频点的能量不属于这个频率，跳过。
                        // 以前照样采信，于是搜出来的是「频点错位、电平虚假」的台。
                        tuneFailures++
                        // 同时把前一点作废，避免用旧能量做局部极大判断
                        prev1 = -120f
                        prev2 = -120f
                        f += step
                        idx++
                        continue
                    }
                    val db = engine.signalDb
                    // 局部极大：中间点比两侧都高 + 超阈值
                    if (prev2 < prev1 && prev1 > db && prev1 > thr) {
                        results.add(Station(prevFreq, prev1))
                        onProgress((idx * 100 / total).coerceIn(0, 100), results.size)
                    }
                    val pct = (idx * 100 / total).coerceIn(0, 100)
                    if (pct > lastPct && pct - lastPct >= 2) {
                        lastPct = pct
                        onProgress(pct, results.size)
                    }
                    prev2 = prev1
                    prev1 = db
                    prevFreq = f
                    f += step
                    idx++
                }
                // 边界：最后一点
                if (prev1 > prev2 && prev1 > thr) {
                    results.add(Station(prevFreq, prev1))
                }
                if (cancelled) wasCancelled = true
                else onProgress(100, results.size)
            } catch (_: InterruptedException) {
                // 中断=取消。必须记成取消，不能当成扫完——
                // 否则用户看到「扫描完成，找到 N 个台」，
                // 而实际只扫了频段的一小部分，剩下的会被误当成「没有台」。
                wasCancelled = true
            } catch (e: Exception) {
                scanFailed = true
                SdrLog.e("SCAN", "扫描异常: ${e.message?.take(50)}")
            } finally {
                // 恢复
                engine.muted = oldMuted
                engine.selectMode(oldMode)
                engine.squelchDb = oldSquelch
                engine.tuneWithinSpan(oldFreq)
                onDone(results)
            }
        }, "FmScan").apply { isDaemon = true }.start()
    }
}
