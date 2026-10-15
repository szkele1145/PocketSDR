package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.SdrEngine
import com.pocketsdr.app.FreqStore
import kotlinx.coroutines.launch

/**
 * 收音页（主页面）：频谱大面积铺满上半屏，下方只有
 * 大频率 + 步进按钮 + 音量滑条——收音场景的全部直接操作。
 * 次要功能（模式/波段/静噪/录音/搜台/收藏）都在设置页。
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
    onExit: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var center by remember { mutableStateOf(engine.centerFreq) }
    var viewSpan by remember { mutableStateOf(engine.sampleRate.toLong()) }
    var volume by remember { mutableStateOf(engine.volume) }
    var mode by remember { mutableStateOf(engine.mode) }
    val ver = remember {
        try { "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        catch (_: Exception) { "" }
    }

    // 引擎状态轮询同步
    LaunchedEffect(Unit) {
        while (true) {
            tuned = engine.tunedFreq
            center = engine.centerFreq
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
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
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
        // ===== 频谱（占满上部，点峰跳频） =====
        Box(Modifier.fillMaxWidth().weight(0.62f)) {
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
                    engine.recenter(center - delta)
                    center = engine.centerFreq
                    tuned = engine.tunedFreq
                },
                onZoom = { z ->
                    viewSpan = (viewSpan / z).toLong().coerceIn(128_000L, engine.sampleRate.toLong())
                },
                modifier = Modifier.fillMaxSize()
            )
            // 信号强度徽标
            Surface(
                color = Color(0xCC101826), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text(
                    "${sMeter(signalDb)}  ${signalDb.toInt()}dB",
                    color = if (signalDb > engine.squelchDb) Color(0xFF00E5FF) else Color(0xFF5A6B7F),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
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

        // ===== 大频率 + 步进 =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatFreq(tuned),
                    fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace
                )
                Text(
                    "${mode.label} · $status",
                    fontSize = 11.sp, color = Color(0xFF5A6B7F),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            // 步进按钮（长按连跳）
            StepButtons(engine, mode) { tuned = engine.tunedFreq }
        }

        // ===== 音量（唯一的滑条，大而好拖） =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
        ) {
            Text("🔊", fontSize = 16.sp)
            Slider(
                value = volume, onValueChange = {
                    volume = it; engine.volume = it
                    FreqStore(context).lastVolume = it
                },
                valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(start = 6.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E5FF),
                    activeTrackColor = Color(0xFF00A8E8),
                    inactiveTrackColor = Color(0xFF1A2230)
                )
            )
            Text(
                "${(volume * 100).toInt()}%",
                fontSize = 12.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 6.dp)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = {
            engine.tuneWithinSpan(engine.tunedFreq + step); onTuned()
        }, modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    startRepeat(true)
                    tryAwaitRelease()
                    repeatJob?.cancel()
                })
            }) {
            Icon(Icons.Filled.KeyboardArrowUp, "步进+", tint = Color(0xFF00E5FF),
                modifier = Modifier.size(36.dp))
        }
        Text(stepLabel, fontSize = 10.sp, color = Color(0xFF5A6B7F))
        IconButton(onClick = {
            engine.tuneWithinSpan(engine.tunedFreq - step); onTuned()
        }, modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    startRepeat(false)
                    tryAwaitRelease()
                    repeatJob?.cancel()
                })
            }) {
            Icon(Icons.Filled.KeyboardArrowDown, "步进-", tint = Color(0xFF00E5FF),
                modifier = Modifier.size(36.dp))
        }
    }
}
