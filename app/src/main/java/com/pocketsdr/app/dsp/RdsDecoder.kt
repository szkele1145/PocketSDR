package com.pocketsdr.app.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * RDS 解码器（IEC 62106）。
 *
 * 输入：MPX 复用信号（鉴频后），需 ≥256ksps（57kHz 载波奈奎斯特）。
 *
 * 级联：57k Costas 下变频 → 2.4k 低通 → 迟延乘积位同步 →
 * 差分双相解码 → 26 位块 CRC 锁定（A/B/C/D 序列）→ group 0A PS 电台名。
 */
class RdsDecoder(private val fs: Float) {

    // ===== 57k Costas =====
    private var vcoPhase = 0.0
    private val vcoInc = 2.0 * Math.PI * 57000 / fs
    private val loopLp = Biquad.Lowpass(800f, fs)
    private var loopInteg = 0.0

    // ===== 基带 =====
    private val dataLp = Biquad.Lowpass(2400f, fs)

    // ===== 位同步（早晚门：额定 1187.5Hz 时钟 + 过零微调）=====
    private val delayHalf = (fs / 2375f).toInt().coerceAtLeast(2)
    private val delayBuf = FloatArray(delayHalf)
    private var delayPos = 0

    // ===== Gardner TED 位同步（2 倍过采样数字 PI 环，经 DspVerify 调参）=====
    private var clockVco = 0.0
    private val clockVcoInc = 2.0 * Math.PI * 1187.5 / fs
    private var gardnerTrim = 0.0
    private var lastSymbolPhase = false
    private var prevSym = 0f
    private var prevSymForDecision = 0f
    private var midSample = 0f

    // ===== 位/块 =====
    private var blockReg = 0L
    private var blockBits = 0
    private var expectBlock = 0
    private val blockData = IntArray(4)
    private var blockIdx = 0

    @Volatile var stationName = ""
        private set
    @Volatile var dataLock = false
        private set

    private val psChars = CharArray(8) { ' ' }
    private val psSeen = BooleanArray(4)

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
        // 单级 2.4k 低通（双级联是模拟 PLL 时代遗留，Costas 环外一级足够）
        val base = dataLp.process(iBr)

        // Gardner 位同步
        clockVco += clockVcoInc + gardnerTrim
        if (clockVco > TWO_PI) clockVco -= TWO_PI
        val symbolNow = sin(clockVco) >= 0.0
        if (symbolNow != lastSymbolPhase) {
            lastSymbolPhase = symbolNow
            if (symbolNow) {
                val cur = base
                // Gardner 误差：(cur − prevSym) × midSample
                val e = (cur - prevSym) * midSample
                gardnerTrim = (gardnerTrim * 0.995 - e * 5e-5).coerceIn(-1e-3, 1e-3)
                prevSym = cur
                // 差分判决（乘积判据，级0验证过）：反相=1
                val bit = if (cur * prevSymForDecision < 0f) 1 else 0
                prevSymForDecision = cur
                pushBit(bit)
            } else {
                midSample = base
            }
        }
    }

    private fun pushBit(bit: Int) {
        blockReg = (blockReg shl 1) or bit.toLong()
        if (blockBits < 26) blockBits++
        if (blockBits < 26) return
        val data = ((blockReg shr 10) and 0xFFFF).toInt()
        val check = (blockReg and 0x3FF).toInt()
        val off = OFFSETS[expectBlock]
        if (checkOk(data, check, off)) {
            blockData[blockIdx] = data
            blockIdx++
            expectBlock = (expectBlock + 1) and 3
            if (blockIdx == 4) {
                decodeGroup(blockData)
                blockIdx = 0
            }
            blockBits = 0
            blockReg = 0
        } else if (checkOk(data, check, OFFSETS[0])) {
            // 任意时刻 A 块出现 → 鲁棒重同步
            expectBlock = 1
            blockData[0] = data
            blockIdx = 1
            blockBits = 0
            blockReg = 0
        } else {
            blockBits = 25  // 滑窗
        }
    }

    private fun checkOk(data: Int, check: Int, offset: Int): Boolean {
        var rem = data shl 10
        for (i in 25 downTo 10) {
            if ((rem shr i) and 1 == 1) rem = rem xor (POLY shl (i - 10))
        }
        return (rem and 0x3FF) == (check xor offset)
    }

    private fun decodeGroup(b: IntArray) {
        if (((b[0] shr 12) and 0xF) != 0) return
        val segment = b[1] and 0x3
        val c1 = (b[2] shr 8) and 0xFF
        val c2 = b[2] and 0xFF
        val ch1 = if (c1 in 32..126) c1.toChar() else ' '
        val ch2 = if (c2 in 32..126) c2.toChar() else ' '
        // 段级双重确认：同段连续 2 次相同才接受（跨帧表决滤随机错）
        if (pendingSeg == segment && pendingC1 == ch1 && pendingC2 == ch2) {
            psChars[segment * 2] = ch1
            psChars[segment * 2 + 1] = ch2
            psSeen[segment] = true
            pendingSeg = -1
            if (psSeen.all { it }) {
                val name = String(psChars).trim()
                if (name.isNotEmpty() && name != stationName) {
                    stationName = name
                    dataLock = true
                }
            }
        } else {
            pendingSeg = segment
            pendingC1 = ch1
            pendingC2 = ch2
        }
    }

    private var pendingSeg = -1
    private var pendingC1 = ' '
    private var pendingC2 = ' '

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private const val POLY = 0b10100101101
        private val OFFSETS = intArrayOf(0x000, 0x0FC, 0x0D9, 0x1D9)
    }
}
