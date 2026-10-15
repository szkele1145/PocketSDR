package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.FreqStore
import com.pocketsdr.app.SdrEngine
import kotlinx.coroutines.launch

/**
 * 收音页（横屏主界面）——布局照 SDR# 桌面版：
 *   ① 顶部频率刻度（跟随变焦视角）
 *   ② 中部频谱 + 瀑布铺满（点峰跳频 / 拖拽移中心 / 双指缩放）
 *   ③ 底部控制条两行：大频率 + 步进 + 音量 ／ 模式直选 + 静噪
 * 一切常用操作都在一屏内一指点到，不需要翻页。
 */
@Composable
fun RxPage(
    engine: SdrEngine,
    spectrum: FloatArray,
    waterfall: WaterfallBitmap,
    status: String,
    signalDb: Float,
    stereoLock: Float,
    rdsName: String,
    onExit: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var center by remember { mutableStateOf(engine.centerFreq) }
    var viewSpan by remember { mutableStateOf(engine.sampleRate.toLong()) }
    var volume by remember { mutableStateOf(engine.volume) }
    var mode by remember { mutableStateOf(engine.mode) }
    var squelch by remember { mutableStateOf(engine.squelchDb) }

    // 引擎状态轮询同步
    LaunchedEffect(Unit) {
        while (true) {
            tuned = engine.tunedFreq
            center = engine.centerFreq
            mode = engine.mode
            kotlinx.coroutines.delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        // ===== 频率刻度行（跟随 viewSpan 变焦视角） =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC101826))
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "%.1fM".format((center - viewSpan / 2) / 1e6),
                fontSize = 9.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace
            )
            Text(
                "%.1fM".format(center / 1e6),
                fontSize = 9.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace
            )
            Text(
                "%.1fM".format((center + viewSpan / 2) / 1e6),
                fontSize = 9.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace
            )
        }

        // ===== 频谱 + 瀑布（占满剩余空间，横屏主体） =====
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SpectrumWaterfall(
                spectrum = spectrum,
                waterfall = waterfall,
                centerFreq = center,
                tunedFreq = tuned,
                span = viewSpan,
                fullSpan = engine.sampleRate.toLong(),
                dbMin = -90f, dbMax = 0f,
                onTune = { f ->
                    engine.tuneWithinSpan(f)
                    tuned = engine.tunedFreq
                },
                onDragTune = { delta ->
                    engine.recenter(center + delta)
                    center = engine.centerFreq
                    tuned = engine.tunedFreq
                },
                onZoom = { z ->
                    viewSpan = (viewSpan / z).toLong().coerceIn(128_000L, engine.sampleRate.toLong())
                },
                modifier = Modifier.fillMaxSize()
            )
            // RDS 电台名（锁定后左上角）
            if (mode == SdrEngine.Mode.WFM && rdsName.isNotEmpty()) {
                Surface(
                    color = Color(0xCC101826), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Text(
                        "📻 $rdsName" + if (stereoLock > 0.5f) " ·ST" else "",
                        color = Color(0xFF7DF3FF), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // ===== 底部控制条 =====
        Surface(color = Color(0xFF101826), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                // 第一行：大频率 + 步进 + 音量
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatFreq(tuned),
                            fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            "${mode.label} · $status" + "  ${sMeter(signalDb)} ${signalDb.toInt()}dB",
                            fontSize = 10.sp, color = Color(0xFF5A6B7F), maxLines = 1,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    StepButtons(engine, mode) { tuned = engine.tunedFreq }
                    Spacer(Modifier.width(14.dp))
                    Text("🔊", fontSize = 15.sp)
                    Slider(
                        value = volume, onValueChange = {
                            volume = it; engine.volume = it
                            FreqStore(context).lastVolume = it
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.width(150.dp).padding(start = 4.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00A8E8),
                            inactiveTrackColor = Color(0xFF1A2230)
                        )
                    )
                    Text(
                        "${(volume * 100).toInt()}%",
                        fontSize = 11.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp).width(34.dp)
                    )
                }

                // 第二行：模式直选（横向排开，一点即换）+ 静噪
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SdrEngine.Mode.entries.forEach { m ->
                        ModeButton(m.label, mode == m) {
                            engine.selectMode(m)
                            mode = m
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("静噪", fontSize = 10.sp, color = Color(0xFF5A6B7F))
                    Slider(
                        value = squelch, onValueChange = { squelch = it; engine.squelchDb = it },
                        valueRange = -80f..-10f,
                        modifier = Modifier.width(120.dp).padding(start = 4.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF5252),
                            activeTrackColor = Color(0xFFFF7961),
                            inactiveTrackColor = Color(0xFF1A2230)
                        )
                    )
                    Text(
                        "${squelch.toInt()}dB",
                        fontSize = 11.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp).width(42.dp)
                    )
                }
            }
        }
    }
}

/** 模式按钮：选中高亮实心，一眼看得出当前收听类型 */
@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0xFF00E5FF) else Color(0xFF1A2230),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 56.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color(0xFF06121A) else Color(0xFF8FA3B8),
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

/** 步进按钮对（模式自适应步长，长按连续） */
@Composable
private fun StepButtons(engine: SdrEngine, mode: SdrEngine.Mode, onTuned: () -> Unit) {
    val step = remember(mode) {
        when (mode) {
            SdrEngine.Mode.WFM -> 100_000L
            SdrEngine.Mode.NFM -> 12_500L
            SdrEngine.Mode.AM -> 25_000L
            else -> 100L
        }
    }
    val stepLabel = when {
        step >= 1_000_000 -> "%.1fM".format(step / 1e6)
        step >= 1_000 -> if (step % 1_000 == 0L) "${step / 1_000}k" else "%.1fk".format(step / 1e3)
        else -> "${step}Hz"
    }
    var repeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    fun startRepeat(up: Boolean) {
        repeatJob?.cancel()
        repeatJob = scope.launch {
            kotlinx.coroutines.delay(400)
            while (true) {
                val f = engine.tunedFreq + if (up) step else -step
                engine.tuneWithinSpan(f)
                kotlinx.coroutines.delay(60)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            engine.tuneWithinSpan(engine.tunedFreq - step); onTuned()
        }, modifier = Modifier
            .size(46.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    startRepeat(false)
                    tryAwaitRelease()
                    repeatJob?.cancel()
                })
            }) {
            Icon(Icons.Filled.KeyboardArrowDown, "步进-", tint = Color(0xFF00E5FF),
                modifier = Modifier.size(32.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stepLabel, fontSize = 10.sp, color = Color(0xFF5A6B7F),
                fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = {
            engine.tuneWithinSpan(engine.tunedFreq + step); onTuned()
        }, modifier = Modifier
            .size(46.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    startRepeat(true)
                    tryAwaitRelease()
                    repeatJob?.cancel()
                })
            }) {
            Icon(Icons.Filled.KeyboardArrowUp, "步进+", tint = Color(0xFF00E5FF),
                modifier = Modifier.size(32.dp))
        }
    }
}
