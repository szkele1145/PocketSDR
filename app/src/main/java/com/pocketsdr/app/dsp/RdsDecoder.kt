package com.pocketsdr.app.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * RDS 解码器（IEC 62106）。
 *
 * 输入：MPX 复用信号（鉴频后），需 ≥256ksps（57kHz 载波奈奎斯特）。
 *
 * 级联：57k Costas 下变频 → 两级 2.8k 低通 → Gardner 位同步 →
 * 差分双相解码 → 26 位块 CRC 锁定（A/B/C/D 序列）→ group 0A PS 电台名。
 */
class RdsDecoder(private val fs: Float) {

    // ===== 57k Costas =====
    private var vcoPhase = 0.0
    private val vcoInc = 2.0 * Math.PI * 57000 / fs
    private val loopLp = Biquad.Lowpass(800f, fs)
    private var loopInteg = 0.0

    // ===== 基带：两级 2.8k 级联 =====
    // 单级 2.4k 对 57k 残余镜像抑制不够，位流底噪偏高（真机表现为锁不住台名）；
    // 与 DspVerify 里验证通过的链路保持一致：两级 2.8k、Q=0.7071。
    private val dataLp = Biquad.Lowpass(2800f, fs)
    private val dataLp2 = Biquad.Lowpass(2800f, fs)

    // ===== 位同步：固定 1187.5 Hz 时钟 + 多相位并行判决 =====
    //
    // 为什么不用 Gardner 环：Gardner TED 的误差项 (cur − prevSym) × midSample
    // 在**曼彻斯特码上退化成常量**——位中点必有跳变，midSample 的符号不携带
    // 相位信息，误差项恒为正，环路收不到任何相位反馈，判决点会随机漂移。
    //
    // 为什么必须多相位并行：RDS 的判决点必须落在**位边界**上，而位边界的
    // 位置相对于解码器自己的时钟是**未知的**（真机上取决于电台帧相位）。
    // 实测判决眼图只有 ±0.015 bit（≈±3 个采样）宽，相位差几个采样 CRC 就全灭。
    // 于是并行维护 PHASE_COUNT 个候选流，相位铺满整个 bit 周期，
    // 各自独立跑 26 位块 + CRC——**CRC 通过的那个就是对的**。
    //
    // 位时钟频率不用估计：RDS 规范就是精确 1187.5 bps（IEC 62106），
    // 直接用固定频率的分数累加器即可，判决点不会相对信号漂移。
    private val spb = fs / 1187.5f                  // 每 bit 采样数（256k 时 215.4667）
    private var nextBitTime = 0.0                   // 下一次判决的绝对时间（采样数，小数）
    private var sampleIdx = 0                       // 已处理样本计数
    private val baseRing = FloatArray(BUF_SIZE)     // 基带环形缓冲
    /** 各候选相位的位流状态机 */
    private val streams = Array(PHASE_COUNT) { BlockStream() }
    /** 各候选相位上一个判决点的绝对采样位置（差分判决用） */
    private val prevDecisionAt = IntArray(PHASE_COUNT) { -1 }
    /** 候选相位：铺满一个 bit 周期 */
    private val phaseOffsets = IntArray(PHASE_COUNT) {
        (it * spb / PHASE_COUNT).toInt()
    }

    // ===== 位/块 =====
    @Volatile var stationName = ""
        private set
    @Volatile var dataLock = false
        private set

    /**
     * 诊断信息：目前有多少个 26 位块通过了 CRC。
     *
     * 0 说明这一路信号里压根没找到能通过校验的块（电台没发 RDS、
     * 或者 Costas/判决链路有问题）；>0 说明链路是通的，只是还没凑齐
     * 8 个字符的 PS 段。界面上用它区分「没收到 RDS」和「正在读台名」。
     */
    @Volatile var crcBlocksOk: Int = 0
        private set

