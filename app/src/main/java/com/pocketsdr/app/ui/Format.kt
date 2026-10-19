package com.pocketsdr.app.ui

/** 频率格式化（公共工具） */
fun formatFreq(f: Long): String {
    val m = f / 1e6
    return if (f % 1_000_000 == 0L) "%.0f MHz".format(m) else "%.4f MHz".format(m)
}

/** dBFS → 业余 S 表（S9=-30dBFS 基准，6dB/S 单位，S9+ 以 dB 超载显示） */
internal fun sMeter(dbfs: Float): String {
    return when {
        dbfs >= -30 -> "S9+${((dbfs + 30) / 6).toInt() * 6}dB"
        dbfs >= -78 -> "S${((dbfs + 78) / 6).toInt() + 1}"
        else -> "S1"
    }
}

/**
 * 信号质量结论：把 dBFS + 静噪状态翻译成一句人话。
 *
 * 业余 S 表对新手不直观，而真机上「-55dB」也看不出好坏（增益不同绝对值差异巨大）。
 * 这里给的是相对结论：能不能听、要不要调天线/增益。
 *
 * @param dbfs 信号电平（dBFS）
 * @param squelchOpen 静噪是否已开（开了说明确实超过门限有信号）
 * @param squelchDb 当前静噪门限
 */
internal fun signalQualityText(dbfs: Float, squelchOpen: Boolean, squelchDb: Float): String {
    return when {
        dbfs <= -95f -> "无信号（查天线/增益）"
        squelchOpen && dbfs >= -45f -> "信号很强"
        squelchOpen && dbfs >= -65f -> "信号良好"
        squelchOpen -> "信号偏弱（可加天线/调 LNA）"
        dbfs > squelchDb -> "有信号（静噪未开）"
        dbfs > -80f -> "很弱（静噪已关）"
        else -> "几乎无信号"
    }
}

/** 视野宽度（Hz）→ 人类可读，如 1024k / 256k / 128k */
internal fun formatSpan(hz: Long): String {
    return when {
        hz >= 1_000_000 -> "%.1fM".format(hz / 1e6)
        hz >= 1_000 -> "${hz / 1_000}k"
        else -> "${hz}Hz"
    }
}
