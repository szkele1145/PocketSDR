package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
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
    // SharedPreferences 只建一次：之前每次滑条回调都 new 一个 FreqStore 并写盘，
    // 拖动一秒就是几十次磁盘写入，既是浪费也会让滑条发涩。
    val store = remember { FreqStore(context) }
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var center by remember { mutableStateOf(engine.centerFreq) }
    var viewSpan by remember { mutableStateOf(engine.sampleRate.toLong()) }
    var volume by remember { mutableStateOf(engine.volume) }
    var mode by remember { mutableStateOf(engine.mode) }
    var squelch by remember { mutableStateOf(engine.squelchDb) }
    var recording by remember { mutableStateOf(engine.isRecording) }
    var showFreqInput by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    val favorites by remember { mutableStateOf(store.getFavorites()) }
    // 拖动期间的"本地权威中心"：老代码把增量加到 250ms 轮询来的 center 上，
    // 轮询落后于手指时基准就错了，越拖越偏。现在拖动自己累积基准，抬指才交还引擎。
    var dragBase by remember { mutableStateOf<Long?>(null) }

    // 启动时恢复上次收听的频率与模式：打开即听，不必重新输入
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!restored) {
            restored = true
            val f = store.lastFreq
            engine.tuneTo(f)
            engine.selectMode(FreqStore.modeFromIndex(store.lastMode))
            tuned = f
            mode = FreqStore.modeFromIndex(store.lastMode)
            center = engine.centerFreq
        }
    }

    // 引擎状态轮询同步（250ms 足够跟手，又不会给 UI 线程加压）
    LaunchedEffect(Unit) {
        while (true) {
            val newTuned = engine.tunedFreq
            if (newTuned != tuned) {
                tuned = newTuned
                store.lastFreq = newTuned
                store.lastCenter = engine.centerFreq
            }
            val newMode = engine.mode
            if (newMode != mode) {
                mode = newMode
                store.lastMode = FreqStore.modeIndex(newMode)
            }
            recording = engine.isRecording
            if (dragBase == null) center = engine.centerFreq
            kotlinx.coroutines.delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        // ===== 频率刻度行（与频谱网格同源，跟随 viewSpan 变焦） =====
        FreqRuler(centerFreq = center, span = viewSpan)

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
                    // 以拖动自己的累积基准推进，不依赖轮询值
                    val base = dragBase ?: center.also { dragBase = it }
                    val next = base + delta
                    dragBase = next
                    engine.recenter(next)
                    center = next
                    tuned = engine.tunedFreq
                },
                onZoom = { z ->
                    viewSpan = (viewSpan / z).toLong().coerceIn(128_000L, engine.sampleRate.toLong())
                },
                onDragStart = { dragBase = center },
                onDragEnd = {
                    dragBase = null
                    center = engine.centerFreq
                    tuned = engine.tunedFreq
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
            // 视野宽度指示 / 一键复位：捏合缩放没有可见入口，必须给个能点回来的按钮
            val fullSpan = engine.sampleRate.toLong()
            val zoomed = viewSpan < fullSpan
            Surface(
                color = if (zoomed) Color(0xCC1C3550) else Color(0x88101826),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clickable { viewSpan = fullSpan }
            ) {
                Text(
                    "视野 " + formatSpan(viewSpan) + if (zoomed) "  ⟲" else "",
                    color = if (zoomed) Color(0xFF7DF3FF) else Color(0xFF5A6B7F),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // ===== 底部控制条 =====
        Surface(color = Color(0xFF101826), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                // 第一行：大频率 + 步进 + 音量
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.clickable { showFreqInput = true }) {
                            Column {
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
                        }
                    }
                    // ★ 收藏台：主页一触即达，不翻设置页
                    Surface(
                        color = if (favorites.isNotEmpty()) Color(0xFF00E5FF) else Color(0xFF1A2230),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .widthIn(min = 56.dp)
                            .clickable { showFavorites = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "★ ${favorites.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (favorites.isNotEmpty()) Color(0xFF06121A) else Color(0xFF8FA3B8),
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                    }
                    // 录音文件：主页一触即达，播放/分享/删不翻页
                    Surface(
                        color = Color(0xFF1A2230),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .widthIn(min = 56.dp)
                            .clickable { showRecordings = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "录音文件",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    StepButtons(engine, mode) { tuned = engine.tunedFreq }
                    Spacer(Modifier.width(14.dp))
                    Text("🔊", fontSize = 15.sp)
                    Slider(
                        value = volume,
                        onValueChange = { volume = it; engine.volume = it },
                        // 只在松手时落盘：拖动过程中每帧写 SharedPreferences 会发涩
                        onValueChangeFinished = { store.lastVolume = volume },
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
                    Spacer(Modifier.width(14.dp))
                    // 录音开关：录音中红底高亮，一眼可见；未录音灰底。
                    OutlinedButton(
                        onClick = {
                            if (recording) {
                                engine.stopRecording()
                                recording = false
                            } else {
                                val ok = engine.startRecording(context)
                                if (ok) recording = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (recording) Color(0xFFFF6B6B) else Color(0xFF8FA3B8),
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = if (recording) "停止录音" else "开始录音",
                                tint = if (recording) Color(0xFFFF6B6B) else Color(0xFF8FA3B8),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                if (recording) "录音中" else "录音",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (recording) Color(0xFFFF6B6B) else Color(0xFF8FA3B8),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
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
                        value = squelch,
                        onValueChange = { squelch = it; engine.squelchDb = it },
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
            // 直接输入频率 / 收藏台弹窗（覆盖显示，不翻设置页）
            if (showFreqInput) {
                FreqInputDialog(engine, onDismiss = { showFreqInput = false })
            }
            if (showFavorites) {
                FavoritesDialog(
                    store,
                    engine,
                    favorites,
                    onChange = {},
                    onRename = { _, _ -> },
                    onDismiss = { showFavorites = false }
                )
            }
            if (showRecordings) {
                RecordingsDialog(onDismiss = { showRecordings = false })
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

/** 步进按钮对（模式自适应步长；轻点走一步，按住连跳，松手即停） */
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepArrow(up = false, step = step, engine = engine, onTuned = onTuned)
        Text(
            stepLabel, fontSize = 10.sp, color = Color(0xFF5A6B7F),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp),
            textAlign = TextAlign.Center
        )
        StepArrow(up = true, step = step, engine = engine, onTuned = onTuned)
    }
}

/**
 * 单个步进箭头。手势全部自己处理，不再叠 IconButton.onClick，
 * 避免"点一下却跳两步"以及松手后又多跳一次的老问题。
 */
@Composable
private fun StepArrow(up: Boolean, step: Long, engine: SdrEngine, onTuned: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(46.dp)
            .pointerInput(step, up) {
                detectTapGestures(onPress = {
                    val dir = if (up) step else -step
                    // 按下立刻走一步，手感跟手
                    engine.tuneWithinSpan(engine.tunedFreq + dir)
                    onTuned()
                    val job = scope.launch {
                        kotlinx.coroutines.delay(400)   // 长按判定门槛
                        while (true) {
                            engine.tuneWithinSpan(engine.tunedFreq + dir)
                            onTuned()
                            kotlinx.coroutines.delay(60)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()                        // 松手立即停，不会惯性乱跳
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            if (up) "步进+" else "步进-",
            tint = Color(0xFF00E5FF),
            modifier = Modifier.size(32.dp)
        )
    }
}