    /** 最佳候选相位的索引（用于诊断显示，-1 = 还没找到能过 CRC 的） */
    @Volatile var bestPhaseIdx: Int = -1
        private set

    /**
     * 已确认的 PS 段数（0~4）。8 个字符的台名分 4 段，每段 2 个字符。
     * 用来在界面上显示「台名进度」，比如 2/4 表示收到了一半。
     */
    @Volatile var psSegments: Int = 0
        private set

    /**
     * RadioText（滚动文本，最长 64 字符，来自 group 2A）。
     *
     * 真机上这比台名更有用：台名只有 8 个字符且不变，RT 常常是
     * 「正在播放：xxx」这种实时信息。
     */
    @Volatile var radioText: String = ""
        private set

    /** 已确认的 RT 段数（0~16，每段 4 字符） */
    @Volatile var rtSegments: Int = 0
        private set

    /**
     * 节目类型（PTY，5 bit，来自 B 块 bit 9-5）。
     *
     * -1 = 还没收到；否则是 IEC 62106 的 PTY 码（0~31）。
     */
    @Volatile var pty: Int = -1
        private set

    /**
     * 已锁定的 PI 码（电台识别码，0~0xFFFF）；-1 = 还没锁定。
     *
     * 真机调试时这是**最关键的一个数**：只要 PI 出现了，就说明
     * Costas → 位同步 → CRC 这条链路是通的，剩下的只是等段凑齐。
     */
    @Volatile var piCode: Int = -1
        private set
    @Volatile var trafficProgram: Boolean = false
        private set
    @Volatile var trafficAnnouncement: Boolean = false
        private set

    /**
     * 收到的组类型分布，形如 `0A×12 2A×8`。
     *
     * 真机上用它判断「这个台到底发了什么」：只发 0A 说明没有 RT；
     * 出现 4A 说明带交通信息；出现 15A 说明有 Long PS。
     */
    @Volatile var groupTypesSummary: String = ""
        private set

    /**
     * 一条候选位流：位累加 → 26 位块 → CRC → group → PS 字符。
     *
     * 每个候选相位一条，各自独立跑 CRC；通过校验的候选才会往上写台名。
     */
    private class BlockStream {
        var blockReg = 0L
        var blockBits = 0
        var expectBlock = 0
        val blockData = IntArray(4)
        var blockIdx = 0
        val psChars = CharArray(8) { ' ' }
        val psSeen = BooleanArray(4)
        /** 该段字符组合的得票数（确认时记录，用来判断后续能否改写） */
        val psVotes = IntArray(4)
        /** 该段是否已通过多数表决确认（只有确认过的段才拿去显示） */
        val psConfirmed = BooleanArray(4)
        /** 每段的字符组合票数表（key = (c1<<8)|c2） */
        val psTally = Array(4) { HashMap<Int, Int>() }
        // ===== RadioText（group 2A）=====
        /** RT 字符缓冲（16 段 × 4 字符 = 64 字符） */
        val rtChars = CharArray(64) { ' ' }
        /** RT 每段是否收到过 */
        val rtSeen = BooleanArray(16)
        /** RT 每段收到的次数（用于多数表决） */
        val rtVotes = IntArray(16)
        /** RT 每段的字符组合票数表（key = 4 字符打包成 Int） */
        val rtTally = Array(16) { HashMap<Int, Int>() }
        /** RT 已确认的段数 */
        var rtSegments = 0
        /** RT 的 A/B 标志（-1 = 还没收到过），翻转表示文本内容换了 */
        var rtAbFlag = -1
        /**
         * 已锁定的 PI 码（-1 = 还没锁定）。
         *
         * 每个 RDS 组的 A 块都是同一个 PI，这是位流里最强的约束。
         * 锁定之后所有 A 块（正常路径和重同步路径）都必须与它一致，
         * 能把「滑窗凑巧通过 A 块 CRC」的假阳性挡在外面。
         */
        var piCode = -1
        /** PI 候选（连续两次相同才锁定，防止第一次 CRC 假阳性把 PI 学错） */
        var piCandidate = -1
        /** 最近一次收到的 PTY（B 块 bit 9-5），-1 = 还没收到 */
        var lastPty = -1
        var tp = false
        var ta = false
        /** 连续完成的 group 数——≥2 才认为可信（CRC 假阳性率 1/1024^4） */
        var groupsOk = 0
        /** CRC 通过的块数，用来在候选之间挑「最可信」的那条 */
        var goodBlocks = 0
        /** 收到的 group type 高 4 位（诊断用） */
        val groupTypes = IntArray(16)

