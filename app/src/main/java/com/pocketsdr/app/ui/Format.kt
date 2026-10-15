package com.pocketsdr.app.ui

/** 频率格式化（公共工具） */
internal fun formatFreq(f: Long): String {
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

/** 视野宽度（Hz）→ 人类可读，如 1024k / 256k / 128k */
internal fun formatSpan(hz: Long): String {
    return when {
        hz >= 1_000_000 -> "%.1fM".format(hz / 1e6)
        hz >= 1_000 -> "${hz / 1_000}k"
        else -> "${hz}Hz"
    }
}
