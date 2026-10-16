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
    // 录音以 engine 为事实来源，与 RxPage 录音开关联动，不再维护孤立本地状态
    val recording = engine.isRecording
    var scanProgress by remember { mutableStateOf(-1) }
    var scanFound by remember { mutableStateOf(listOf<com.pocketsdr.app.FmScanner.Station>()) }
    var favorites by remember { mutableStateOf(store.getFavorites()) }
    var renameTarget by remember { mutableStateOf<FreqStore.Ch?>(null) }
    var renameText by remember { mutableStateOf("") }

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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

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
                    value = squelch, onValueChange = { squelch = it; engine.squelchDb = it },
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
                var manualGain by remember { mutableStateOf(false) }
                var gainIdx by remember { mutableStateOf(14f) }
                var hfDirect by remember { mutableStateOf(false) }
                var offTune by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("LNA 增益", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = manualGain, onCheckedChange = {
                        manualGain = it; engine.setHardwareGainMode(it)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF)))
                    if (manualGain) {
                        Slider(
                            value = gainIdx,
                            onValueChange = {
                                gainIdx = it
                                val g = engine.lnaGains[it.toInt().coerceIn(0, engine.lnaGains.size - 1)]
                                engine.setHardwareLnaGain(g)
                            },
                            valueRange = 0f..(engine.lnaGains.size - 1).toFloat(),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFF6D55C))
                        )
                        Text("%.1fdB".format(engine.lnaGains[gainIdx.toInt()] / 10f),
                            fontSize = 11.sp, color = Color(0xFFF6D55C), fontFamily = FontFamily.Monospace)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("HF 直采", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = hfDirect, onCheckedChange = {
                        hfDirect = engine.setHardwareDirectSampling(if (it) 1 else 0)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF6D55C)))
                    Spacer(Modifier.width(12.dp))
                    Text("偏移调谐", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                    Switch(checked = offTune, onCheckedChange = {
                        offTune = engine.setHardwareOffsetTuning(it)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF7DF3FF)))
                    Spacer(Modifier.weight(1f))
                    Text("%.3fM".format(engine.sampleRate / 1e6), fontSize = 11.sp,
                        color = Color(0xFF5A6B7F), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ===== 关于 =====
        GroupCard("关于") {
            Text("PocketSDR · RTL-SDR 横屏收音机", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            Text("仿真模式可无硬件体验全部功能；RTL-SDR 插入 OTG 自动切换真硬件",
                fontSize = 11.sp, color = Color(0xFF5A6B7F))
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showOnboarding = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))) {
                Text("❓ 使用说明", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        }
    }

    // ---- 对话框们（复用原有实现） ----
    if (showOnboarding) OnboardingDialog { showOnboarding = false }
    if (showFreqInput) FreqInputDialog(engine) {
        tuned = engine.tunedFreq; showFreqInput = false
    }
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

/** 分组卡片容器 */
@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color(0xFF111826),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
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