        fun reset() {
            blockReg = 0L
            blockBits = 0
            expectBlock = 0
            blockIdx = 0
            blockData[0] = 0; blockData[1] = 0; blockData[2] = 0; blockData[3] = 0
        }

        /**
         * 清空**内容**（台名 / 滚动文本 / PTY / PI），保留位同步状态。
         *
         * 换台时必须调：否则上一個台的台名会一直挂着，等新台凑齐 4 段才被
         * 覆盖；更糟的是两个台的段会混着投票，拼出一个不存在的「混合台名」。
         */
        fun resetContent() {
            java.util.Arrays.fill(psChars, ' ')
            java.util.Arrays.fill(psConfirmed, false)
            java.util.Arrays.fill(psSeen, false)
            java.util.Arrays.fill(psVotes, 0)
            for (m in psTally) m.clear()
            java.util.Arrays.fill(rtChars, ' ')
            java.util.Arrays.fill(rtSeen, false)
            java.util.Arrays.fill(rtVotes, 0)
            for (m in rtTally) m.clear()
            rtSegments = 0
            rtAbFlag = -1
            piCode = -1
            piCandidate = -1
            lastPty = -1
            tp = false
            ta = false
            groupsOk = 0
            java.util.Arrays.fill(groupTypes, 0)
        }
    }

    /**
     * 清空全部 RDS 状态。
     *
     * **换台时必须调用**。否则：
     *  - 上一个台的台名会一直挂在界面上，直到新台凑齐 4 段才被覆盖（1~2 秒
     *    的错误信息）；
     *  - 更糟的是两个台的段会混在同一个投票表里，可能拼出一个**不存在的
     *    混合台名**——用户会以为收到了一个奇怪的台。
     *
     * 扫描读台名时尤其关键：每个频点都要从零开始等，不能拿上一个台的结果。
     */
    fun resetAll() {
        stationName = ""
        radioText = ""
        pty = -1
        piCode = -1
        trafficProgram = false
        trafficAnnouncement = false
        groupTypesSummary = ""
        crcBlocksOk = 0
        bestPhaseIdx = -1
        psSegments = 0
        rtSegments = 0
        dataLock = false
        for (s in streams) {
            s.reset()
            s.resetContent()
        }
        java.util.Arrays.fill(prevDecisionAt, -1)
    }

