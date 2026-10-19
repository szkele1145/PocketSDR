package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.FreqStore
import com.pocketsdr.app.SdrEngine

/** 常用频段预设：点一下就跳过去，模式一并切好 */
data class PresetBand(val name: String, val freqHz: Long, val mode: SdrEngine.Mode)

val PresetBands = listOf(
    PresetBand("FM 广播", 98_000_000L, SdrEngine.Mode.WFM),
    PresetBand("航空对讲", 121_500_000L, SdrEngine.Mode.AM),
    PresetBand("业余无线电 2m", 144_000_000L, SdrEngine.Mode.NFM),
    PresetBand("对讲机 UHF", 446_000_000L, SdrEngine.Mode.NFM),
    PresetBand("短波 7MHz", 7_074_000L, SdrEngine.Mode.USB),
    PresetBand("短波 3.5MHz", 3_573_000L, SdrEngine.Mode.LSB)
)

/** 新手引导 */
@Composable
fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📻 怎么用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("① 直接听：设置页点「FM 广播」→ 回收音页点频谱图上的尖峰", fontSize = 13.sp)
                Text("② 调频率：点大数字输入 MHz，或点 ▲▼ 步进（长按连续跳）", fontSize = 13.sp)
                Text("③ 频谱：单指左右拖 = 平移 · 双指捏合 = 放大缩小", fontSize = 13.sp)
                Text("④ 找台：设置页「🔍搜台」自动扫 FM 全波段，结果点一下就能听", fontSize = 13.sp)
                Text("⑤ 两个页面：收音页只管听，其他都在设置页", fontSize = 13.sp)
                Text("⑥ 现在是仿真模式（没插硬件也能玩）；RTL-SDR 棒子插上自动变真收音机",
                    fontSize = 13.sp, color = Color(0xFF00E5FF))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/** 频率输入（含最近频率快捷跳台 + 收藏当前） */
@Composable
fun FreqInputDialog(
    engine: SdrEngine,
    store: FreqStore,
    onDismiss: () -> Unit,
    onAddFavorite: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recents = remember { store.getRecents().toMutableList() }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入频率 (MHz)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("98.1") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                if (recents.isNotEmpty()) {
                    Text("最近", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        recents.forEach { f ->
                            TextButton(onClick = {
                                text = "%.3f".format(f / 1_000_000f).trimEnd('0').trimEnd('.')
                            }) { Text(formatFreq(f), fontSize = 12.sp) }
                        }
                    }
                }
                // 常用频段：一点即跳，模式自动切换（用户不用先懂 AM/NFM/WFM 的区别）
                Text("常用频段", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PresetBands.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    engine.tuneTo(p.freqHz)
                                    engine.selectMode(p.mode)
                                    store.addRecent(p.freqHz)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(p.name, fontSize = 12.sp, color = Color(0xFF00E5FF))
                            }
                            Text(formatFreq(p.freqHz), fontSize = 11.sp,
                                color = Color(0xFF8FA3B8),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.trim().replace('．', '.').replace(',', '.').replace('。', '.')
                n.toFloatOrNull()?.let { mhz ->
                    if (mhz in 0.1f..1800f) {
                        val hz = (mhz * 1_000_000).toLong()
                        engine.tuneTo(hz)
                        store.addRecent(hz)
                    }
                }
                onDismiss()
            }) { Text("调谐") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAddFavorite) { Text("★ 收藏当前") }
            }
        }
    )
}

/** 收藏列表（含改名/删除/跳台/收藏当前） */
@Composable
fun FavoritesDialog(
    store: FreqStore,
    engine: SdrEngine,
    favorites: List<FreqStore.Ch>,
    onChange: () -> Unit,
    onRename: (FreqStore.Ch, String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<FreqStore.Ch?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FreqStore.Ch?>(null) }
    var mode by remember { mutableStateOf(engine.mode) }
    var tuned by remember { mutableStateOf(engine.tunedFreq) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏频道 (${favorites.size})") },
        text = {
            Column {
                if (favorites.isEmpty()) {
                    Text("暂无收藏。收听到喜欢的台后点「★ 收藏当前」添加。", fontSize = 13.sp)
                }
                favorites.forEach { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            engine.tuneTo(ch.freq)
                            engine.selectMode(FreqStore.modeFromIndex(ch.mode))
                            mode = engine.mode
                            tuned = engine.tunedFreq
                            onDismiss()
                        }) { Text("${ch.name} · ${formatFreq(ch.freq)}", fontSize = 14.sp) }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        IconButton(onClick = { renameTarget = ch; renameText = ch.name },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Edit, "改名", tint = Color(0xFF5A6B7F),
                                modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { deleteTarget = ch },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F),
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.addFavorite(FreqStore.Ch(
                    formatFreq(engine.tunedFreq),
                    engine.tunedFreq,
                    FreqStore.modeIndex(engine.mode)))
                onChange()
            }) { Text("★ 收藏当前") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    // 改名子弹窗
    renameTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    singleLine = true, label = { Text(formatFreq(t.freq)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(t, renameText.trim())
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    // 删除二次确认：误触一下就把收藏删掉太容易了
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除收藏") },
            text = { Text("确定删除「${t.name} · ${formatFreq(t.freq)}」？", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    store.removeFavorite(t.freq)
                    onChange()
                    deleteTarget = null
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
