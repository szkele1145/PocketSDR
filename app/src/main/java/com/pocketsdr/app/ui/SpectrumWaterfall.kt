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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

/**
 * 频谱 + 瀑布复合视图（横屏核心显示区）。
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
    /** 峰值保持谱线（关闭时全为 -200，画不出屏幕） */
    peak: FloatArray = FloatArray(0),
    centerFreq: Long,
    tunedFreq: Long,
    /** 显示 span（可小于 fullSpan = 数字变焦） */
    span: Long,
    /** 全采样率对应 span（bin 映射基准） */
    fullSpan: Long,
    dbMin: Float,
    dbMax: Float,
    onTune: (Long) -> Unit,
    /** 拖动增量（Hz），正=往高频方向平移中心 */
    onDragTune: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    /** 拖动开始/结束，调用方用它维护"本地权威中心"，避免拿轮询到的旧值当基准 */
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 双缓冲：key 跟踪 bitmap 引用（发布交换后重组取新页）
    val image = remember(waterfall.bitmap) { waterfall.bitmap.asImageBitmap() }
    val specColor = Color(0xFF00E5FF)
    val peakColor = Color(0xFFF6D55C)
    val specFill = Color(0x3300E5FF)
    val markerColor = Color(0xFFFF5252)
    val textMeasurer = rememberTextMeasurer()

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
            var active = false
            detectDragGestures(
                onDragStart = {
                    accum = 0f
                    if (!active) { active = true; onDragStart() }
                },
                onDrag = { change, drag ->
                    val hzPerPx = span.toFloat() / max(size.width, 1)
                    accum -= drag.x * hzPerPx
                    if (abs(accum) >= hzPerPx) {
                        onDragTune(accum.toLong())
                        accum = 0f
                    }
                },
                onDragEnd = { if (active) { active = false; onDragEnd() } },
                onDragCancel = { if (active) { active = false; onDragEnd() } }
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

        // ---- 谱线（上 40%，Path 单次绘制：填充 Path + 描线 Path，替代 2×1024 次 drawLine） ----
        if (spectrum.isNotEmpty()) {
            val n = spectrum.size
            val dbRange = max(dbMax - dbMin, 1f)
            val fracL = ((centerFreq - span / 2) - (centerFreq - fullSpan / 2)).toFloat() / fullSpan
            val fracR = fracL + span.toFloat() / fullSpan
            val k0 = (fracL * (n - 1)).toInt().coerceIn(0, n - 1)
            val k1 = (fracR * (n - 1)).toInt().coerceIn(k0 + 1, n)
            val visN = k1 - k0
            val stepX = w / max(visN - 1, 1)
            // 描线路径
            val line = androidx.compose.ui.graphics.Path()
            // 填充路径（谱线下方整体填充，一次 drawPath 替代 1024 次竖线 drawLine）
            val fill = androidx.compose.ui.graphics.Path()
            var first = true
            for (kk in 0 until visN) {
                val k = k0 + kk
                val x = kk * stepX
                val y = specH * (1f - (spectrum[k] - dbMin) / dbRange)
                if (first) { line.moveTo(x, y); fill.moveTo(x, specH); fill.lineTo(x, y); first = false }
                else { line.lineTo(x, y); fill.lineTo(x, y) }
            }
            if (!first) {
                fill.lineTo(w, specH)
                fill.close()
                drawPath(fill, specFill)
                drawPath(line, specColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            }
        }

        // ---- 峰值保持线（黄色，画在谱线之上；关闭时值全为 -200，落在屏外） ----
        if (peak.size == spectrum.size && peak.isNotEmpty()) {
            val n = peak.size
            val dbRange = max(dbMax - dbMin, 1f)
            val fracL = ((centerFreq - span / 2) - (centerFreq - fullSpan / 2)).toFloat() / fullSpan
            val fracR = fracL + span.toFloat() / fullSpan
            val k0 = (fracL * (n - 1)).toInt().coerceIn(0, n - 1)
            val k1 = (fracR * (n - 1)).toInt().coerceIn(k0 + 1, n)
            val visN = k1 - k0
            val stepX = w / max(visN - 1, 1)
            val pk = androidx.compose.ui.graphics.Path()
            var started = false
            for (kk in 0 until visN) {
                val v = peak[k0 + kk]
                if (v < -190f) { started = false; continue }   // 关闭/无数据：断开不连线
                val x = kk * stepX
                val y = specH * (1f - (v - dbMin) / dbRange)
                if (!started) { pk.moveTo(x, y); started = true } else pk.lineTo(x, y)
            }
            if (started) {
                drawPath(pk, peakColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            }
        }

        // ---- 频率网格（与顶部 FreqRuler 同源刻度：数字和竖线永远对齐） ----
        val axis = freqAxis(centerFreq, span, w, 64f)
        val leftHz = centerFreq - span / 2
        axis.ticks.forEach { f ->
            val gx = ((f - leftHz).toFloat() / span) * w
            if (gx > 0f && gx < w) {
                drawLine(Color(0x1AFFFFFF), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }
        }
        // 边缘频率标签（Canvas 外用 Text 叠加，这里画端点短线）
        drawLine(Color(0x33FFFFFF), Offset(0f, 0f), Offset(0f, h), strokeWidth = 1f)
        drawLine(Color(0x33FFFFFF), Offset(w, 0f), Offset(w, h), strokeWidth = 1f)

        // ---- dB 纵轴网格线（谱区）----
        // 没有 dB 刻度时，用户看不懂频谱高度代表什么：强台该有多高？噪声底在哪？
        // 自动 dB 范围（v2.25）下范围是动态的，刻度步长必须跟着范围选，
        // 否则窄范围时标签会挤成一团、宽范围时又只有稀稀几条。
        if (spectrum.isNotEmpty() || peak.isNotEmpty()) {
            val dbRange = max(dbMax - dbMin, 1f)
            // 目标约 4~6 条网格线：步长从 10/20/25/50/100 里挑
            val step = when {
                dbRange / 4f <= 12f -> 10f
                dbRange / 4f <= 22f -> 20f
                dbRange / 4f <= 30f -> 25f
                dbRange / 4f <= 55f -> 50f
                else -> 100f
            }
            // 起点对齐到步长整数倍，且不超出上界
            var db = kotlin.math.ceil(dbMax / step) * step
            var guard = 0
            while (db >= dbMin && guard++ < 40) {
                val y = specH * (1f - (db - dbMin) / dbRange)
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
                // dB 标签：画在左侧，字号小、半透明，不与谱线争视线
                val label = if (db == 0f) "0" else db.toInt().toString()
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    style = TextStyle(fontSize = 8.sp, color = Color(0x668FA3B8)),
                    topLeft = Offset(2f, (y - 9f).coerceAtLeast(0f))
                )
                db -= step
            }
        }

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