    fun process(mpx: Float) {
        // 57k Costas（极性修正：负反馈，经 DspVerify 验证）
        vcoPhase += vcoInc + loopInteg
        if (vcoPhase > TWO_PI) vcoPhase -= TWO_PI
        val c = cos(vcoPhase).toFloat()
        val s = sin(vcoPhase).toFloat()
        val iBr = mpx * c
        val qBr = mpx * s
        val err = loopLp.process(iBr * qBr)
        loopInteg = loopInteg * 0.99999 - err * 2e-6
        // 两级 2.8k 级联低通（与 DspVerify 验证链路一致）
        val base = dataLp2.process(dataLp.process(iBr))
        baseRing[sampleIdx % BUF_SIZE] = base
        sampleIdx++

        // 固定 1187.5 Hz 位时钟：每 bit 精确 spb 个采样。
        // nextBitTime 用小数累加，判决点不会相对信号漂移。
        while (nextBitTime < sampleIdx) {
            val bitIdx = nextBitTime.toInt()
            // 第 k 个 bit 的判决点 = 候选相位 ph 对应的采样位置
            for (ph in 0 until PHASE_COUNT) {
                val at = bitIdx + phaseOffsets[ph]
                if (at < 1) continue
                val cur = baseAt(at)
                // 差分判决：与**前一个 bit 的同相位判决点**比符号（反号 = 1）。
                // 必须用同一个候选相位的上一个判决点，不能拿 at - round(spb) 顶替——
                // 那是整数近似，每 bit 累积 0.47 个采样的误差，100 bit 后就偏了
                // 0.22 bit，真机有噪声时裕量会被吃光。
                val prevAt = prevDecisionAt[ph]
                if (prevAt < 0) { prevDecisionAt[ph] = at; continue }
                val prevVal = baseAt(prevAt)
                prevDecisionAt[ph] = at
                val bit = if (cur * prevVal < 0f) 1 else 0
                pushBit(streams[ph], bit)
            }
            nextBitTime += spb
        }
    }

    /** 读环形缓冲里的基带采样（绝对索引） */
    private fun baseAt(absIdx: Int): Float {
        val i = absIdx % BUF_SIZE
        return if (i < 0) baseRing[i + BUF_SIZE] else baseRing[i]
    }

    /**
     * 把一个判决位推进候选流：攒够 26 位就校验 CRC。
     *
     * 每个候选相位一条流，各自独立跑 CRC；通过校验的候选才会往上写台名。
     */
    private fun pushBit(s: BlockStream, bit: Int) {
        s.blockReg = (s.blockReg shl 1) or bit.toLong()
        if (s.blockBits < 26) s.blockBits++
        if (s.blockBits < 26) return
        // 滑窗时 blockReg 已经超过 26 位（失败后继续左移），
        // 必须先掩到 26 位再取字段，否则 (blockReg shr 10) and 0xFFFF
        // 取到的是**上一个**窗口的数据——整体错位一位，CRC 就再也过不了了。
        // 这就是「台名永远锁不上」的原因之一：Costas 稳定之前的前几块
        // 必然校验失败，从那一刻起窗口就永久错位了。
        val w = s.blockReg and 0x3FFFFFFL
        val data = ((w shr 10) and 0xFFFF).toInt()
        val check = (w and 0x3FF).toInt()
        val off = OFFSETS[s.expectBlock]
        if (checkOk(data, check, off)) {
            // A 块额外校验 PI 码：每个组的 A 块都是同一个 PI，这是最强约束。
            // 不加这一条的话，滑窗偶尔会在错误位置凑出一个「A 块 CRC 通过」
            // 的 26 位窗口（10 位 CRC 误判率 1/1024，但一秒有几十个块 ×
            // 24 条候选流，迟早撞上），块序列就此整体错位，台名会丢段。
            if (s.expectBlock == 0 && s.piCode >= 0 && data != s.piCode) {
                s.blockBits = 25
                return
            }
            s.blockData[s.blockIdx] = data
            s.blockIdx++
            s.expectBlock = (s.expectBlock + 1) and 3
            s.goodBlocks++
            refreshDiag()
            if (s.blockIdx == 4) {
                decodeGroup(s)
                s.blockIdx = 0
            }
            s.blockBits = 0
            s.blockReg = 0
        } else if (checkOk(data, check, OFFSETS[0]) && (s.piCode < 0 || data == s.piCode)) {
            // 任意时刻 A 块出现 → 鲁棒重同步。同样要求 PI 匹配：
            // 否则会把 D 块里凑巧能过 CRC 的内容当成 A 块，从此整条流错位。
            s.expectBlock = 1
            s.blockData[0] = data
            s.blockIdx = 1
            s.blockBits = 0
            s.blockReg = 0
        } else {
            s.blockBits = 25  // 滑窗
        }
    }

