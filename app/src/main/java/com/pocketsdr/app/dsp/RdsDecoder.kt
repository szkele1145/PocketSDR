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
        /** 连续完成的 group 数——≥2 才认为可信（CRC 假阳性率 1/1024^4） */
        var groupsOk = 0
        /** CRC 通过的块数，用来在候选之间挑「最可信」的那条 */
        var goodBlocks = 0

        fun reset() {
            blockReg = 0L
            blockBits = 0
            expectBlock = 0
            blockIdx = 0
            blockData[0] = 0; blockData[1] = 0; blockData[2] = 0; blockData[3] = 0
        }
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
        } else if (checkOk(data, check, OFFSETS[0])) {
            // 任意时刻 A 块出现 → 鲁棒重同步
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
        // group type / version 在 B 块（b[1]）高 4 位——之前误取 A 块（PI 码高 4 位
        // 基本不为 0），导致每个 group 都被这里 return 丢掉，台名永远锁不上。
        if (((b[1] shr 12) and 0xF) != 0) return
        // PS 字符在 D 块（IEC 62106）：0A/0B 的 C 块是 AF 替代频率表，不是字符。
        // 段地址在 B 块低 2 位，段按 0→1→2→3 轮转，这里直接落位——不能做
        // "同段连续两次相同"表决（下一段到来时 pending 已被覆盖）。
        val segment = b[1] and 0x3
        val c1 = (b[3] shr 8) and 0xFF
        val c2 = b[3] and 0xFF
        s.psChars[segment * 2] = if (c1 in 32..126) c1.toChar() else ' '
        s.psChars[segment * 2 + 1] = if (c2 in 32..126) c2.toChar() else ' '
        s.psSeen[segment] = true
        s.groupsOk++
        if (s.psSeen.all { it }) {
            val name = String(s.psChars).trim()
            if (name.isNotEmpty() && name != stationName) {
                stationName = name
                dataLock = true
            }
        }
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private const val POLY = 0b10100101101
        private val OFFSETS = intArrayOf(0x000, 0x0FC, 0x0D9, 0x1D9)

        /** 基带环形缓冲长度（需 ≥ 一个 bit 周期的 2 倍 + 抖动余量） */
        private const val BUF_SIZE = 4096
        /** 候选相位个数——铺满整个 bit 周期 */
        private const val PHASE_COUNT = 24
    }
}
