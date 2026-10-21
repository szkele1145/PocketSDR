package com.pocketsdr.app

import kotlin.math.abs

/**
 * 增益自动寻优。
 *
 * ## 为什么需要它
 *
 * RTL-SDR 的 LNA 增益是一把双刃剑：
 *
 * - **太低**：弱信号淹没在 ADC 量化噪声里，听不到远处的台
 * - **太高**：前端过载，强信号互相调制（互调产物铺满频谱），
 *   更要命的是 ADC 削顶——波形顶部被切平，解调出来全是失真噪声
 *
 * 老手的做法是「一档一档往上推，听到开始变糊就退一档」。这件事完全可以自动化：
 * 每一档量一下噪声底和峰值，峰值贴着天花板的就是过载档，往前退到「不削顶
 * 且信噪比最高」的那一档。
 *
 * ## 判据
 *
 * 对每一档记录：
 * - `floorDb`：噪声底（25% 分位数），越低说明底噪越干净
 * - `peakDb`：谱峰，太高说明前端已经压不住强信号了
 *
 * 评分用 **SNR = peak − floor**，但要排除过载档。
 *
 * ### 过载怎么判（v2.75.0 改过）
 *
 * 原来是「谱峰 ≥ -3dBFS 即为削顶档」。**这条判据其实测不到东西**：
 * FM/AM 的能量分散在几百个 bin 上，单 bin 幅度远低于总幅度。
 * 用仿真源跑完 23 档实测（`tools/simcheck`），过载最严重的一档谱峰也只到
 * -7.7dB，离 -3dBFS 还差 4.7dB——**永远不会触发**，于是寻优会把过载档
 * 当成最优档推荐给用户。
 *
 * 真正可靠的过载证据是**底噪被抬起来**：ADC 削顶产生的失真互调产物
 * 铺满整个频谱。实测同一组数据：
 *
 * | 增益段 | 噪声底 | 谱峰 | SNR |
 * |---|---|---|---|
 * | -9.9…-5.4 dB | -75dB | -38dB | 37~38 |
 * | 5.8…7.1 dB   | -55dB | -15dB | 38~**41** |
 * | 17.9…19.7 dB | -34dB | -9dB  | **22~33** |
 *
 * 高增益段底噪抬了 21dB、SNR 从 41 掉到 22——**SNR 掉头向下**才是过载
 * 的可靠信号。于是判据改成：
 *
 * - 主判据：SNR 比已见的最高值低 [SNR_DROP_DB]（3dB）以上 → 该档过载
 * - 辅判据：谱峰 ≥ [CLIP_DB]（-3dBFS）→ 该档过载（留着兜底，
 *   万一某个源的信号确实集中在一个 bin 上）
 *
 * 安全区里取 SNR 最高的那档。实测（仿真源全档扫描）推荐落在 6.5dB，
 * 正是「刚好把弱台抬出噪声、又还没让强台过载」的位置。
 *
 * ## 线程模型
 *
 * 与 [FmScanner] 一致：跑在自己的线程里，通过 [SdrEngine] 的公开 API 操作硬件，
 * 每档之间 sleep 让 DSP 的 250ms 统计窗口刷新。UI 在 `onDone` 回调里读结果。
 *
 * 全程不碰 DSP 内部状态，只调 `setHardwareGainMode` / `setHardwareLnaGain`，
 * 所以不会和 DSP 线程打架。
 */
