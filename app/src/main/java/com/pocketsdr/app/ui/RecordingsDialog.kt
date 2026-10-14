package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音文件浏览器对话框：列出 recordings 目录的 WAV 文件。
 * 每项显示文件名 / 修改时间 / 大小，可删除、可分享。
 */
@Composable
fun RecordingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(listFiles(context)) }
    var deletedTick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") deletedTick

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("录音文件 (${files.size})") },
        text = {
            if (files.isEmpty()) {
                Text("暂无录音。收音时点 ⏺ 开始录音。", fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFD0DAE4), maxLines = 1)
                                Text(
                                    "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))}  " +
                                        "%.1f MB".format(f.length() / 1e6),
                                    fontSize = 10.sp, color = Color(0xFF5A6B7F)
                                )
                            }
                            IconButton(onClick = {
                                // 分享
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".files", f)
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "audio/x-wav"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "分享录音"))
                                } catch (_: Exception) {}
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Share, "分享",
                                    tint = Color(0xFF7DF3FF), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                f.delete()
                                files = listFiles(context)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F),
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun listFiles(context: android.content.Context): List<File> {
    val dir = File(context.getExternalFilesDir(null), "recordings")
    return dir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
