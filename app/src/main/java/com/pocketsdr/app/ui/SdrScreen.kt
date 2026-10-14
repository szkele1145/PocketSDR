package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.SdrEngine
import com.pocketsdr.app.source.IQSource
import kotlin.math.roundToLong

/**
 * 竖屏主界面：
 * 上半屏 SpectrumWaterfall（可拖动调谐）
 * 下半屏控制区（频率大数字 + 模式/WFM 波段/音量/静噪 + 源切换）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdrScreen(
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
    val modes = remember { SdrEngine.Mode.entries.toList() }
    var mode by remember { mutableStateOf(engine.mode) }
    var volume by remember { mutableStateOf(engine.volume) }
    var squelch by remember { mutableStateOf(engine.squelchDb) }
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var center by remember { mutableStateOf(engine.centerFreq) }
    // 显示 span（数字变焦）：1.024M 全景 → 最小 128k
    var viewSpan by remember { mutableStateOf(engine.sampleRate.toLong()) }
    var recording by remember { mutableStateOf(engine.isRecording) }
    var showFreqInput by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var recStartMs by remember { mutableStateOf(0L) }
    var recTick by remember { mutableStateOf(0) }
    var scanProgress by remember { mutableStateOf(-1) }
    var scanFound by remember { mutableStateOf(listOf<com.pocketsdr.app.FmScanner.Station>()) }
    val store = remember { com.pocketsdr.app.FreqStore(context) }
    var favorites by remember { mutableStateOf(store.getFavorites()) }

    // 引擎状态变化 → UI 同步
    LaunchedEffect(Unit) {
        while (true) {
            tuned = engine.tunedFreq
            center = engine.centerFreq
            kotlinx.coroutines.delay(200)
        }
    }

    // 调谐/模式变化 → 刷新前台服务通知
    LaunchedEffect(tuned, mode) {
        try {
            val intent = android.content.Intent(context, com.pocketsdr.app.RxService::class.java)
                .putExtra("freq", tuned)
                .putExtra("mode", mode.label)
            // Android 12+ 后台启动前台服务受限——App 在前台时才刷新通知，失败静默
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException：App 已不在前台，通知保持旧值即可
            }
        } catch (_: Exception) {}
    }

    // 录音中：每 500ms 触发重组刷新时长显示
    LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(500)
            recTick++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .statusBarsPadding()
    ) {
        // ===== 频谱区 =====
        Box(Modifier.fillMaxWidth().weight(0.52f)) {
            Column(Modifier.fillMaxSize()) {
                // 频率刻度行（跟随 viewSpan 变焦视角）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC101826))
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "%.1f".format((center - viewSpan / 2) / 1e6) + "M",
                        fontSize = 9.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "%.1f".format(center / 1e6) + "M",
                        fontSize = 9.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "%.1f".format((center + viewSpan / 2) / 1e6) + "M",
                        fontSize = 9.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace
                    )
                }
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
                        // 捏合: span 限制在 [128k, 采样率]，双指张开缩小 span=放大
                        val s = (viewSpan / z).toLong()
                        viewSpan = s.coerceIn(128_000L, engine.sampleRate.toLong())
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 信号强度徽标（S 表 + dBFS 双显示）
            Surface(
                color = Color(0xCC101826), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                val smeter = sMeter(signalDb)
                Text(
                    text = "$smeter  ${signalDb.toInt()}dB",
                    color = if (signalDb > squelch) Color(0xFF00E5FF) else Color(0xFF5A6B7F),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // ===== 控制区 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f)
                .padding(horizontal = 14.dp)
        ) {
            // ---- 频率显示 + 微调 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatFreq(tuned),
                    fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures { showFreqInput = true }
                        }
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 模式自适应步进：WFM 100k / NFM 12.5k / AM 25k / SSB 100Hz
                    val step = remember(mode) {
                        when (mode) {
                            SdrEngine.Mode.WFM -> 100_000L
                            SdrEngine.Mode.NFM -> 12_500L
                            SdrEngine.Mode.AM -> 25_000L
                            else -> 100L
                        }
                    }
                    // 长按连续步进（400ms 后每 60ms 一格）
                    var repeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                    IconButton(
                        onClick = { engine.tuneWithinSpan(tuned + step); tuned += step },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    startRepeat(true)
                                    val released = tryAwaitRelease()
                                    if (released) repeatJob?.cancel()
                                }
                            )
                        }
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, "步进+", tint = Color(0xFF00E5FF))
                    }
                    // 步长标签：≥1MHz 显示 M，≥1kHz 显示 k（保留 0.5 精度），<1k 显示 Hz
                    val stepLabel = when {
                        step >= 1_000_000 -> "±${"%.1f".format(step / 1e6)}M"
                        step >= 1_000 -> if (step % 1_000 == 0L) "±${step / 1_000}k" else "±${"%.1f".format(step / 1e3)}k"
                        else -> "±${step}Hz"
                    }
                    Text(stepLabel, fontSize = 9.sp, color = Color(0xFF5A6B7F))
                    IconButton(
                        onClick = { engine.tuneWithinSpan(tuned - step); tuned -= step },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    startRepeat(false)
                                    val released = tryAwaitRelease()
                                    if (released) repeatJob?.cancel()
                                }
                            )
                        }
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "步进-", tint = Color(0xFF00E5FF)) }
                }
            }

            // ---- 模式行（横向滚动，5 模式不挤） ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                modes.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            engine.selectMode(m)
                        },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF16202F),
                            selectedContainerColor = Color(0xFF00E5FF),
                            labelColor = Color(0xFF8FA3B8),
                            selectedLabelColor = Color(0xFF0B0E14)
                        )
                    )
                }
            }

            // ---- 波段快捷 + 录音 + 收藏 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 波段带默认模式（点击后自动切模式）
                val bands = listOf(
                    Triple("FM", 98_000_000L, SdrEngine.Mode.WFM),
                    Triple("航空", 121_500_000L, SdrEngine.Mode.AM),
                    Triple("2m", 145_500_000L, SdrEngine.Mode.NFM),
                    Triple("NOAA", 137_100_000L, SdrEngine.Mode.NFM),
                    Triple("40m", 7_074_000L, SdrEngine.Mode.LSB),
                    Triple("20m", 14_300_000L, SdrEngine.Mode.USB)
                )
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                ) {
                    bands.forEach { (name, f, m) ->
                        OutlinedButton(
                            onClick = {
                                engine.selectMode(m)
                                mode = m
                                engine.tuneTo(f)
                                tuned = engine.tunedFreq
                                center = engine.centerFreq
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                        ) { Text(name, fontSize = 12.sp) }
                    }
                }
                IconButton(onClick = {
                    if (recording) {
                        engine.stopRecording()
                        recording = false
                    } else {
                        recording = engine.startRecording(context)
                        if (recording) recStartMs = System.currentTimeMillis()
                    }
                }, modifier = Modifier.size(36.dp).pointerInput(Unit) {
                    detectTapGestures(onLongPress = { showRecordings = true })
                }) {
                    Icon(
                        if (recording) Icons.Filled.StopCircle else Icons.Filled.RadioButtonUnchecked,
                        "录音",
                        tint = if (recording) Color(0xFFFF4040) else Color(0xFF8FA3B8)
                    )
                }
                // 录制中：实时时长徽标（与 LaunchedEffect 同步循环）
                if (recording) {
                    recTick += 1
                    @Suppress("UNUSED_EXPRESSION") recTick
                    val sec = (System.currentTimeMillis() - recStartMs) / 1000
                    Text(
                        "%d:%02d".format(sec / 60, sec % 60),
                        fontSize = 10.sp, color = Color(0xFFFF4040), fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = { showFavorites = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.StarBorder, "收藏", tint = Color(0xFFF6D55C))
                }
                IconButton(onClick = { showScan = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Search, "搜台", tint = Color(0xFF7DF3FF))
                }
            }

            // ---- 音量/静噪 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, null, tint = Color(0xFF5A6B7F), modifier = Modifier.size(18.dp))
                Slider(
                    value = volume, onValueChange = {
                        volume = it; engine.volume = it
                    },
                    valueRange = 0f..1f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00A8E8)
                    )
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("静噪", fontSize = 11.sp, color = Color(0xFF5A6B7F))
                Slider(
                    value = squelch, onValueChange = {
                        squelch = it; engine.squelchDb = it
                    },
                    valueRange = -80f..-10f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF5252),
                        activeTrackColor = Color(0xFFFF7961)
                    )
                )
                Text("${squelch.toInt()}", fontSize = 11.sp, color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace)
            }

            // ---- 状态行（长按弹日志面板）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { showLog = true })
                    }
            ) {
                Column(Modifier.weight(1f)) {
                    // 状态行带版本号
                    val ver = remember {
                        try {
                            "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (_: Exception) { "" }
                    }
                    Text("$status · $ver", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                    if (mode == SdrEngine.Mode.WFM && rdsName.isNotEmpty()) {
                        Text(
                            "RDS: $rdsName",
                            fontSize = 12.sp,
                            color = Color(0xFF7DF3FF),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (mode == SdrEngine.Mode.WFM && stereoLock > 0.5f) {
                    Text("ST", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onExit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "退出", tint = Color(0xFF5A6B7F))
                }
            }

            // ---- 硬件增益控制（仅真实硬件显示） ----
            if (engine.isRealSource) {
                var manualGain by remember { mutableStateOf(false) }
                var gainIdx by remember { mutableStateOf(14f) }  // 默认 7.1dB 档
                var hfDirect by remember { mutableStateOf(engine.hardwareDirectSampling != 0) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = hfDirect,
                        onCheckedChange = {
                            hfDirect = it
                            engine.setHardwareDirectSampling(if (it) 1 else 0)
                        },
                        modifier = Modifier.size(36.dp),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFF6D55C)
                        )
                    )
                    Text("HF直采", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                    androidx.compose.material3.Switch(
                        checked = manualGain,
                        onCheckedChange = {
                            manualGain = it
                            engine.setHardwareGainMode(it)
                        },
                        modifier = Modifier.size(36.dp),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF)
                        )
                    )
                    Text("增益", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                    if (manualGain) {
                        Slider(
                            value = gainIdx,
                            onValueChange = {
                                gainIdx = it
                                val gains = engine.lnaGains
                                engine.setHardwareLnaGain(gains[it.toInt().coerceIn(0, gains.size - 1)])
                            },
                            valueRange = 0f..(engine.lnaGains.size - 1).toFloat(),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFF6D55C),
                                activeTrackColor = Color(0xFFF6D55C)
                            )
                        )
                        Text(
                            "%.1f dB".format(engine.lnaGains[gainIdx.toInt()] / 10f),
                            fontSize = 11.sp, color = Color(0xFFF6D55C), fontFamily = FontFamily.Monospace
                        )
                    }
                }
                // ---- 第二行：offset tuning + RTL AGC（进阶） ----
                var offTune by remember { mutableStateOf(false) }
                var rtlAgcOn by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = offTune,
                        onCheckedChange = {
                            offTune = engine.setHardwareOffsetTuning(it)
                        },
                        modifier = Modifier.size(36.dp),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF7DF3FF)
                        )
                    )
                    Text("偏移调谐", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                    androidx.compose.material3.Switch(
                        checked = rtlAgcOn,
                        onCheckedChange = {
                            rtlAgcOn = it
                            engine.setHardwareRtlAgc(it)
                        },
                        modifier = Modifier.size(36.dp),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFF9800)
                        )
                    )
                    Text("RTL AGC", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "采样率 ${"%.3f".format(engine.sampleRate / 1e6)}M",
                        fontSize = 10.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // ---- 频率输入对话框 ----
    if (showFreqInput) {
        var text by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFreqInput = false },
            title = { Text("输入频率 (MHz)") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("98.1") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // 容错：全角点/逗号转半角点；范围限制 0.1–1800 MHz
                    val normalized = text.trim().replace('．', '.').replace(',', '.').replace('。', '.')
                    normalized.toFloatOrNull()?.let { mhz ->
                        if (mhz in 0.1f..1800f) {
                            val f = (mhz * 1_000_000).toLong()
                            engine.tuneTo(f)
                            tuned = engine.tunedFreq
                            center = engine.centerFreq
                        }
                    }
                    showFreqInput = false
                }) { Text("调谐") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showFreqInput = false }) { Text("取消") }
            }
        )
    }

    // ---- 收藏列表对话框 ----
    if (showFavorites) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFavorites = false },
            title = { Text("收藏频道") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (favorites.isEmpty()) {
                        Text("暂无收藏。收听到喜欢的台后点 ★+ 添加。", fontSize = 13.sp)
                    }
                    favorites.forEach { ch ->
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.TextButton(onClick = {
                                engine.tuneTo(ch.freq)
                                engine.selectMode(com.pocketsdr.app.FreqStore.modeFromIndex(ch.mode))
                                mode = engine.mode
                                tuned = engine.tunedFreq
                                center = engine.centerFreq
                                showFavorites = false
                            }) {
                                Text("${ch.name}  ${formatFreq(ch.freq)}", fontSize = 14.sp)
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            androidx.compose.material3.IconButton(onClick = {
                                store.removeFavorite(ch.freq)
                                favorites = store.getFavorites()
                            }) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F),
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // 收藏当前频率
                    val name = formatFreq(tuned)
                    store.addFavorite(com.pocketsdr.app.FreqStore.Ch(name, tuned, com.pocketsdr.app.FreqStore.modeIndex(mode)))
                    favorites = store.getFavorites()
                }) { Text("★ 收藏当前") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showFavorites = false }) { Text("关闭") }
            }
        )
    }

    // ---- 搜台对话框 ----
    if (showScan) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showScan = false
                scanProgress = -1
            },
            title = { Text(if (scanProgress >= 0) "搜索中… $scanProgress% (${scanFound.size} 台)" else "FM 自动搜台") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        if (scanProgress >= 0) "扫描 87.5–108 MHz，请稍候"
                        else "扫描整个 FM 广播波段（87.5–108 MHz），找到所有信号峰值。约需 20 秒。",
                        fontSize = 13.sp
                    )
                    if (scanProgress >= 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { scanProgress / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = Color(0xFF00E5FF)
                        )
                    }
                    scanFound.take(12).forEach { st ->
                        androidx.compose.material3.TextButton(onClick = {
                            engine.tuneTo(st.freq)
                            tuned = engine.tunedFreq
                            center = engine.centerFreq
                            showScan = false
                        }) {
                            Text("${formatFreq(st.freq)}   ${st.levelDb.toInt()} dBFS", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (scanProgress < 0 || scanProgress > 100) {
                        scanProgress = 0
                        scanFound = emptyList()
                        val scanner = com.pocketsdr.app.FmScanner(
                            engine,
                            onProgress = { p, n ->
                                scanProgress = p
                            },
                            onDone = { stations ->
                                // 按信号强度排序（强的台排前面，好台优先）
                                scanFound = stations.sortedByDescending { it.levelDb }
                                scanProgress = 101  // 完成标记
                            }
                        )
                        scanner.scan(87_500_000, 108_000_000)
                    }
                }) { Text(if (scanProgress < 0) "开始扫描" else if (scanProgress > 100) "重新扫描" else "扫描中…") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    // 扫描完成 → 全部存收藏
                    if (scanProgress > 100 && scanFound.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = {
                            scanFound.forEach { st ->
                                store.addFavorite(com.pocketsdr.app.FreqStore.Ch(
                                    formatFreq(st.freq), st.freq, 0))
                            }
                            favorites = store.getFavorites()
                        }) { Text("★ 全存收藏") }
                    }
                    androidx.compose.material3.TextButton(onClick = { showScan = false }) { Text("关闭") }
                }
            }
        )
    }

    // ---- 日志面板对话框 ----
    if (showRecordings) {
        RecordingsDialog(onDismiss = { showRecordings = false })
    }

    if (showLog) {
        val logLines = remember { com.pocketsdr.app.SdrLog.dump() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("运行日志 (${logLines.size})") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .height(320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        androidx.compose.foundation.layout.Column {
                            logLines.take(200).forEach {
                                Text(it, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    color = when {
                                        it.contains("/E/") -> Color(0xFFFF5252)
                                        it.contains("/W/") -> Color(0xFFF6D55C)
                                        else -> Color(0xFF8FA3B8)
                                    })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val path = com.pocketsdr.app.SdrLog.save(context)
                    if (path != null) {
                        android.widget.Toast.makeText(context, "已保存: $path", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("落盘") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLog = false }) { Text("关闭") }
            }
        )
    }
}

private fun formatFreq(f: Long): String {
    val m = f / 1e6
    return if (f % 1_000_000 == 0L) "%.0f MHz".format(m) else "%.4f MHz".format(m)
}

/** dBFS → 业余 S 表（S9=-30dBFS 基准，6dB/S 单位，S9+ 以 dB 超载显示） */
private fun sMeter(dbfs: Float): String {
    return when {
        dbfs >= -30 -> "S9+${((dbfs + 30) / 6).toInt() * 6}dB"
        dbfs >= -78 -> "S${((dbfs + 78) / 6).toInt() + 1}"
        else -> "S1"
    }
}
