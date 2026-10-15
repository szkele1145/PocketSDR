package com.pocketsdr.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.max

/**
 * 频谱 + 瀑布复合视图（竖屏核心显示区）。
 *
 * 上 40%：实时 FFT 谱线（tuned 标记线）
 * 下 60%：瀑布（WaterfallBitmap，一次 drawImage 贴图）
 *
 * 手势：单击跳频，水平拖动平移中心频率。
 */
@Composable
fun SpectrumWaterfall(
    spectrum: FloatArray,
    waterfall: WaterfallBitmap,
    centerFreq: Long,
    tunedFreq: Long,
    /** 显示 span（可小于 fullSpan = 数字变焦） */
    span: Long,
    /** 全采样率对应 span（bin 映射基准） */
    fullSpan: Long,
    dbMin: Float,
    dbMax: Float,
    onTune: (Long) -> Unit,
    onDragTune: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // 双缓冲：key 跟踪 bitmap 引用（发布交换后重组取新页）
    val image = remember(waterfall.bitmap) { waterfall.bitmap.asImageBitmap() }
    val specColor = Color(0xFF00E5FF)
    val specFill = Color(0x3300E5FF)
    val markerColor = Color(0xFFFF5252)

    Canvas(modifier = modifier
        .pointerInput(centerFreq, span) {
            detectTapGestures { offset ->
                val frac = offset.x / size.width.toFloat()
                val f = centerFreq + ((frac - 0.5f) * span).toLong()
                onTune(f)
            }
        }
        .pointerInput(span) {
            var accum = 0f
            detectDragGestures(
                onDragStart = { accum = 0f },
                onDrag = { change, drag ->
                    val hzPerPx = span.toFloat() / max(size.width, 1)
                    accum -= drag.x * hzPerPx
                    if (abs(accum) >= hzPerPx) {
                        onDragTune(accum.toLong())
                        accum = 0f
                    }
                }
            )
        }
        .pointerInput(span) {
            // 捏合缩放 span（1.0 = 全采样率，越小放大越细）
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val zoomChange = event.calculateZoom()
                    if (zoomChange != 1f) {
                        onZoom(zoomChange)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val specH = h * 0.4f

        drawRect(Color(0xFF0B0E14))

        // ---- 瀑布（底部 60%，按 span 视角裁剪源 Bitmap 横向区间） ----
        if (span < fullSpan) {
            val fracL = ((centerFreq - span / 2) - (centerFreq - fullSpan / 2)).toFloat() / fullSpan
            val sx = (fracL * image.width).toInt().coerceIn(0, image.width - 1)
            val sw = (span.toFloat() / fullSpan * image.width).toInt().coerceAtLeast(1)
            drawImage(image,
                srcOffset = androidx.compose.ui.unit.IntOffset(sx, 0),
                srcSize = androidx.compose.ui.unit.IntSize(sw.coerceAtMost(image.width - sx), image.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(0, specH.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), (h - specH).toInt()))
        } else {
            drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), (h - specH).toInt()),
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstOffset = androidx.compose.ui.unit.IntOffset(0, specH.toInt()),
                srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height))
        }

        // ---- 谱线（上 40%，按显示 span 视角取 bin 子集） ----
        if (spectrum.isNotEmpty()) {
            val n = spectrum.size
            val dbRange = max(dbMax - dbMin, 1f)
            // 显示 span 占全采样率的比例 → bin 可见子区间
            val fracL = ((centerFreq - span / 2) - (centerFreq - fullSpan / 2)).toFloat() / fullSpan
            val fracR = fracL + span.toFloat() / fullSpan
            val k0 = (fracL * (n - 1)).toInt().coerceIn(0, n - 1)
            val k1 = (fracR * (n - 1)).toInt().coerceIn(k0 + 1, n)
            val visN = k1 - k0
            val stepX = w / max(visN - 1, 1)
            var prevY = specH * (1f - (spectrum[k0] - dbMin) / dbRange)
            var prevX = 0f
            for (kk in 0 until visN) {
                val k = k0 + kk
                val x = kk * stepX
                val y = specH * (1f - (spectrum[k] - dbMin) / dbRange)
                drawLine(specFill, Offset(x, y), Offset(x, specH), strokeWidth = stepX + 1f)
                if (kk > 0) drawLine(specColor, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2f)
                prevX = x; prevY = y
            }
        }

        // ---- 频率网格（每 MHz 竖线 + 刻度标签） ----
        val mhzStepPx = w * 1_000_000f / span
        if (mhzStepPx > 30f) {  // 太密就不画
            var mhz = (centerFreq / 1_000_000) - (span / 2_000_000)
            var gridX = ((mhz + 1) * 1_000_000 - (centerFreq - span / 2)).toFloat() / span * w
            while (gridX < w) {
                if (gridX > 0) {
                    drawLine(Color(0x1AFFFFFF), Offset(gridX, 0f), Offset(gridX, h), strokeWidth = 1f)
                }
                mhz++
                gridX += mhzStepPx
            }
        }
        // 边缘频率标签（Canvas 外用 Text 叠加，这里画端点短线）
        drawLine(Color(0x33FFFFFF), Offset(0f, 0f), Offset(0f, h), strokeWidth = 1f)
        drawLine(Color(0x33FFFFFF), Offset(w, 0f), Offset(w, h), strokeWidth = 1f)

        // ---- tuned 标记线 ----
        val tunedFrac = ((tunedFreq - centerFreq).toFloat() / span) + 0.5f
        if (tunedFrac in 0f..1f) {
            val x = tunedFrac * w
            drawLine(markerColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
        }

        // ---- 分割线 ----
        drawLine(Color(0xFF1A2230), Offset(0f, specH), Offset(w, specH), strokeWidth = 1f)
    }
}
