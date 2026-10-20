package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.FreqStore
import com.pocketsdr.app.SdrEngine

/**
 * 设置页：所有次要功能分组摊开——
 * 调制模式 / 波段快捷 / 功能（录音·搜台·收藏·日志）/ 静噪 / 硬件增益（RTL 时）。
 * 每组一张圆角卡片，组内按钮直接可点。
 */
@Composable
fun SettingsPage(
    engine: SdrEngine,
    status: String,
    /** 主界面硬件提示横幅被点中：进来就滚到 USB 诊断，别让用户自己找 */
    focusUsbDiag: Boolean = false,
    /** 最近一次硬件连接失败的完整原因（横幅上截断了，这里给完整版） */
    usbErrorFull: String? = null,
    onExit: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { FreqStore(context) }
    var mode by remember { mutableStateOf(engine.mode) }
    var squelch by remember { mutableStateOf(engine.squelchDb) }
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var showFreqInput by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    // 自检报告是否已复制到剪贴板
    var copied by remember { mutableStateOf(false) }
    // 录音以 engine 为事实来源，与 RxPage 录音开关联动，不再维护孤立本地状态
    val recording = engine.isRecording
    var scanProgress by remember { mutableStateOf(-1) }
    var scanFound by remember { mutableStateOf(listOf<com.pocketsdr.app.FmScanner.Station>()) }
    var favorites by remember { mutableStateOf(store.getFavorites()) }
    var renameTarget by remember { mutableStateOf<FreqStore.Ch?>(null) }
    var renameText by remember { mutableStateOf("") }
    // USB 诊断卡在左列末尾（列很长，要滚很久才看得到）。
    // 主界面硬件提示横幅点进来时直接滚过去，别让用户自己找。
    val leftScroll = rememberScrollState()
    var usbDiagTop by remember { mutableStateOf(0f) }
    LaunchedEffect(focusUsbDiag) {
        if (focusUsbDiag) {
            // 等一帧让布局先算出来，拿到诊断卡的实际位置
            kotlinx.coroutines.delay(120)
            leftScroll.scrollTo(usbDiagTop.coerceAtLeast(0f).toInt())
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("设置", fontSize = 18.sp, color = Color(0xFFD0DAE4), modifier = Modifier.weight(1f))
            Text(status, fontSize = 10.sp, color = Color(0xFF5A6B7F), maxLines = 1)
            IconButton(onClick = onExit, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Close, "退出", tint = Color(0xFF5A6B7F))
            }
        }

        // 横屏只有 400dp 出头的高度，竖排一长条根本翻不完；
        // 改成左右两列，常用项（模式/波段/静噪）在左，工具与硬件在右。
        Row(Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.weight(1f).verticalScroll(leftScroll)) {

        // ===== 调制模式 =====
        GroupCard("调制模式") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SdrEngine.Mode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m; engine.selectMode(m) },
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
        }

        // ===== 波段 =====
        GroupCard("波段（点击自动切模式）") {
            val bands = listOf(
                Triple("FM 广播", 98_000_000L, SdrEngine.Mode.WFM),
                Triple("航空", 121_500_000L, SdrEngine.Mode.AM),
                Triple("2m 业余", 145_500_000L, SdrEngine.Mode.NFM),
                Triple("NOAA 气象", 137_100_000L, SdrEngine.Mode.NFM),
                Triple("40m 短波", 7_074_000L, SdrEngine.Mode.LSB),
                Triple("20m 短波", 14_300_000L, SdrEngine.Mode.USB)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bands.take(3).forEach { (name, f, m) -> BandBtn(name, f, m, engine) { mode = engine.mode; tuned = engine.tunedFreq } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bands.drop(3).forEach { (name, f, m) -> BandBtn(name, f, m, engine) { mode = engine.mode; tuned = engine.tunedFreq } }
            }
        }

        // ===== 静噪 =====
        GroupCard("静噪（往下拖 = 只收强台）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = squelch,
                    onValueChange = {
                        squelch = it
                        engine.squelchDb = it
                        store.lastSquelch = it
                        store.squelchTuned = true
                    },
                    valueRange = -80f..-10f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF5252),
                        activeTrackColor = Color(0xFFFF7961),
                        inactiveTrackColor = Color(0xFF1A2230)
                    )
                )
                Text("${squelch.toInt()} dB", fontSize = 12.sp,
                    color = Color(0xFF8FA3B8), fontFamily = FontFamily.Monospace)
            }
        }
        }

        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // ===== 功能 =====
        GroupCard("功能") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showFreqInput = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                    modifier = Modifier.weight(1f)) { Text("⌨ 输频", fontSize = 12.sp) }
                OutlinedButton(onClick = { showScan = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7DF3FF)),
                    modifier = Modifier.weight(1f)) { Text("🔍 搜台", fontSize = 12.sp) }
                OutlinedButton(onClick = { showFavorites = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF6D55C)),
                    modifier = Modifier.weight(1f)) { Text("★ 收藏", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    if (engine.isRecording) engine.stopRecording()
                    else engine.startRecording(context)
                },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (engine.isRecording) Color(0xFFFF4040) else Color(0xFF8FA3B8)),
                    modifier = Modifier.weight(1f)) { Text(if (engine.isRecording) "⏹ 停录" else "⏺ 录音", fontSize = 12.sp) }
                OutlinedButton(onClick = { showRecordings = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8FA3B8)),
                    modifier = Modifier.weight(1f)) { Text("☰ 录音列表", fontSize = 12.sp) }
                OutlinedButton(onClick = { showLog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5A6B7F)),
                    modifier = Modifier.weight(1f)) { Text("📄 日志", fontSize = 12.sp) }
            }
        }
        if (engine.isRealSource) {
            GroupCard("RTL 硬件") {
                // 初始值从 store 读，改动立刻写回：以前这几个都是 remember 本地态，
                // 退出应用再进来全部回到默认值，用户每次都要重调一遍。
                var manualGain by remember { mutableStateOf(store.hwManualGain) }
                var gainIdx by remember { mutableStateOf(store.hwGainIndex.toFloat()) }
                var hfDirect by remember { mutableStateOf(store.hwDirectSampling != 0) }
                var offTune by remember { mutableStateOf(store.hwOffsetTuning) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("LNA 增益", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = manualGain, onCheckedChange = {
                        manualGain = it
                        store.hwManualGain = it
                        engine.setHardwareGainMode(it)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF)))
                    if (manualGain) {
                        Slider(
                            value = gainIdx,
                            onValueChange = {
                                gainIdx = it
                                val idx = it.toInt().coerceIn(0, engine.lnaGains.size - 1)
                                store.hwGainIndex = idx
                                engine.setHardwareLnaGain(engine.lnaGains[idx])
                            },
                            valueRange = 0f..(engine.lnaGains.size - 1).toFloat(),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFF6D55C))
                        )
                        Text("%.1fdB".format(engine.lnaGains[gainIdx.toInt().coerceIn(0, engine.lnaGains.size - 1)] / 10f),
                            fontSize = 11.sp, color = Color(0xFFF6D55C), fontFamily = FontFamily.Monospace)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("HF 直采", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = hfDirect, onCheckedChange = {
                        hfDirect = engine.setHardwareDirectSampling(if (it) 1 else 0)
                        store.hwDirectSampling = if (hfDirect) 1 else 0
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF6D55C)))
                    Spacer(Modifier.width(12.dp))
                    Text("偏移调谐", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = offTune, onCheckedChange = {
                        offTune = engine.setHardwareOffsetTuning(it)
                        store.hwOffsetTuning = offTune
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF7DF3FF)))
                    Spacer(Modifier.weight(1f))
                    Text("%.3fM".format(engine.sampleRate / 1e6), fontSize = 11.sp,
                        color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace)
                }
                // PPM 频率校准：晶振偏几十 ppm 就会让窄带信号完全跑掉
                var ppm by remember { mutableStateOf(engine.hardwarePpm.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("频率校准", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Slider(
                        value = ppm,
                        onValueChange = {
                            ppm = it
                            val v = Math.round(it)
                            store.hwPpm = v
                            engine.setHardwarePpm(v)
                        },
                        valueRange = -100f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFF6D55C))
                    )
                    Text("%+.0f ppm".format(ppm), fontSize = 11.sp,
                        color = Color(0xFFF6D55C), fontFamily = FontFamily.Monospace)
                }
                Text("听到的信号比实际频率偏低（听不清）就往正调，偏高往负调。一次标定全频段通用。",
                    fontSize = 10.sp, color = Color(0xFF5A6B7F))
            }
        }

        // ===== 显示 =====
        GroupCard("显示") {
            var peakHold by remember { mutableStateOf(engine.peakHoldEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("频谱峰值保持", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                Switch(checked = peakHold, onCheckedChange = {
                    peakHold = it
                    engine.peakHoldEnabled = it
                    store.peakHold = it
                }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF6D55C)))
                Spacer(Modifier.width(10.dp))
                Text("黄线记录出现过的最高电平，弱信号一闪而过也留痕",
                    fontSize = 10.sp, color = Color(0xFF5A6B7F))
            }
            var autoDb by remember { mutableStateOf(engine.autoDbRange) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("自动 dB 范围", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                Switch(checked = autoDb, onCheckedChange = {
                    autoDb = it
                    engine.autoDbRange = it
                    store.autoDbRange = it
                }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF)))
                Spacer(Modifier.width(10.dp))
                Text("按实测噪声底自动缩放纵轴，不同增益下都能看清信号",
                    fontSize = 10.sp, color = Color(0xFF5A6B7F))
            }
            // 瀑布速度三档：慢档历史长（60 秒）适合盯弱信号，快档不漏瞬时信号
            var wfSpeed by remember { mutableStateOf(engine.waterfallSpeed) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("瀑布速度", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                Spacer(Modifier.width(10.dp))
                listOf("慢", "中", "快").forEachIndexed { i, label ->
                    val on = wfSpeed == i
                    OutlinedButton(
                        onClick = { wfSpeed = i; engine.waterfallSpeed = i; store.waterfallSpeed = i },
                        modifier = Modifier.padding(end = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (on) Color(0xFF00E5FF) else Color(0xFF1A2230),
                            contentColor = if (on) Color(0xFF06121A) else Color(0xFF8FA3B8)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Text(label, fontSize = 11.sp) }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    when (wfSpeed) {
                        0 -> "3 行/秒 · 约 60 秒历史"
                        1 -> "8 行/秒 · 约 24 秒历史"
                        else -> "16 行/秒 · 约 12 秒历史"
                    },
                    fontSize = 10.sp, color = Color(0xFF5A6B7F)
                )
            }
        }

        // ===== USB 诊断（无硬件也常驻，非官方棒子排查用）=====
        GroupCard("USB 诊断", modifier = Modifier.onGloballyPositioned {
            // 记下这张卡在滚动容器里的位置，主界面横幅点进来时直接滚到这儿
            usbDiagTop = it.positionInParent().y
        }) {
            UsbDiagCard(engine, usbErrorFull)
        }

        // ===== 关于 =====
        GroupCard("关于") {
            Text("PocketSDR · RTL-SDR 横屏收音机", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            Text("仿真模式可无硬件体验全部功能；RTL-SDR 插入 OTG 自动切换真硬件",
                fontSize = 11.sp, color = Color(0xFF5A6B7F))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showOnboarding = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                    modifier = Modifier.weight(1f)) {
                    Text("❓ 使用说明", fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                // 一键自检报告：出问题时用户不用翻 logcat，复制一段文本即可定位
                OutlinedButton(onClick = {
                    val ver = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                    val txt = engine?.selfCheckReport(ver) ?: "引擎未就绪"
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("PocketSDR 自检", txt))
                    copied = true
                },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (copied) Color(0xFF7DF3FF) else Color(0xFF8FA3B8)),
                    modifier = Modifier.weight(1f)) {
                    Text(if (copied) "✓ 已复制" else "📋 自检报告", fontSize = 12.sp)
                }
            }
            if (copied) {
                Text("已复制到剪贴板，粘贴给我即可排查。", fontSize = 10.sp, color = Color(0xFF5A6B7F))
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        }
    }

    // ---- 对话框们（复用原有实现） ----
    if (showOnboarding) OnboardingDialog { showOnboarding = false }
    if (showFreqInput) FreqInputDialog(engine, store, onDismiss = {
        tuned = engine.tunedFreq; showFreqInput = false
    })
    if (showFavorites) FavoritesDialog(store, engine, favorites,
        onChange = { favorites = store.getFavorites() },
        onRename = { ch, name -> store.renameFavorite(ch.freq, name); favorites = store.getFavorites() },
        onDismiss = { showFavorites = false })
    if (showScan) ScanDialog(engine, scanFound, scanProgress,
        onFound = { scanFound = it }, onProgress = { scanProgress = it },
        onDismiss = { showScan = false })
    if (showLog) LogDialog { showLog = false }
    if (showRecordings) RecordingsDialog { showRecordings = false }
}

