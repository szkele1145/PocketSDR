package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    waterfall: ArrayDeque<FloatArray>,
    status: String,
    signalDb: Float,
    stereoLock: Float,
    onExit: () -> Unit
) {
    val modes = remember { SdrEngine.Mode.entries.toList() }
    var mode by remember { mutableStateOf(engine.mode) }
    var volume by remember { mutableStateOf(engine.volume) }
    var squelch by remember { mutableStateOf(engine.squelchDb) }
    var tuned by remember { mutableStateOf(engine.tunedFreq) }
    var center by remember { mutableStateOf(engine.centerFreq) }
    var showRtl by remember { mutableStateOf(false) }

    // 引擎状态变化 → UI 同步
    LaunchedEffect(Unit) {
        while (true) {
            tuned = engine.tunedFreq
            center = engine.centerFreq
            kotlinx.coroutines.delay(200)
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
            SpectrumWaterfall(
                spectrum = spectrum,
                waterfall = waterfall,
                centerFreq = center,
                tunedFreq = tuned,
                span = engine.sampleRate.toLong(),
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
                modifier = Modifier.fillMaxSize()
            )
            // 信号强度徽标
            Surface(
                color = Color(0xCC101826), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text(
                    text = "${signalDb.toInt()} dBFS",
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
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { engine.tuneWithinSpan(tuned + 100_000); tuned += 100_000 }) {
                        Icon(Icons.Filled.KeyboardArrowUp, "上100k", tint = Color(0xFF00E5FF))
                    }
                    Text("±100k", fontSize = 9.sp, color = Color(0xFF5A6B7F))
                    IconButton(onClick = { engine.tuneWithinSpan(tuned - 100_000); tuned -= 100_000 })
                    { Icon(Icons.Filled.KeyboardArrowDown, "下100k", tint = Color(0xFF00E5FF)) }
                }
            }

            // ---- 模式行 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
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

            // ---- 波段快捷 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val bands = listOf(
                    "FM 广播" to 98_000_000L,
                    "航空" to 121_500_000L,
                    "2m" to 145_500_000L
                )
                bands.forEach { (name, f) ->
                    OutlinedButton(
                        onClick = { engine.tuneTo(f); tuned = engine.tunedFreq; center = engine.centerFreq },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                    ) { Text(name, fontSize = 12.sp) }
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

            // ---- 状态行 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Text(status, fontSize = 11.sp, color = Color(0xFF8FA3B8), modifier = Modifier.weight(1f))
                if (mode == SdrEngine.Mode.WFM && stereoLock > 0.5f) {
                    Text("ST", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onExit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "退出", tint = Color(0xFF5A6B7F))
                }
            }
        }
    }
}

private fun formatFreq(f: Long): String {
    val m = f / 1e6
    return if (f % 1_000_000 == 0L) "%.0f MHz".format(m) else "%.4f MHz".format(m)
}
