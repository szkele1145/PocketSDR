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
    /**
     * 搜到的一个台。
     *
     * @param freq 频率（Hz）。经过 [refine] 精化后是台的真实载频，
     *   而不是粗扫的网格点。
     * @param levelDb 信号强度（dBFS）
     * @param refined 是否经过频率精化。UI 用它标注「这个频率是细扫出来的」，
     *   免得用户以为还是原来的网格值。
     */
    /** 本次扫描用的测量模式（refine 读台名时切到 WFM，读完要切回它） */
    private var measureModeForRefine: SdrEngine.Mode = SdrEngine.Mode.NFM

    data class Station(
        val freq: Long,
        val levelDb: Float,
        val refined: Boolean = false,
        /** 扫到时顺手读到的 RDS 台名；读不到就是空串 */
        val name: String = ""
    )

    companion object {
        /**
         * 精化时每点的等待时间。
         *
         * 比粗扫的 90ms 略长：精化要比较相邻点的细微差别，
         * DSP 统计没稳就采，读到的差异可能是噪声波动而不是频率偏差。
         */
        private const val REFINE_SETTLE_MS = 90L
        /**
         * 每个找到的台最多花多久等 RDS 台名。
         *
         * 电台名分 4 段发，真机通常 1~2 秒凑齐；这里给 1.6 秒上限，
         * 读到就提前跳出。10 个台的波段最多多花 16 秒，比扫完再逐个
         * 回头调谐一遍便宜得多。
         */
        private const val NAME_DWELL_MS = 1600
        /**
         * 切模式后等 DSP 线程真正换过去 + RDS 重新锁定。
         *
         * selectMode 只是入队，真正切换发生在 DSP 线程块头，块大小 ~50ms；
         * 再加上滤波器/鉴频器重建，留 180ms 比较稳。
         */
        private const val MODE_SWITCH_SETTLE_MS = 180L
    }

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
     * 频率精化：在网格点附近细扫，找到能量最高点作为台的真实频率。
     *
     * ## 为什么需要
     *
     * 粗扫的步进是 100 kHz（FM 广播），电台的真实载频并不落在网格点上——
     * 98.1 MHz 这个台可能实际在 98.043 MHz。直接用网格频率收听，
     * 就有几十 kHz 的偏差：
     *
     * - WFM 带宽 200 kHz，还能听到，但 RDS 台名可能解不出来
     * - 航空 AM 带宽只有 25 kHz，偏 50 kHz 就**完全收不到**
     *
     * ## 做法
     *
     * 以网格点为中心，在 ±step/2 区间内按 step/20 的细粒度扫一遍，
     * 取能量最大的那一点。FM 广播下精度从 ±50 kHz 提升到 ±2.5 kHz。
     *
     * 代价是每个台多花 21 × 90ms ≈ 1.9 秒。搜到 20 个台就是 38 秒——
     * 换来的是结果能直接用来收听的频率，值得。
     *
     * 调谐失败或被取消时返回原网格点，不让精化失败毁掉已经找到的台。
     */
    private fun refine(gridFreq: Long, gridLevel: Float, step: Long): Station {
        if (cancelled) return Station(gridFreq, gridLevel)
        // 步进太小就没必要精化（已经足够准）
        if (step <= 20_000L) return Station(gridFreq, gridLevel)
        val fineStep = step / 20
        if (fineStep <= 0L) return Station(gridFreq, gridLevel)
        val lo = gridFreq - step / 2
        val hi = gridFreq + step / 2
        var bestF = gridFreq
        var bestDb = gridLevel
        var f = lo
        while (f <= hi) {
            if (cancelled) break
            engine.tuneWithinSpan(f)
            try {
                Thread.sleep(REFINE_SETTLE_MS)
            } catch (_: InterruptedException) {
                cancelled = true
                break
            }
            if (!engine.lastTuneOk) {
                // 精化过程中的调谐失败：跳过这个点，不让它影响最佳值
                tuneFailures++
                f += fineStep
                continue
            }
            val db = engine.signalDb
            if (db > bestDb) {
                bestDb = db
                bestF = f
            }
            f += fineStep
        }
        if (bestF != gridFreq) {
            SdrLog.i("SCAN", "频率精化 ${gridFreq / 1000}kHz → ${bestF / 1000}kHz")
        }

        // 顺手读一下 RDS 台名。
        //
        // 此刻引擎已经停在最佳频点上，只要多停一会儿等 RDS 锁定就行——不用
        // 扫完之后再回头逐个调谐一遍。电台名分 4 段发，真机通常 1~2 秒凑齐，
        // 所以最多等 NAME_DWELL_MS，读到就提前跳出（弱台不拖慢整轮扫描）。
        //
        // 扫出来的列表里只有频率的话，用户还得一个个记住「98.3 是音乐台」；
        // 带上台名之后一眼就能挑。
        var stationName = ""
        if (!cancelled) {
            // **RDS 只在 WFM 下解码**（解码器接在调频鉴频输出上），
            // 而扫描期间引擎是测量模式（FM 用 NFM 测电平更准）。
            // 不切过来的话，engine.rds.stationName 永远是空的——读台名
            // 会静默地一直失败，而且看不出原因。
            engine.selectMode(SdrEngine.Mode.WFM)
            try {
                Thread.sleep(MODE_SWITCH_SETTLE_MS)
            } catch (_: InterruptedException) {
                cancelled = true
            }
            // **必须先调回最佳频点**。细扫循环结束时引擎停在 hi
            // （gridFreq + step/2，FM 上是偏 50kHz），在那儿读 RDS 等于
            // 在邻频上等台名——白白等满 1.6 秒还读不到。
            if (bestF != gridFreq) {
                engine.tuneWithinSpan(bestF)
                try {
                    Thread.sleep(REFINE_SETTLE_MS)
                } catch (_: InterruptedException) {
                    cancelled = true
                }
            }
            var waited = 0
            while (waited < NAME_DWELL_MS && !cancelled) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    cancelled = true
                    break
                }
                waited += 100
                val nm = engine.rds?.stationName?.trim().orEmpty()
                // 少于 4 个字符的名字多半是刚解出一段半段的残名，继续等
                if (nm.length >= 4) {
                    stationName = nm
                    break
                }
            }
            if (stationName.isNotEmpty()) {
                SdrLog.i("SCAN", "读电台名 ${bestF / 1000}kHz → $stationName")
            }
            if (!cancelled) {
                engine.selectMode(measureModeForRefine)
                try {
                    Thread.sleep(MODE_SWITCH_SETTLE_MS)
                } catch (_: InterruptedException) {
                    cancelled = true
                }
            }
        }
        // 必须回到网格点：精化把引擎停在了 bestF，而粗扫的游标还在等下一个网格点。
        // 不回去的话，下一次测量读到的是 bestF 的能量，会把它当成下一个网格点的值——
        // 这正是「搜出来的台频点错位」的老毛病。
        if (!cancelled) {
            engine.tuneWithinSpan(gridFreq)
            try {
                Thread.sleep(REFINE_SETTLE_MS)
            } catch (_: InterruptedException) {
                cancelled = true
            }
        }
        return Station(bestF, bestDb, refined = true, name = stationName)
    }

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
            // 录音必须暂停：扫描要跳频、切模式、开静噪，
            // 继续录的话录出来的是几十秒的扫频噪声和跳变——
            // 而用户以为录的是他正在听的那个台。
            // 扫描前录着就暂停，扫完恢复；本来就没录就不动。
            val wasRecording = engine.isRecording && !engine.recordingPaused
            if (wasRecording) engine.pauseRecording()
            try {
                // 测量模式按波段选：FM 广播用窄带更准，航空对讲是 AM，
                // 对讲机才是 NFM。以前写死 NFM，扫航空频段会一个台都搜不到。
                engine.selectMode(measureMode)
                measureModeForRefine = measureMode
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
                        val st = refine(prevFreq, prev1, step)
                        results.add(st)
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
                    results.add(refine(prevFreq, prev1, step))
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
                // 扫描前在录就接着录（内容从扫描结束这一刻继续）
                if (wasRecording) engine.resumeRecording()
                engine.tuneWithinSpan(oldFreq)
                onDone(results)
            }
        }, "FmScan").apply { isDaemon = true }.start()
    }
}