/**
 * USB 诊断卡：列出系统枚举到的所有 USB 设备（VID/PID、授权、bulk-in）。
 * 非官方棒子的 PID 落在白名单外时，这里是唯一的排查与自救入口。
 */
@Composable
fun UsbDiagCard(engine: SdrEngine? = null, usbErrorFull: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(listOf<com.pocketsdr.app.UsbDiag.Entry>()) }
    val refresh: () -> Unit = {
        val usb = context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        if (usb != null) entries = com.pocketsdr.app.UsbDiag.snapshot(usb)
    }
    // 打开设置页时刷新一次（进入页面必然重组，插拔后重进即可）
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxWidth()) {
        // 上次连接失败的完整原因：主界面横幅上为了排版截断了，
        // 而被砍掉的往往正是「该换线还是该换芯片」这类关键判断依据。
        if (!usbErrorFull.isNullOrBlank()) {
            Surface(
                color = Color(0xFF2A1114),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        "上次连接失败：$usbErrorFull",
                        fontSize = 11.sp,
                        color = Color(0xFFFF9E9E)
                    )
                }
            }
        }
        // 实时数据流状态：真机连上了但没数据，是最常见也最难猜的故障
        if (engine != null && engine.isRealSource) {
            var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) { kotlinx.coroutines.delay(500); tick++ }
            }
            @Suppress("UNUSED_EXPRESSION") tick
            val idle = engine.dataIdleMs()
            val alive = idle in 0..2000
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        idle < 0 -> "数据流：尚未收到任何数据"
                        alive -> "数据流：正常（已收 ${engine.blockCount} 块）"
                        else -> "数据流：已中断 ${idle / 1000} 秒（设备无响应）"
                    },
                    fontSize = 11.sp,
                    color = if (alive) Color(0xFF7DF3FF) else Color(0xFFFF6B6B),
                    modifier = Modifier.weight(1f)
                )
                Text("%.3fM".format(engine.sampleRate / 1e6),
                    fontSize = 10.sp, color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            // USB 层健康：超时/复位是供电不稳与 OTG 线接触不良的硬指标
            val timeouts = engine.usbReadTimeouts
            val resets = engine.usbResets
            if (timeouts > 0 || resets > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            resets > 0 -> "USB：读超时 $timeouts 次 · 已复位 $resets 次"
                            else -> "USB：读超时 $timeouts 次"
                        },
                        fontSize = 11.sp,
                        color = if (resets > 0) Color(0xFFFF6B6B) else Color(0xFFF6D55C),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("供电不足或 OTG 线接触不良。换线/换口，或给手机供电后重试。",
                    fontSize = 10.sp, color = Color(0xFF5A6B7F))
            }
        }
        if (entries.isEmpty()) {
            Text("未检测到 USB 设备（插上 OTG 后回到此页）", fontSize = 11.sp, color = Color(0xFF5A6B7F))
        } else {
            entries.forEach { e ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        "${e.id} ${if (e.bulkIn) "·SDR?" else "·非SDR"}",
                        fontSize = 11.sp,
                        color = if (e.known) Color(0xFF00E5FF) else Color(0xFF8FA3B8),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    if (!e.granted) {
                        Text("未授权", fontSize = 10.sp, color = Color(0xFF5A6B7F))
                        Spacer(Modifier.width(8.dp))
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            val act = context as? com.pocketsdr.app.MainActivity
                            val usb = context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                            val dev = if (usb != null) com.pocketsdr.app.UsbDiag.find(usb, e.vid, e.pid) else null
                            if (act != null && dev != null) act.forceConnect(dev)
                            refresh()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = if (e.known) Color(0xFF00E5FF) else Color(0xFFF6D55C)
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(if (e.known) "连接" else "强制尝试", fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("提示：识别不了的棒子点「强制尝试」，仍失败请把上面的 VID:PID 抄下来反馈",
                fontSize = 10.sp, color = Color(0xFF5A6B7F))
        }
    }
}

/** 分组卡片容器 */@Composable
private fun GroupCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Color(0xFF111826),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 12.sp, color = Color(0xFF5A6B7F),
                modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun BandBtn(name: String, f: Long, m: SdrEngine.Mode, engine: SdrEngine,
                    onChange: () -> Unit) {
    OutlinedButton(onClick = {
        engine.selectMode(m); engine.tuneTo(f); onChange()
    }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))) {
        Text(name, fontSize = 12.sp)
    }
}
