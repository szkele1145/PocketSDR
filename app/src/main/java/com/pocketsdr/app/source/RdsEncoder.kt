package com.pocketsdr.app.source

import kotlin.math.cos

/**
 * RDS 编码器（电台名 PS 组）：生成标准 RDS 基带双相码，供仿真源调制到 57kHz。
 *
 * 信号结构（IEC 62106）：
 *  - 1187.5 bps 差分双相码（每比特两半，相邻跳变编码）
 *  - 26 位块：16 数据 + 10 CRC（偏置字区分块 A/B/C/D）
 *  - PS 电台名 = 4 组 group 0A，每组带 2 字符
 *
 * 编码流程：bit 流 → 双相波形 → 57kHz DSB 抑制载波（调制度 ~7.5%）
 */
class RdsEncoder(
    private val stationName: String,
    /** RadioText：最长 64 字符的滚动文本（IEC 62106 group 2A），空串表示不发 */
    private val radioText: String = "",
    /** 替代频率表（kHz）。非空时写进 group 0A 的 C 块（每组装 2 个 AF 码） */
    private val alternativeFrequenciesKhz: List<Int> = emptyList()
) {

    companion object {
        // RDS CRC 生成多项式 G(x) = x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + 1
        private const val POLY = 0x1B9  // (x^10..x^3 项: 1010110111b 反转序，见下)
        // 实际用标准表：g = 0x2B (10bit: 0b0100010111)？按 IEC 62106:
        // G(x)=x^10+x^8+x^7+x^5+x^4+x^3+1 → 二进制 10100101101 (11 位含 x^10)
        // 校验字 = 剩余多项式，偏置字 A=0, B=252(0x0FC), C=217(0x0D9)? 见 RDS 规范表
        private val OFFSETS = intArrayOf(0x000, 0x0FC, 0x0D9, 0x1D9)
        // 修正：标准偏置字 A=0x000 B=0x0FC C=0x0D9 D=0x1D9（C' 用于交通信息，此处用 C）
    }

    // ---------- 帧生成 ----------
    /** 生成 4 块（A/B/C/D）× 26 位 = 104 位的一帧 group 0A */
    private fun buildFrame(segment: Int, chars: CharArray): BooleanArray {
        val bits = BooleanArray(104)
        var p = 0

        fun pushWord(v: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                bits[p++] = ((v shr i) and 1) == 1
            }
        }

        // 块 A：PI 码（电台识别，用 0x1234 演示值）
        pushWord(0x1234, 16)
        pushWord(crc10(0x1234) xor OFFSETS[0], 10)
        // 块 B：group 0A (type=0000, version=0) + TP=0 + PTY=1(新闻) + 段地址
        //
        // **B 块位布局（IEC 62106 §3.1.5.3，Block 2）**：
        //   bit 15-12 : group type
        //   bit 11    : B0  (version: 0 = A, 1 = B)
        //   bit 10    : TP  (traffic programme)
        //   bit 9-5   : PTY (5 bits)
        //   bit 4     : TA  (traffic announcement)
        //   bit 3     : M/S (music/speech)
        //   bit 2-1   : DI  (decoder identification)  ← group 0 专有
        //   bit 0     : Segment Address               ← **PS 段地址在这里**
        //
        // 注意：0 组的 bit 2-0 不是「3 位段地址 + C1/C0」。C1/C0 是 4A 组
        // 那种带交通信息的组才有的，0 组没有。0 组的段地址只有 **2 位，在 bit 1-0**。
        //
        // 这一格踩过两次：最早写在 bit 1-0（对的），v2.78.0 又「纠正」到
        // bit 4-2（错的），并用同样写错的独立实现互相验证通过。最后拿
        // redsea（业界通用解码器）的 0B 测试向量实测定案：
        //   0x9423 0800 0000 2020 / 0801 4B52 / 0802 4B41 / 0807 2020
        //   → PS = "  KRKA  "（0801 的 bit1-0=01 → 段 1 → "KR" 落在位置 2-3）
        val bB = (0 shl 12) or (0 shl 11) or (0 shl 10) or (1 shl 5) or (segment and 0x3)
        pushWord(bB, 16)
        pushWord(crc10(bB) xor OFFSETS[1], 10)

        // **修复**：AF 填充用 0xE0（填充码），不是 0x00。
        // 0x00 会被部分实现误当成「空」处理，但规范上 0x00 有特殊含义（跳过该码）；
        // 更关键的是 0xE0（224）超出 VHF 有效码范围（1..204），我们的解码器和大多数实现都直接跳过，
        // 不会污染 AF 列表。
        val bC = if (alternativeFrequenciesKhz.isEmpty()) {
            0xE0E0
        } else {
            // 段 segment 对应第 (segment*2) 和 (segment*2+1) 个频率；
            // 只有 4 个 PS 段，所以最多发 8 个 AF；超出部分忽略。
            // 不足 2 个时补 0xE0（无效码，解码器跳过）。
            val af1 = alternativeFrequenciesKhz.getOrNull(segment * 2)
            val af2 = alternativeFrequenciesKhz.getOrNull(segment * 2 + 1)
            val code1 = af1?.let { ((it - 87_500) / 100).coerceIn(1, 204) } ?: 0xE0
            val code2 = af2?.let { ((it - 87_500) / 100).coerceIn(1, 204) } ?: 0xE0
            (code1 shl 8) or code2
        }
        pushWord(bC, 16)
        pushWord(crc10(bC) xor OFFSETS[2], 10)

        // 块 D：PS 电台名的 2 个字符（IEC 62106：0A/0B 的 PS 都在 D 块）。
        // 之前这里漏了整块 D（只写 78 位，余 26 位补零），全零块恰好能用 A 块偏置字
        // 通过校验，于是每组末尾都被误判成"重同步 A 块"，blockIdx 永远到不了 4，
        // decodeGroup 一次都没被调用过——台名自然永远锁不上。
        val c1 = chars[segment * 2].code and 0xFF
        val c2 = chars[segment * 2 + 1].code and 0xFF
        val bD = (c1 shl 8) or c2
        pushWord(bD, 16)
        pushWord(crc10(bD) xor OFFSETS[3], 10)
        return bits
    }

    /**
     * 生成 group 2A 的帧（RadioText，4 字符/组）。
     *
     * ## 位布局（IEC 62106 §3.1.5.3）
     *
     * - **A 块永远是 PI 码**（电台识别）。RT 的段地址**不在 A 块**。
     * - B 块：bit 15-12 group type = 0010；bit 11 B0 = 0（版本 A）；
     *   bit 10 TP；bit 9-5 PTY；**bit 4 A/B 标志**；
     *   **bit 3-0 段地址**（4 位，16 段 × 4 字符 = 64 字符）。
     * - **C 块 = 字符 0-1，D 块 = 字符 2-3**（每块只有 16 bit，4 个字符
     *   必须拆两块；全塞 D 块会让高 16 bit 直接截断，解出来全是错位字符）。
     *
     * 位布局依据：redsea `Station::decodeType2`（业界通用实现）
     *   radiotext_position = getBits<4>(BLOCK2, 0) * (versionA ? 4 : 2)
     *   ab                = getBool(BLOCK2, 4)
     *   C 块 → 位置 +0，D 块 → 位置 +2
     * 该实现是拿真实电台抓包验证过的，比手写规范解释可靠。
     */
    private fun buildRtFrame(segment: Int, aToB: Boolean, chars: CharArray): BooleanArray {
        val bits = BooleanArray(104)
        var p = 0

        fun pushWord(v: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                bits[p++] = ((v shr i) and 1) == 1
            }
        }

        // A 块：PI 码（始终是 PI，段地址不在这里）
        pushWord(0x1234, 16)
        pushWord(crc10(0x1234) xor OFFSETS[0], 10)

        // B 块：group 2A + PTY + A/B 标志（bit 4）+ 段地址（bit 3-0）
        val bB = (2 shl 12) or (0 shl 11) or (0 shl 10) or (1 shl 5) or
                ((if (aToB) 1 else 0) shl 4) or (segment and 0xF)
        pushWord(bB, 16)
        pushWord(crc10(bB) xor OFFSETS[1], 10)

        // C 块：RT 字符 0-1
        val bC = ((chars[0].code and 0xFF) shl 8) or (chars[1].code and 0xFF)
        pushWord(bC, 16)
        pushWord(crc10(bC) xor OFFSETS[2], 10)

        // D 块：RT 字符 2-3
        val bD = ((chars[2].code and 0xFF) shl 8) or (chars[3].code and 0xFF)
        pushWord(bD, 16)
        pushWord(crc10(bD) xor OFFSETS[3], 10)
        return bits
    }

    /** RDS CRC-10（IEC 62106 标准多项式） */
    private fun crc10(data: Int): Int {
        // G(x) = x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + 1 → 二进制 0b10100101101
        // 长除法：数据 16 位后补 10 个 0，对多项式取余
        var rem = data shl 10
        val poly = 0b10100101101
        for (i in 25 downTo 10) {
            if (((rem shr i) and 1) == 1) {
                rem = rem xor (poly shl (i - 10))
            }
        }
        return rem and 0x3FF
    }

    // ---------- 双相编码 ----------
    /**
     * 位流 → 差分双相波形。1187.5bps → 每位两半，相邻跳变编码。
     *
     * **位长必须用分数累加，不能整数截断。**
     * 早期版本用 `samplesPerBit = (sampleRate / 1187.5f).toInt()` = 215，
     * 于是 256 kHz 下实际码率变成 256000/215 = 1190.7 bps，比标准 1187.5 bps
     * 偏了 0.28%。解码器的位时钟 NCO 跑的是标准 1187.5 Hz（每 bit
     * 215.4667 个采样），两者每 bit 差 0.4667 个采样 —— 判决点相对信号
     * 每 460 bit 漂移整整 1 bit，CRC 从此永远过不了，台名永远锁不上。
     *
     * 现在每个 bit 精确占 `sampleRate / 1187.5` 个采样（分数位累加），
     * bit 边界精确落在 k × (fs/1187.5)，与解码器严格对齐。
     */
    private fun biphase(bits: BooleanArray, sampleRate: Float): FloatArray {
        val inc = sampleRate / 1187.5f              // 每 bit 采样数（分数，256k 时 215.4667）
        val total = kotlin.math.ceil(bits.size.toDouble() * inc.toDouble()).toInt()
        val wave = FloatArray(total)
        var level = 1f
        var p = 0
        // 用**小数累加器**分配每 bit 的采样数：216,215,216,215,... 平均正好
        // 215.4667。这样 bit 边界严格落在 k × (fs/1187.5)。
        //
        // 踩过的坑：
        //  a) 每 bit 固定取整 215 → 码率 1190.7 bps，偏 +0.28%；
        //  b) 每 bit 固定取整 216 → 码率 1185.2 bps，偏 -0.19%；
        //  c) 用浮点相位 while(s < bitEnd) 逐点步进 → 起点是分数，
        //     每 bit 仍覆盖 216 个整数点，等同 (b)。
        // 三种都让判决点相对信号逐 bit 漂移，CRC 永远过不了。
        var frac = 0f
        for (b in bits) {
            // 差分双相：每位中间必跳变；"0" 位开头也跳，"1" 位开头不跳
            val half0 = if (b) level else -level
            val half1 = -half0
            level = half1  // 位尾电平成为下一位起始
            frac += inc
            val nSamples = frac.toInt()
            frac -= nSamples.toFloat()
            val nFirst = nSamples / 2
            val nSecond = nSamples - nFirst
            for (s in 0 until nFirst) wave[p++] = half0
            for (s in 0 until nSecond) wave[p++] = half1
        }
        // **必须按实际写入长度返回**。上面 total 是 ceil(位数 × 每bit采样数)，
        // 分数累加后实际写入的 p 通常比 total 少 0~1 个采样，多出来的尾部
        // 是 FloatArray 的零值——波形末尾突然掉到 0 再回绕，「零采样段」
        // 会把紧随其后的那一组比特整个毁掉。
        //
        // 这个 bug 直到加 RT（组数从 4 变 9）才暴露：回绕点恰好落在
        // PS 段 0 之前，于是**每轮都必然丢掉段 0 的 "PO"**，台名显示成
        // "CKETSD"。波形长度对齐到整 bit 之后回绕就落在 bit 边界上，
        // 不会再毁组。
        // 按实际写入长度返回（total 是 ceil，实际写入 p 通常少 0~1 个采样，
        // 多出来的尾部零值会在循环播放时制造电平跳变）。
        return wave.copyOf(p)
    }

    // ---------- 57kHz DSB 调制 ----------
    /**
     * 一个完整循环的**基带**双相波形（不含 57kHz 载波）。
     *
     * 抗噪回归测试要自己控制载波初相和调制度，所以必须能拿到未调制的
     * 基带。之前测试是从已调制波形里 `÷ cos(2π·57k·t)` 反解基带——
     * 在 cos 过零点附近除法会放大成巨大值，等于给波形注入尖峰噪声，
     * 于是「某些载波初相下台名解不出」的假失败一直存在。
     */
    fun buildBaseband(sampleRate: Float, repeats: Int = 1): FloatArray {
        val base = groupBits()
        val bits = if (repeats <= 1) base else BooleanArray(base.size * repeats).also {
            for (k in 0 until repeats) System.arraycopy(base, 0, it, k * base.size, base.size)
        }
        return biphase(bits, sampleRate)
    }

    /** 把 PS 组和 RT 组拼成完整的一轮比特流（每轮 4 个 PS 组 + N 个 RT 组） */
    private fun groupBits(): BooleanArray {
        val name = stationName.padEnd(8, ' ').take(8)

        // 组序列：4 个 PS 组，然后 RT 的 N 个段（N = ceil(len/4)，每段 4 字符）。
        // 真实电台是 PS/RT/其他组按比例混发；这里让 PS 每轮都发一遍，
        // 保证两轮内就能凑齐 8 个字符的台名。
        val groups = ArrayList<BooleanArray>()
        for (seg in 0 until 4) {
            groups.add(buildFrame(seg, name.toCharArray()))       // PS
        }
        if (radioText.isNotEmpty()) {
            val rt = radioText.padEnd(4, ' ').take(64)
            val nSeg = (rt.length + 3) / 4
            val padded = rt.padEnd(nSeg * 4, ' ')
            for (seg in 0 until nSeg) {
                groups.add(buildRtFrame(seg, false, padded.substring(seg * 4, seg * 4 + 4).toCharArray()))
            }
        }

        val bits = BooleanArray(groups.size * 104)
        for ((i, g) in groups.withIndex()) {
            System.arraycopy(g, 0, bits, i * 104, 104)
        }
        return bits
    }

    /** 预生成一个完整循环（PS + RT）的 57k DSB 波形样本；repeats > 1 时连续重复 */
    fun buildWave(sampleRate: Float, repeats: Int = 1): FloatArray {
        val base = buildBaseband(sampleRate, repeats)
        // 57k DSB：base × cos(2π·57k·t)，幅度 RDS 调制度 ~ 0.075（相对满偏）
        val out = FloatArray(base.size)
        val w = 2.0 * Math.PI * 57000 / sampleRate
        for (i in out.indices) {
            out[i] = base[i] * cos(w * i).toFloat() * 0.075f * 2f
        }
        return out
    }
}