    /** 汇总诊断计数：哪条候选流 CRC 通过得最多 */
    private fun refreshDiag() {
        var bestIdx = -1
        var best = 0
        var total = 0
        for (i in streams.indices) {
            val g = streams[i].goodBlocks
            total += g
            if (g > best) { best = g; bestIdx = i }
        }
        crcBlocksOk = total
        bestPhaseIdx = if (best > 0) bestIdx else -1
        // PS 段进度取「最好的那条候选流」——错的候选流可能也收到了几段，
        // 但它收到的多半是乱码，显示出来会误导用户。
        val bestStream = if (bestIdx >= 0) streams[bestIdx] else null
        psSegments = bestStream?.psConfirmed?.count { it } ?: 0
        rtSegments = bestStream?.rtSegments ?: 0
        // PTY 从最好的那条流里取（B 块 bit 9-5）
        if (bestStream != null && bestStream.lastPty >= 0) pty = bestStream.lastPty
        if (bestStream != null) {
            if (bestStream.piCode >= 0) piCode = bestStream.piCode
            trafficProgram = bestStream.tp
            trafficAnnouncement = bestStream.ta
            // 组类型分布（只列真的收到过的，按类型号排序，最多列 6 种）
            val sb = StringBuilder()
            var listed = 0
            for (t in 0 until 16) {
                val n = bestStream.groupTypes[t]
                if (n <= 0) continue
                if (listed >= 6) { sb.append(" …"); break }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append("${t}A×$n")
                listed++
            }
            groupTypesSummary = sb.toString()
        }
    }

