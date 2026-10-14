package com.pocketsdr.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 最小单位：1px 对应的频率 */
private const val TAG = "SpectrumWaterfall"

/**
 * 频谱 + 瀑布复合视图（竖屏核心显示区）。
 *
 * 上 40%：实时 FFT 谱线（带 tuned 标记线）
 * 下 60%：瀑布历史（Bitmap 逐行下移，约 15 行/秒）
 *
 * 手势：
 *  - 单指拖：调谐（拖 1px = span/宽 Hz，灵敏但可捏合改 span）
 *  - 单击：跳到该频率（频谱区）
 */
@Composable
fun SpectrumWaterfall(
    spectrum: FloatArray,
    waterfall: ArrayDeque<FloatArray>,
    centerFreq: Long,
    tunedFreq: Long,
    span: Long,
    dbMin: Float,
    dbMax: Float,
    onTune: (Long) -> Unit,
    onDragTune: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragAccum = 0L
    val displaySpan = max(span, 1L)

    Canvas(modifier = modifier
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                val frac = offset.x / size.width.toFloat()
                val f = centerFreq + ((frac - 0.5f) * displaySpan).toLong()
                onTune(f)
            }
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { dragAccum = 0 },
                onDrag = { change, drag ->
                    val hzPerPx = displaySpan / max(size.width, 1)
                    dragAccum -= (drag.x * hzPerPx).toLong()
                    if (abs(dragAccum) >= hzPerPx) {
                        onDragTune(dragAccum)
                        dragAccum = 0
                    }
                }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        val specH = h * 0.4f

        // ---- 背景渐变 ----
        drawRect(Color(0xFF0B0E14))

        // ---- 谱线 ----
        if (spectrum.isNotEmpty()) {
            val n = spectrum.size
            val dbRange = max(dbMax - dbMin, 1f)
            val path = androidx.compose.ui.graphics.Path()
            for (k in 0 until n) {
                val x = k.toFloat() / (n - 1) * w
                val y = specH * (1f - (spectrum[k] - dbMin) / dbRange)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // 底封口
            path.lineTo(w, specH)
            path.lineTo(0f, specH)
            path.close()
            drawPath(path, Color(0x3300E5FF))
            // 描线
            val line = androidx.compose.ui.graphics.Path()
            for (k in 0 until n) {
                val x = k.toFloat() / (n - 1) * w
                val y = specH * (1f - (spectrum[k] - dbMin) / dbRange)
                if (k == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(line, Color(0xFF00E5FF))
        }

        // ---- tuned 标记线 ----
        val tunedFrac = ((tunedFreq - centerFreq).toFloat() / displaySpan) + 0.5f
        if (tunedFrac in 0f..1f) {
            val x = tunedFrac * w
            drawLine(Color(0xFFFF5252), androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, specH), strokeWidth = 2f)
            // 顶角标
            drawRect(Color(0xFFFF5252), androidx.compose.ui.geometry.Offset(x - 8f, 0f),
                androidx.compose.ui.geometry.Size(16f, 6f))
        }

        // ---- 分割线 ----
        drawLine(Color(0xFF1A2230), androidx.compose.ui.geometry.Offset(0f, specH),
            androidx.compose.ui.geometry.Offset(w, specH), strokeWidth = 1f)

        // ---- 瀑布（逐行色带，新行在上） ----
        val wfTop = specH + 1f
        val wfH = h - wfTop
        if (waterfall.isNotEmpty()) {
            val rowH = min(4f, wfH / waterfall.size)
            var y = wfTop
            for (row in waterfall) {
                var x = 0f
                val px = w / row.size
                for (k in row.indices) {
                    val v = ((row[k] - dbMin) / max(dbMax - dbMin, 1f)).coerceIn(0f, 1f)
                    drawRect(WF_COLORS[(v * 255).toInt()], androidx.compose.ui.geometry.Offset(x, y),
                        androidx.compose.ui.geometry.Size(px + 1f, rowH))
                    x += px
                }
                y += rowH
                if (y > h) break
            }
        }
    }
}

/** 预计算 256 级瀑布色（黑→蓝→青→黄→红→白 SDR 经典配色），避免每像素计算颜色 */
private val WF_COLORS = Array(256) { idx ->
    val v = idx / 255f
    when {
        v < 0.10f -> Color(0xFF050B12)
        v < 0.22f -> Color(0xFF0D2B52)
        v < 0.34f -> Color(0xFF0A5FA8)
        v < 0.46f -> Color(0xFF00A8E8)
        v < 0.58f -> Color(0xFF00E5FF)
        v < 0.70f -> Color(0xFF7DF3FF)
        v < 0.82f -> Color(0xFFF6D55C)
        v < 0.93f -> Color(0xFFFF4040)
        else -> Color(0xFFFFF0F0)
    }
}
