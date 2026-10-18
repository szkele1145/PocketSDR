package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsdr.app.FmScanner
import com.pocketsdr.app.SdrEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 搜台对话框 */
@Composable
fun ScanDialog(
    engine: SdrEngine,
    scanFound: List<FmScanner.Station>,
    scanProgress: Int,
    onFound: (List<FmScanner.Station>) -> Unit,
    onProgress: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { com.pocketsdr.app.FreqStore(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scanProgress in 0..100) "搜索中… $scanProgress% (${scanFound.size} 台)"
            else "FM 自动搜台") },
        text = {
            Column {
                Text(
                    if (scanProgress in 0..100) "扫描 87.5–108 MHz，请稍候"
                    else "扫描整个 FM 广播波段，找到所有信号峰值。约需 20 秒。",
                    fontSize = 13.sp
                )
                if (scanProgress in 0..100) {
                    LinearProgressIndicator(
                        progress = { scanProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = Color(0xFF00E5FF)
                    )
                }
                if (scanFound.isEmpty()) {
                    Text("尚未找到电台。点右下角「开始扫描」，约 20 秒。", fontSize = 13.sp,
                        color = Color(0xFF5A6B7F))
                } else {
                    // 高度受限 + 可滚动：扫出 20 个台也不会把对话框顶出屏幕
                    LazyColumn(Modifier.height(300.dp).fillMaxWidth()) {
                        items(scanFound, key = { it.freq }) { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    engine.tuneTo(st.freq); onDismiss()
                                }) { Text(formatFreq(st.freq), fontSize = 14.sp) }
                                Text("${st.levelDb.toInt()}dB", fontSize = 11.sp,
                                    color = Color(0xFF5A6B7F), modifier = Modifier.weight(1f))
                                val saved = remember(st.freq) {
                                    store.getFavorites().any { it.freq == st.freq }
                                }
                                Text(if (saved) "★" else "", fontSize = 14.sp,
                                    color = Color(0xFF00E5FF))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (scanProgress !in 0..100) {
                    onProgress(0)
                    onFound(emptyList())
                    FmScanner(engine,
                        onProgress = { p, _ -> onProgress(p) },
                        onDone = { stations ->
                            onFound(stations.sortedByDescending { it.levelDb })
                            onProgress(101)
                        }).scan(87_500_000, 108_000_000)
                }
            }) { Text(if (scanProgress !in 0..100) "开始扫描" else "扫描中…") }
        },
        dismissButton = {
            Row {
                if (scanProgress > 100 && scanFound.isNotEmpty()) {
                    TextButton(onClick = {
                        scanFound.forEach { st ->
                            store.addFavorite(com.pocketsdr.app.FreqStore.Ch(
                                formatFreq(st.freq), st.freq, 0))
                        }
                    }) { Text("★ 全存收藏") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 日志面板 */
@Composable
fun LogDialog(onDismiss: () -> Unit) {
    val logLines = remember { com.pocketsdr.app.SdrLog.dump() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志 (${logLines.size})") },
        text = {
            androidx.compose.foundation.layout.Box(
                Modifier.height(320.dp).verticalScroll(rememberScrollState())
            ) {
                Column {
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
        },
        confirmButton = {
            val context = LocalContext.current
            TextButton(onClick = {
                com.pocketsdr.app.SdrLog.save(context)?.let {
                    android.widget.Toast.makeText(context, "已保存: $it", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text("落盘") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 录音文件浏览器 */
@Composable
fun RecordingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(listWavFiles(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("录音文件 (${files.size})") },
        text = {
            if (files.isEmpty()) {
                Text("暂无录音。设置页点「⏺ 录音」开始。", fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.height(340.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF00E5FF),
                                modifier = Modifier.padding(end = 6.dp).height(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFD0DAE4), maxLines = 1)
                                Text(
                                    "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))}  " +
                                        wavDuration(f) + "  %.1fMB".format(f.length() / 1e6),
                                    fontSize = 10.sp, color = Color(0xFF5A6B7F)
                                )
                            }
                            IconButton(onClick = {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".files", f)
                                    context.startActivity(android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "audio/x-wav"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "分享录音"))
                                } catch (_: Exception) {}
                            }, modifier = Modifier.padding(0.dp)) {
                                Icon(Icons.Filled.Share, "分享", tint = Color(0xFF7DF3FF))
                            }
                            IconButton(onClick = { f.delete(); files = listWavFiles(context) }) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun listWavFiles(context: android.content.Context): List<File> {
    val dir = File(context.getExternalFilesDir(null), "recordings")
    return dir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

private fun wavDuration(f: File): String {
    return try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(44)
            r.readFully(h)
            val byteRate = ((h[31].toInt() and 0xFF) shl 24) or ((h[30].toInt() and 0xFF) shl 16) or
                ((h[29].toInt() and 0xFF) shl 8) or (h[28].toInt() and 0xFF)
            val dataLen = ((h[43].toInt() and 0xFF) shl 24) or ((h[42].toInt() and 0xFF) shl 16) or
                ((h[41].toInt() and 0xFF) shl 8) or (h[40].toInt() and 0xFF)
            if (byteRate > 0 && dataLen > 0) {
                val sec = dataLen / byteRate
                if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
                else "%d:%02d".format(sec / 60, sec % 60)
            } else ""
        }
    } catch (_: Exception) { "" }
}
