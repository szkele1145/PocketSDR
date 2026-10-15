package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** 频率输入 */
@Composable
fun FreqInputDialog(engine: SdrEngine, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入频率 (MHz)") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("98.1") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.trim().replace('．', '.').replace(',', '.').replace('。', '.')
                n.toFloatOrNull()?.let { mhz ->
                    if (mhz in 0.1f..1800f) {
                        engine.tuneTo((mhz * 1_000_000).toLong())
                    }
                }
                onDismiss()
            }) { Text("调谐") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
                        IconButton(onClick = { store.removeFavorite(ch.freq); onChange() },
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
}
