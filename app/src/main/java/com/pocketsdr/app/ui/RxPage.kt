package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.geometry.Offset
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
    /** 峰值保持谱线（开关关闭时全为 -200，画不出来） */
    peak: FloatArray = FloatArray(0),
    status: String,
    signalDb: Float,
    stereoLock: Float,
    rdsName: String,
    rdsLocked: Boolean = false,
    /** 插着 USB 设备但没连上时的提示条 */
    usbPending: String? = null,
    /** 提示条上的「去连接」→ 跳设置页 USB 诊断 */
    onOpenUsbDiag: () -> Unit = {},
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
    // USB 健康警告（仅真机）：读超时/端点复位是供电与线材问题的直接证据
    var usbWarn by remember { mutableStateOf<String?>(null) }
    // 静噪开闭状态（引擎侧）：用来给「信号质量」下结论
    var squelchOpen by remember { mutableStateOf(engine.squelchIsOpen) }
    // 信号历史（最近 ~30 秒，250ms 一个点）：架天线时比盯瀑布更直观
    // 采集信号历史：满了丢最旧的，容量固定不增长。
    // 连频率一起记：换了台之后，旧台的信号强度和新台放在同一条曲线上毫无意义。
    // 画线时频率不同就断开，用户一眼能看出「换台了」。
    val signalHistory = remember { mutableStateListOf<Pair<Long, Float>>() }
    var recording by remember { mutableStateOf(engine.isRecording) }
    var recordingPaused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(engine.muted) }
    var showFreqInput by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    // 收藏列表快照：弹窗里增删后必须回写这里，否则底部 ★ 角标和列表都不刷新
    var favorites by remember { mutableStateOf(store.getFavorites()) }
    // 拖动期间的"本地权威中心"：老代码把增量加到 250ms 轮询来的 center 上，
    // 轮询落后于手指时基准就错了，越拖越偏。现在拖动自己累积基准，抬指才交还引擎。
    var dragBase by remember { mutableStateOf<Long?>(null) }
    // 自动 dB 范围：底噪 25% 分位数往下留 15dB 余量，上限取峰值 + 8dB（限幅 -20..0）
    // 固定范围在真机上不是「太高看不全」就是「太低全黑」——这正是 v2.22 修不干净的根因
    var dbRange by remember { mutableStateOf(-100f to 0f) }

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
            // 恢复瀑布速度档位（设置页改过要留下来）
            engine.waterfallSpeed = store.waterfallSpeed.coerceIn(0, 2)
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
                // 换台了：清空信号历史。旧台的强度留在曲线上会让人误以为
                // 「信号突然掉了」，其实是换了个频点而已。
                signalHistory.clear()
            }
            val newMode = engine.mode
            if (newMode != mode) {
                mode = newMode
                store.lastMode = FreqStore.modeIndex(newMode)
                // 换模式：不同调制的 dB 尺度不同，历史曲线没有可比性
                signalHistory.clear()
            }
            recording = engine.isRecording
            recordingPaused = engine.recordingPaused
            // 静噪/音量可能被外部改（真机连接时静噪自动全开），
            // 轮询期间滑条显示值必须与引擎实际值保持一致，否则「显示 -40 但实际 -100」
            if (engine.squelchDb != squelch) squelch = engine.squelchDb
            if (engine.volume != volume) volume = engine.volume
            if (engine.muted != muted) muted = engine.muted
            if (engine.squelchIsOpen != squelchOpen) squelchOpen = engine.squelchIsOpen
            // 采集信号历史：满了丢最旧的，容量固定不增长
            signalHistory.add(engine.tunedFreq to engine.signalDb)
            if (signalHistory.size > 120) signalHistory.removeAt(0)
            // 自动 dB 范围：底噪下留 15dB，峰值上留 8dB；总高度至少 40dB，最多到 0dB
            if (engine.autoDbRange) {
                val floor = engine.noiseFloorDb
                val peak = engine.spectrumPeakDb
                var lo = (floor - 15f).coerceAtLeast(-120f)
                var hi = (peak + 8f).coerceAtMost(0f)
                if (hi - lo < 40f) hi = (lo + 40f).coerceAtMost(0f)
                if (hi - lo < 40f) lo = hi - 40f
                val curLo = dbRange.first
                val curHi = dbRange.second
                // 缓动：范围跟着慢慢走，不跳变（跳变会让频谱整体上下抖）
                dbRange = (curLo + (lo - curLo) * 0.15f) to (curHi + (hi - curHi) * 0.15f)
            } else {
                // 关闭自动范围：回到固定刻度（与瀑布默认一致）
                if (dbRange.first != -100f || dbRange.second != 0f) dbRange = -100f to 0f
            }
            // 瀑布的 dB→颜色映射必须与谱线同一范围，否则两者色阶对不上
            waterfall.dynamicRange = dbRange
            // USB 健康：复位说明真的断流过；超时说明供电/线材不稳
            if (engine.isRealSource) {
                val resets = engine.usbResets
                val timeouts = engine.usbReadTimeouts
                usbWarn = when {
                    resets > 0 -> "USB 断流 $resets 次（换 OTG 线或换接口）"
                    timeouts >= 3 -> "USB 读超时 $timeouts 次（供电不稳）"
                    else -> null
                }
            } else {
                usbWarn = null
            }
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
                peak = peak,
                centerFreq = center,
                tunedFreq = tuned,
                span = viewSpan,
                fullSpan = engine.sampleRate.toLong(),
                dbMin = dbRange.first, dbMax = dbRange.second,
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
            // RDS 电台名（锁定后左上角）+ 立体声/单声道指示
            if (mode == SdrEngine.Mode.WFM) {
                val label = when {
                    rdsName.isNotEmpty() -> "📻 $rdsName"
                    rdsLocked -> "📻 RDS 同步·读台名中"
                    else -> "📻 RDS 搜索中…"
                }
                Surface(
                    color = Color(0xCC101826), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Text(
                        label + if (stereoLock > 0.5f) " ·ST" else " ·单声道",
                        color = when {
                            rdsName.isNotEmpty() -> Color(0xFF7DF3FF)
                            rdsLocked -> Color(0xFF00E5FF)
                            else -> Color(0xFF5A6B7F)
                        },
                        fontSize = 12.sp,
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

        // ===== 信号历史条（最近 30 秒）=====
        SignalHistoryStrip(
            history = signalHistory,
            modifier = Modifier.background(Color(0xFF0D1219))
        )

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
                                // 状态行优先级：未连接（最根本问题）> USB 断流告警 > 信号质量结论。
                                // 三者共占一行，避免小字堆叠把横屏频谱压扁
                                val line = usbPending ?: usbWarn
                                    ?: signalQualityText(signalDb, squelchOpen, squelch)
                                Text(
                                    line,
                                    fontSize = 10.sp,
                                    color = when {
                                        usbPending != null -> Color(0xFFF6D55C)
                                        usbWarn != null -> Color(0xFFFF6B6B)
                                        squelchOpen && signalDb >= -45f -> Color(0xFF7DF3FF)
                                        squelchOpen -> Color(0xFF00E5FF)
                                        else -> Color(0xFF5A6B7F)
                                    },
                                    maxLines = 1,
                                    // 未连接提示可点：直接跳设置页 USB 诊断
                                    modifier = if (usbPending != null)
                                        Modifier.clickable { onOpenUsbDiag() } else Modifier
                                )
                            }
                        }
                    }
                    // ★ 一键收藏/取消：当前频率是否已收藏一眼可见，一点即切，不用进弹窗
                    val isFav = favorites.any { it.freq == tuned }
                    Surface(
                        color = if (isFav) Color(0xFF00E5FF) else Color(0xFF1A2230),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .widthIn(min = 44.dp)
                            .clickable {
                                if (isFav) {
                                    store.removeFavorite(tuned)
                                } else {
                                    // 收藏名优先用 RDS 电台名：看到 "BBC RADIO 1" 存下来
                                    // 比存 "98.100000 MHz" 有用得多；没有 RDS 时退回频率
                                    val label = rdsName.trim().ifEmpty { formatFreq(tuned) }
                                    store.addFavorite(FreqStore.Ch(
                                        label, tuned,
                                        FreqStore.modeIndex(mode)))
                                }
                                favorites = store.getFavorites()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (isFav) "★" else "☆",
                                fontSize = 18.sp,
                                color = if (isFav) Color(0xFF06121A) else Color(0xFF8FA3B8),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
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
                    Text("音量", fontSize = 11.sp, color = Color(0xFF5A6B7F))
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
                        fontSize = 11.sp,
                        color = if (muted) Color(0xFF5A6B7F) else Color(0xFF8FA3B8),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp).width(34.dp)
                    )
                    // 静音开关：SDR 时想临时静音又不想关录音/断流，直接点
                    OutlinedButton(
                        onClick = { engine.muted = !engine.muted; muted = engine.muted },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (muted) Color(0xFFFF6B6B) else Color(0xFF8FA3B8)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(if (muted) "🔇 静音中" else "🔊 静音", fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (muted) Color(0xFFFF6B6B) else Color(0xFF8FA3B8))
                    }
                    Spacer(Modifier.width(14.dp))
                    // 录音开关：录音中红底高亮，一眼可见；未录音灰底。
                    OutlinedButton(
                        onClick = {
                            when {
                                recording && !recordingPaused -> {   // 录音中 → 暂停
                                    engine.pauseRecording()
                                    recordingPaused = true
                                }
                                recording && recordingPaused -> {    // 暂停中 → 继续
                                    engine.resumeRecording()
                                    recordingPaused = false
                                }
                                else -> {                             // 未录 → 开始
                                    val ok = engine.startRecording(context)
                                    if (ok) { recording = true; recordingPaused = false }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (recording && !recordingPaused) Color(0xFFFF6B6B)
                                           else if (recordingPaused) Color(0xFFF6D55C)
                                           else Color(0xFF8FA3B8),
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (recording && !recordingPaused) Icons.Filled.Pause
                                             else if (recordingPaused) Icons.Filled.PlayArrow
                                             else Icons.Filled.Stop,
                                contentDescription = when {
                                    recording && !recordingPaused -> "暂停录音"
                                    recordingPaused -> "继续录音"
                                    else -> "开始录音"
                                },
                                tint = if (recording && !recordingPaused) Color(0xFFFF6B6B)
                                       else if (recordingPaused) Color(0xFFF6D55C)
                                       else Color(0xFF8FA3B8),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                when {
                                    recording && !recordingPaused -> "录音中"
                                    recordingPaused -> "已暂停"
                                    else -> "录音"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (recording && !recordingPaused) Color(0xFFFF6B6B)
                                       else if (recordingPaused) Color(0xFFF6D55C)
                                       else Color(0xFF8FA3B8),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    // 录音中额外给一个「停止」：暂停/继续与停止是两个不同意图，
                    // 塞进同一个按钮会让用户不敢点（怕点错丢录音）
                    if (recording) {
                        OutlinedButton(
                            onClick = {
                                engine.stopRecording()
                                recording = false
                                recordingPaused = false
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5A6B7F)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text("■ 停", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF5A6B7F))
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
                FreqInputDialog(
                    engine, store,
                    onDismiss = { showFreqInput = false },
                    onAddFavorite = { showFavorites = true }
                )
            }
            if (showFavorites) {
                FavoritesDialog(
                    store,
                    engine,
                    favorites,
                    // 增删/改名后立刻回读并回写快照，★ 角标与列表同步刷新
                    onChange = { favorites = store.getFavorites() },
                    onRename = { ch, n ->
                        store.renameFavorite(ch.freq, n)
                        favorites = store.getFavorites()
                    },
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

/** 步进按钮对（默认随模式自适应；轻点步进标签可循环切换步长，按住连跳，松手即停） */
@Composable
private fun StepButtons(engine: SdrEngine, mode: SdrEngine.Mode, onTuned: () -> Unit) {
    // 可选步长表：覆盖广播/业余常用信道间隔，点一下换一档
    val steps = listOf(1_000L, 5_000L, 9_000L, 10_000L, 12_500L, 25_000L, 50_000L, 100_000L, 1_000_000L)
    fun defaultIndex(m: SdrEngine.Mode): Int = when (m) {
        SdrEngine.Mode.WFM -> steps.indexOf(100_000L)
        SdrEngine.Mode.NFM -> steps.indexOf(12_500L)
        SdrEngine.Mode.AM -> steps.indexOf(25_000L)
        else -> steps.indexOf(1_000L)
    }
    // 模式切换时回到该模式默认档；用户手动选过的档位在换模式后重置
    var stepIndex by remember(mode) { mutableStateOf(defaultIndex(mode)) }
    val step = steps[stepIndex]
    val stepLabel = when {
        step >= 1_000_000 -> "%.1fM".format(step / 1e6)
        step >= 1_000 -> if (step % 1_000 == 0L) "${step / 1_000}k" else "%.1fk".format(step / 1e3)
        else -> "${step}Hz"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepArrow(up = false, step = step, engine = engine, onTuned = onTuned)
        // 步长标签本身就是切换按钮：点一下进一档（循环），不必去设置页翻
        Surface(
            color = Color(0xFF1A2230),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .clickable { stepIndex = (stepIndex + 1) % steps.size }
        ) {
            Text(
                stepLabel, fontSize = 10.sp, color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
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

/**
 * 信号历史条（SDR# 的 Signal History）：最近 ~30 秒的 dB 曲线。
 * 架天线/找信号时，「刚才动没动过」比「现在多强」更有信息量——
 * 尤其人转身、天线挪位置时，历史曲线的突跳一目了然。
 * 高度 36dp：够看清趋势，又不挤占频谱主体。
 *
 * 每个点连同当时的调谐频率一起记录：换了台之后，旧台的强度和新台画在同一条线上
 * 会误导用户（看着像信号突然掉了，其实只是换了个频点）。所以频率一变，曲线就断开。
 */
@Composable
private fun SignalHistoryStrip(history: List<Pair<Long, Float>>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(36.dp)) {
        if (history.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        // dB 范围：固定 -110..-20，跨台可比；不随当前值缩放（否则弱信号时整条线乱跳）
        val dbMin = -110f
        val dbMax = -20f
        fun y(db: Float): Float =
            h - ((db - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f) * h
        // 底噪参考线（-80dB）
        drawLine(
            color = Color(0x335A6B7F),
            start = Offset(0f, y(-80f)),
            end = Offset(w, y(-80f)),
            strokeWidth = 1f
        )
        val stepX = w / (history.size - 1)
        var last: Offset? = null
        var lastFreq: Long? = null
        for (i in history.indices) {
            val (freq, db) = history[i]
            val p = Offset(i * stepX, y(db))
            // 只有同一频率的点才连线；换台处断开
            if (last != null && lastFreq == freq) {
                drawLine(Color(0xFF00E5FF), last!!, p, strokeWidth = 1.6f)
            }
            last = p
            lastFreq = freq
        }
        // 当前值端点
        last?.let {
            drawCircle(Color(0xFFF6D55C), radius = 2.5f, center = it)
        }
    }
}