    /**
     * 诊断转储：打印每条候选流的块数、组数、段地址分布、收到的字符。
     *
     * 只在桌面自检工具里用（App 里不调用），用来定位「块过了但台名拼不出来」
     * 到底卡在段地址、group type 还是字符校验。
     */
    fun debugDump(): String {
        val sb = StringBuilder()
        for (i in streams.indices) {
            val s = streams[i]
            if (s.goodBlocks == 0) continue
            sb.append("    cand[$i] blocks=${s.goodBlocks} groups=${s.groupsOk} ")
            sb.append("seen=")
            for (k in 0 until 4) sb.append(if (s.psSeen[k]) '1' else '0')
            sb.append(" chars='")
            sb.append(String(s.psChars))
            sb.append("' groupTypes=")
            sb.append(s.groupTypes.joinToString(","))
            // 每段的票数分布（看少数表决有没有被假阳性票污染）
            for (seg in 0 until 4) {
                if (s.psTally[seg].isEmpty()) continue
                sb.append(" seg$seg{")
                for ((k, v) in s.psTally[seg].entries.sortedByDescending { it.value }) {
                    sb.append("'")
                    sb.append(((k shr 8) and 0xFF).toChar())
                    sb.append(((k) and 0xFF).toChar())
                    sb.append("'=$v ")
                }
                sb.append("}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun checkOk(data: Int, check: Int, offset: Int): Boolean {
        var rem = data shl 10
        for (i in 25 downTo 10) {
            if ((rem shr i) and 1 == 1) rem = rem xor (POLY shl (i - 10))
        }
        return (rem and 0x3FF) == (check xor offset)
    }

    private fun decodeGroup(s: BlockStream) {
        val b = s.blockData
        // 学 PI 码：连续两个组一致才锁定，避免第一次 CRC 假阳性把 PI 学错。
        // 锁定后 pushBit 会用 PI 校验每一个 A 块，block 序列不会再错位。
        if (s.piCode < 0) {
            val pi = b[0]
            if (s.piCandidate == pi) s.piCode = pi else s.piCandidate = pi
        }
        // group type / version 在 B 块（b[1]）高 4 位——之前误取 A 块（PI 码高 4 位
        // 基本不为 0），导致每个 group 都被这里 return 丢掉，台名永远锁不上。
        //
        // 0A 和 0B 都带 PS（电台名），PS 字符都在 D 块：
        //   0A 的 C 块是 AF（替代频率）表，0B 的 C 块是变体 AF 表，都不是字符。
        // 真实电台两种都发，只认 0A 会漏掉一半台名。
        val gtype = (b[1] shr 12) and 0xF
        s.groupTypes[gtype]++
        // PTY 在所有组类型的 B 块 bit 9-5 里都存在（IEC 62106 Table 9）
        s.lastPty = (b[1] shr 5) and 0x1F
        // TP bit 10，TA bit 4（IEC 62106）
        s.tp = ((b[1] shr 10) and 1) == 1
        s.ta = ((b[1] shr 4) and 1) == 1

        // group 2A = RadioText。RT 的段地址在 **A 块 bit 11-0**（和 PS 完全不同
        // 的位置），B 块 bit 4-1 里也有一份，两者应当一致。
        //
        // 规范里 RT 有 16 段 × 4 字符 = 64 字符。
        if (gtype == 2) {
            decodeRadioText(s, b)
            return
        }

        if (gtype != 0 && gtype != 1) return
        // **PS 段地址在 B 块 bit 1-0**（IEC 62106 §3.1.5.3 Block 2）。
        //
        // 这里返工过两次：最早写 bit 1-0（对的），v2.78.0 误信二手资料
        // 「纠正」到 bit 4-2，还用同样写错的参考实现互相验证通过——两个
        // 实现自洽但都错。最终靠 redsea（业界通用解码器）的 0B 真实抓包
        // 测试向量定案：
        //   0x9423 0800 0000 2020 / 0801 4B52 / 0802 4B41 / 0807 2020
        //   → PS = "  KRKA  "
        // 只有「bit 1-0 = 段地址」才能让 "KR" 落到位置 2-3；按 bit 4-2 读
        // 会全部落到段 0，结果是 "KR__  KA"。
        //
        // 0 组的 bit 2-0 = DI(2 bit) + 段地址(1 bit)？不是——0 组的 Block 2
        // 低 3 位是：bit 2-1 = DI，bit 0 = 段地址的低位，bit 1 是段地址高位。
        // 实际布局见上；PS 只用得到 segment 0-3。
        val segment = b[1] and 0x3
        val c1 = (b[3] shr 8) and 0xFF
        val c2 = b[3] and 0xFF
        val ch1 = if (c1 in 32..126) c1.toChar() else ' '
        val ch2 = if (c2 in 32..126) c2.toChar() else ' '
        // 全空格的段判为假块直接丢弃。
        //
        // 曾经踩过的坑：块边界错位一格时，某组会被解成 group 1，D 块取到
        // C 块的 AF 填充码 0xE0E0，两个字符都不是可打印字符 → 变成两个空格，
        // 被当成 segment 0 的合法内容。确认门槛是 1 票，于是这一段被
        // **永久占住**（后续正确内容票数相等，`cnt > psVotes` 不成立），
        // 台名从此少前 2 个字符，显示成 "CKETSD" 而不是 "POCKETSD"。
        // 真实台名是左对齐、尾部补空格的，中间/开头出现全空格段必是假块。
        if (ch1 == ' ' && ch2 == ' ') return

        // 段级多数表决：累计该段每个字符组合的票数，得票最多的胜出。
        //
        // 为什么需要表决：单个 26 位块的 CRC 误判率是 1/1024，不是 1/1024^4。
        // 一秒里有几十个块，假阳性块随时可能出现；假阳性块如果落在 D 块
        // 位置上，就会往台名里写进 2 个错字符。
        //
        // 确认门槛是 **1 票**（不是 2 票）：实测 7 dB SNR 时段 1 在 2 秒窗口里
        // 只收到 1 次，2 票门槛会让台名永远缺一段（显示成 "PO  ETSD"）。
        // 段地址修对之后（见下面 segment 的注释），不同段之间不会互相污染，
        // 单次误码只会写坏自己那一段的 2 个字符，不会毁掉整个台名。
        //
        // 后续收到更多票时允许**改写**——真实电台改台名时需要这个。
        val idx = segment * 2
        val key = (ch1.code shl 8) or ch2.code
        val votes = s.psTally[segment]
        val cnt = (votes[key] ?: 0) + 1
        votes[key] = cnt
        if (!s.psConfirmed[segment] || cnt > s.psVotes[segment]) {
            s.psChars[idx] = ch1
            s.psChars[idx + 1] = ch2
            s.psConfirmed[segment] = true
            s.psVotes[segment] = cnt
        }
        s.psSeen[segment] = true
        s.groupsOk++

        // **部分台名也算数**——这是 v2.78.0 的关键修复。
        //
        // 之前要求 4 个段全部到齐才发布台名。实测（5 dB SNR）解码器已经
        // 正确解出了前 6 个字符 "POCKET"，只差最后一个段，却因为
        // psSeen.all { it } 不满足而**什么都不显示**——用户看到的是空白，
        // 而实际上 6 个字符里的每一个都是对的。
        //
        // 现在：把已确认的段发布出去，缺的部分留空。后续段到达时自动补全。
        if (s.groupsOk >= 1 && s.psConfirmed.any { it }) {
            val name = buildName(s)
            if (name.isNotEmpty() && name != stationName) {
                stationName = name
                dataLock = true
            }
        }
    }

    /**
     * 解 RadioText（group 2A）。
     *
     * ## 位布局（IEC 62106 §3.1.5.3，Block 2）
     * - **A 块永远是 PI 码**，RT 的段地址**不在 A 块**。
     * - **B 块 bit 3-0 = 段地址**（0~15，16 段 × 4 字符 = 64 字符）
     * - **B 块 bit 4 = A/B 标志**：翻转表示文本内容要换（清缓冲区）
     * - **C 块 = 字符 0-1，D 块 = 字符 2-3**
     *
     * 依据 redsea `Station::decodeType2`（业界通用实现，真实抓包验证）：
     *   radiotext_position = getBits<4>(BLOCK2, 0) * 4
     *   ab                = getBool(BLOCK2, 4)
     */
    private fun decodeRadioText(s: BlockStream, b: IntArray) {
        val seg = b[1] and 0x0F           // B 块 bit 3-0
        val abFlag = (b[1] shr 4) and 1    // B 块 bit 4
        // A/B 标志翻转 = 电台换文本了，旧内容作废
        if (s.rtAbFlag != abFlag) {
            if (s.rtAbFlag >= 0) {
                java.util.Arrays.fill(s.rtSeen, false)
                java.util.Arrays.fill(s.rtVotes, 0)
                for (t in s.rtTally) t.clear()
                radioText = ""
            }
            s.rtAbFlag = abFlag
        }
        // C 块 = 字符 0-1，D 块 = 字符 2-3
        val c = b[2]
        val d = b[3]
        val key = (c shl 16) or (d and 0xFFFF)
        val c0 = (c shr 8) and 0xFF
        val c1 = c and 0xFF
        val c2 = (d shr 8) and 0xFF
        val c3 = d and 0xFF
        // 可打印字符（32..126）；其余（含控制符和中文的 UTF-8 高位）一律当空格。
        // 真实电台的 RT 里可能有非 ASCII（各家电台编码不一），显示成方框比乱码好。
        fun ch(v: Int): Char = when (v) { 0x0D -> '\u000D'; 0x0A -> '\u000A'; 0x00 -> '\u0000'; in 32..126 -> v.toChar(); else -> ' ' }
        val t = charArrayOf(ch(c0), ch(c1), ch(c2), ch(c3))
        val votes = s.rtTally[seg]
        val cnt = (votes[key] ?: 0) + 1
        votes[key] = cnt
        if (!s.rtSeen[seg] || cnt > s.rtVotes[seg]) {
            for (i in 0 until 4) s.rtChars[seg * 4 + i] = t[i]
            s.rtSeen[seg] = true
            s.rtVotes[seg] = cnt
        }
        s.rtSegments = s.rtSeen.count { it }
        // 发布：已确认的段拼起来，未确认的段留空格。
        val txt = buildRadioText(s)
        if (txt.isNotEmpty() && txt != radioText) {
            radioText = txt
        }
    }

    /** 用已确认的 RT 段拼出文本（未确认的段留空格） */
    private fun buildRadioText(s: BlockStream): String {
        val sb = StringBuilder(64)
        // RT 有终止符：ET(0x0D) / BT(0x0A) / NUL(0x00) 表示文本到此为止，
        // 电台常发 "HELLO FROM POCKETSDR\x0D..." 这种带填充的形式。
        // 不截断的话尾部会出现一堆空格或控制符，trim() 也清不干净（空格前还有字符时）。
        for (seg in 0 until 16) {
            if (!s.rtSeen[seg]) {
                sb.append("    ")
                continue
            }
            for (i in 0 until 4) {
                val ch = s.rtChars[seg * 4 + i]
                if (ch == '\u000D' || ch == '\u000A' || ch == '\u0000') {
                    // 截断：后面的内容作废（但不删，电台切文本时 A/B 标志会清空）
                    return sb.toString().trimEnd()
                }
                sb.append(ch)
            }
        }
        return sb.toString().trimEnd()
    }

    /** 用**已确认**的段拼出台名（未确认的段留空格） */
    private fun buildName(s: BlockStream): String {
        val sb = StringBuilder(8)
        for (seg in 0 until 4) {
            if (!s.psConfirmed[seg]) {
                sb.append("  ")
            } else {
                sb.append(s.psChars[seg * 2])
                sb.append(s.psChars[seg * 2 + 1])
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private const val POLY = 0b10100101101
        private val OFFSETS = intArrayOf(0x000, 0x0FC, 0x0D9, 0x1D9)

        /** 基带环形缓冲长度（需 ≥ 一个 bit 周期的 2 倍 + 抖动余量） */
        private const val BUF_SIZE = 4096
        /** 候选相位个数——铺满整个 bit 周期 */
        private const val PHASE_COUNT = 24

        /**
         * 57kHz Costas 解调 + 数据低通，返回基带波形。
         *
         * 抽成公开函数是为了让桌面实验（低通带宽扫描）能复用**同一份**链路，
         * 避免实验代码和真实解码器各写一份然后悄悄漂移——那正是
         * v2.77.0 里「编码解码器自洽但都错」那类 bug 的温床。
         *
         * @param lpHz 数据低通截止频率（两级级联）
         */
        fun costasAndLowpass(mpx: FloatArray, fs: Float, lpHz: Float): FloatArray {
            var vcoPhase = 0.0
            val vcoInc = 2.0 * Math.PI * 57000 / fs
            val loopLp = Biquad.Lowpass(800f, fs)
            var loopInteg = 0.0
            val dataLp = Biquad.Lowpass(lpHz, fs)
            val dataLp2 = Biquad.Lowpass(lpHz, fs)
            val out = FloatArray(mpx.size)
            for (i in mpx.indices) {
                vcoPhase += vcoInc + loopInteg
                if (vcoPhase > TWO_PI) vcoPhase -= TWO_PI
                val c = cos(vcoPhase).toFloat()
                val s = sin(vcoPhase).toFloat()
                val iBr = mpx[i] * c
                val qBr = mpx[i] * s
                val err = loopLp.process(iBr * qBr)
                loopInteg = loopInteg * 0.99999 - err * 2e-6
                out[i] = dataLp2.process(dataLp.process(iBr))
            }
            return out
        }
    }
}
