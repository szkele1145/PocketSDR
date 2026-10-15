package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 频率刻度轴：可见区间内的刻度列表 + 自适应步长。
 *
 * 频谱 Canvas 与顶部刻度条都用它，保证"画出来的竖线"和"标出来的数字"
 * 永远同一套坐标——之前两边各算各的，横屏下拉动/变焦后数字和网格线对不上。
 */
internal data class FreqAxis(val stepHz: Long, val ticks: List<Long>)

/**
 * 取可见区间 [center-span/2, center+span/2] 内的刻度。
 * stepHz 在常用档位里挑第一个"相邻间距 >= minPx"的，避免刻度糊成一片。
 */
internal fun freqAxis(centerFreq: Long, span: Long, widthPx: Float, minPx: Float): FreqAxis {
    if (span <= 0L || widthPx <= 0f) return FreqAxis(1_000_000L, emptyList())
    val steps = longArrayOf(
        5_000L, 10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L,
        1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 25_000_000L,
        50_000_000L, 100_000_000L
    )
    var step = steps[steps.size - 1]
    for (s in steps) {
        if (s.toDouble() / span * widthPx >= minPx) { step = s; break }
    }
    val left = centerFreq - span / 2
    val right = centerFreq + span / 2
    val out = ArrayList<Long>(16)
    var f = (left / step) * step
    while (f <= right && out.size < 64) {
        if (f >= left) out.add(f)
        f += step
    }
    return FreqAxis(step, out)
}

/** 刻度文字：步长越大位数越少，统一带 M 单位，横屏一眼能读。 */
internal fun tickLabel(f: Long, stepHz: Long): String {
    val mhz = f / 1_000_000.0
    val s = when {
        stepHz >= 1_000_000L -> "%.1f".format(mhz)
        stepHz >= 100_000L -> "%.2f".format(mhz)
        else -> "%.3f".format(mhz)
    }
    return s + "M"
}

/**
 * 顶部频率刻度条（整宽、无水平内边距：坐标系必须和下方 Canvas 完全一致）。
 * 超出边缘的标签做位置收敛，不会被裁掉半个字。
 */
@Composable
fun FreqRuler(centerFreq: Long, span: Long, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.fillMaxWidth().height(15.dp).background(Color(0xCC101826))
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val axis = remember(centerFreq, span, widthPx) { freqAxis(centerFreq, span, widthPx, 64f) }
        val left = centerFreq - span / 2
        axis.ticks.forEach { f ->
            val x = ((f - left).toFloat() / span) * widthPx
            val maxX = (widthPx - 46f).coerceAtLeast(2f)
            val xClamped = (x + 2f).coerceIn(2f, maxX)
            Box(Modifier.offset { IntOffset(xClamped.roundToInt(), 0) }) {
                Text(
                    tickLabel(f, axis.stepHz),
                    fontSize = 9.sp,
                    color = Color(0xFF5A6B7F),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}