class GainScanner(
    private val engine: SdrEngine,
    private val onProgress: (done: Int, total: Int) -> Unit,
    private val onDone: (result: Result) -> Unit
) {
    companion object {
        /**
         * 谱峰绝对判据：单 bin 到 -3dBFS 就认为 ADC 已经在切顶。
         *
         * 但这条判据**单独用不够**——FM/AM 的能量分散在几百个 bin 上，
         * 单 bin 幅度远低于总幅度，真机过载时谱峰往往只到 -10dB 上下，
         * 根本够不到 -3dBFS。实测（仿真源全档位扫描）里最坏的一档也只有 -7.7dB。
         *
         * 真正可靠的过载证据是**底噪被抬起来**：削顶产生的失真互调产物
         * 铺满整个频谱，把噪声底从 -55dB 抬到 -34dB。
         * 所以过载判定以 [SNR_DROP_DB] 为主，[CLIP_DB] 只作补充。
         */
        private const val CLIP_DB = -3f

        /**
         * SNR 回落判据：比已见最高 SNR 低这么多，就认为已经过载。
         *
         * 3 dB 是「听得出来变糊」的量级：增益再往上加，
         * 失真互调的增速远快于信号增益，SNR 会掉头向下。
         * 找到掉头的那一档，它前面一档就是推荐值。
         */
        private const val SNR_DROP_DB = 3f

        /**
         * 每档等待时间。
         *
         * 引擎的噪声底统计每 8 帧一次（16ms/帧 → 128ms 一轮），
         * 而 [SdrEngine.resetNoiseStats] 之后还有 4 帧要跳过平滑，
         * 那 4 帧正是「纯属于本档」的数据。要等它们跑完才读，
         * 否则读到的还是上一档的残留（底噪用 0.7/0.3 平滑，混在一起
         * 用来比较哪一档更好会得出错误结论）。
         *
         * 128ms × 4 ≈ 512ms，取 550ms 留余量。24 档共约 13 秒。
         */
        private const val SETTLE_MS = 550L
    }

    /** 单档测量结果 */
    data class Step(
        val gainTenthDb: Int,
        val floorDb: Float,
        val peakDb: Float,
        /** 该档是否已过载（削顶或 SNR 回落） */
        val clipped: Boolean
    ) {
        /** 信噪比：这是真正决定「能不能听清」的量 */
        val snrDb: Float get() = peakDb - floorDb
    }

    /**
     * 扫描结论。
     *
     * @param best 推荐档位（null = 全部削顶，硬件增益过高或前端有强干扰）
     * @param steps 全部测量值，UI 可展示成一张表让用户自己判断
     * @param wasCancelled 用户中途取消
     * @param failed 硬件不接受增益设置，或全程读不到有效数据
     * @param note 失败/异常时的可读原因
     */
    data class Result(
        val best: Step?,
        val steps: List<Step>,
        val wasCancelled: Boolean = false,
        val failed: Boolean = false,
        val note: String? = null
    )

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun start(): Thread {
        val t = Thread({ run() }, "GainScan")
        t.isDaemon = true
        t.start()
        return t
    }

    private fun run() {
        val gains = engine.lnaGains
        val total = gains.size
        val steps = ArrayList<Step>(total)
        var failed = false
        var note: String? = null

        val oldManual = engine.hardwareManualGain
        val oldGain = engine.hardwareLnaGain

        // 扫描期间静音 + 暂停录音：
        // - 静音是因为增益会被一路推高，用户会听到越来越响最后过载的声音
        // - 暂停录音是因为扫的是增益台阶，录下来是「呼吸声」而不是正常节目
        val oldMuted = engine.muted
        engine.muted = true
        val wasRecording = engine.isRecording && !engine.recordingPaused
        if (wasRecording) engine.pauseRecording()

        try {
            // 必须切到手动增益：自动增益下改单档毫无意义，AGC 会把它拉回去
            if (!engine.setHardwareGainMode(true)) {
                failed = true
                note = "硬件不接受手动增益切换（检查 USB 连接）"
                return
            }

            for (i in 0 until total) {
                if (cancelled) break
                val g = gains[i]
                if (!engine.setHardwareLnaGain(g)) {
                    // 单档失败不算整体失败：跳过它继续，最后看是否一档都没成
                    SdrLog.w("GAIN", "第 $i 档（${g / 10f}dB）设置未被硬件接受，跳过")
                    onProgress(i + 1, total)
                    continue
                }
                // 丢掉上一档残留的统计值：不清的话读到的是两个档位的混合，
                // 用来比较哪一档更好会得出错误结论。
                engine.resetNoiseStats()
                try {
                    Thread.sleep(SETTLE_MS)
                } catch (_: InterruptedException) {
                    cancelled = true
                    break
                }
                val floor = engine.noiseFloorDb
                val peak = engine.spectrumPeakDb
                // 数据完全无效的档位不入表：
                // - 非有限数（NaN/Inf）说明统计还没跑起来
                // - peak 必须高于 floor，否则这两个数不是同一份统计的结果
                //   （比如还在用引擎的初始占位值 -85/-30）
                if (floor.isFinite() && peak.isFinite() && floor < 0f && peak <= 0f && peak > floor) {
                    // clipped 先占位 false，等全部档扫完、知道最高 SNR 之后再回填
                    steps.add(Step(g, floor, peak, clipped = false))
                }
                onProgress(i + 1, total)
            }

            if (steps.isEmpty()) {
                failed = true
                note = "所有增益档都没读到有效数据——检查 USB 连接和数据流"
                return
            }

            // 过载判定：主判据是 SNR 回落，辅以谱峰绝对阈值。
            //
            // 为什么不能只看谱峰：FM/AM 能量分散在几百个 bin 上，
            // 单 bin 幅度远低于总幅度。实测仿真源全档位扫描，
            // 过载档的谱峰也只到 -7.7dB，够不到 -3dBFS 的绝对线。
            // 而削顶的失真互调产物会铺满全谱、把噪声底抬起来，
            // 于是 SNR（peak - floor）会明显掉头向下——这个信号才可靠。
            val maxSnr = steps.maxOf { it.snrDb }
            val marked = steps.map { s ->
                if (s.peakDb >= CLIP_DB || s.snrDb < maxSnr - SNR_DROP_DB) {
                    s.copy(clipped = true)
                } else {
                    s
                }
            }
            val stepsOut = marked

            // 安全区：没被判过载、且 SNR 距最高值不足 3dB 的档位。
            // 不用「第一个过载档之前」那种顺序规则——SNR 回落判据是逐档算的，
            // 而测量噪声可能让个别档抖动，顺序规则会把好档位一起砍掉。
            val usable = stepsOut.filter { !it.clipped }
            if (usable.isEmpty()) {
                // 连最低档都削顶：前端有极强的信号（或天线太近强发射源），
                // 不是软件能解决的，如实告诉用户
                failed = true
                note = "连最低增益档都过载（峰值 ≥ -3dB）——天线离强发射源太近，或需要加衰减器"
                return
            }
            // 安全区里 SNR 最高的那档。同分取更高增益（更能救弱信号）
            val best = usable.maxWithOrNull(compareBy({ it.snrDb }, { it.gainTenthDb }))
            onDone(Result(best = best, steps = stepsOut, wasCancelled = cancelled))
        } catch (e: Exception) {
            SdrLog.e("GAIN", "增益扫描异常：${e.message}")
            onDone(Result(best = null, steps = steps, wasCancelled = cancelled,
                failed = true, note = "扫描异常：${e.message?.take(60) ?: "未知错误"}"))
        } finally {
            // 恢复用户原来的设置：扫描是「量一下」，不该把用户的偏好改掉。
            // 扫描过程本身会把增益一路推到最高，直接留着会让用户听到过载的声音。
            // 是否采用新档位交给 UI 询问用户，不在这里替他拍板。
            //
            // 只有「原本就是手动模式」时才需要恢复具体档位——自动模式下 tuner 会
            // 自己管增益，恢复一个可能不准的档位反而添乱。
            if (oldManual) {
                engine.setHardwareLnaGain(oldGain)
            }
            engine.setHardwareGainMode(oldManual)
            // 静音与录音恢复：与保存的偏好一一对应
            engine.muted = oldMuted
            if (wasRecording) engine.resumeRecording()
        }
    }

    /** 用于 UI 展示：把 tenths dB 换成一位小数 */
    fun formatGain(tenthDb: Int): String = "%.1fdB".format(tenthDb / 10f)

    /** 两个档位间的差距（tenths dB 的绝对值） */
    fun gainDelta(a: Int, b: Int): Int = abs(a - b)
}
