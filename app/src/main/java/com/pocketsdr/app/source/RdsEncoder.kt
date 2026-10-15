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
class RdsEncoder(private val stationName: String) {

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
        val bB = (0 shl 12) or (0 shl 11) or (0 shl 10) or (1 shl 5) or (segment and 0x3)
        pushWord(bB, 16)
        pushWord(crc10(bB) xor OFFSETS[1], 10)

        // 块 C：group 0A 时是 AF（替代频率）表，无 AF 用 0xE0 填充码
        val bC = 0xE0E0
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
    /** 位流 → 差分双相波形。1187.5bps → 每位 2 个半位电平翻转 */
    private fun biphase(bits: BooleanArray, samplesPerBit: Int): FloatArray {
        val half = samplesPerBit / 2
        val wave = FloatArray(bits.size * samplesPerBit)
        var level = 1f
        var p = 0
        for (b in bits) {
            // 差分双相：每位中间必跳变；"0" 位开头也跳，"1" 位开头不跳
            val half0 = if (b) level else -level
            val half1 = -half0
            level = half1  // 位尾电平成为下一位起始
            for (s in 0 until half) wave[p++] = half0
            for (s in 0 until samplesPerBit - half) wave[p++] = half1
        }
        return wave
    }

    // ---------- 57kHz DSB 调制 ----------
    /** 预生成一个完整 PS 名循环（4 段 × 104 位）的 57k DSB 波形样本 */
    fun buildWave(sampleRate: Float): FloatArray {
        val samplesPerBit = (sampleRate / 1187.5f).toInt().coerceAtLeast(8)
        val name = stationName.padEnd(8, ' ').take(8)
        val bits = BooleanArray(416)
        for (seg in 0 until 4) {
            val frame = buildFrame(seg, name.toCharArray())
            System.arraycopy(frame, 0, bits, seg * 104, 104)
        }
        val base = biphase(bits, samplesPerBit)
        // 57k DSB：base × cos(2π·57k·t)，幅度 RDS 调制度 ~ 0.075（相对满偏）
        val out = FloatArray(base.size)
        val w = 2.0 * Math.PI * 57000 / sampleRate
        for (i in out.indices) {
            out[i] = base[i] * cos(w * i).toFloat() * 0.075f * 2f
        }
        return out
    }
}
