package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.pocketsdr.app.FreqStore
import com.pocketsdr.app.SdrEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 可扫描的波段。
 *
 * 以前搜台只扫 FM 广播 87.5–108 MHz，可 SDR 远不止收广播——
 * 航空对讲、业余无线电、对讲机都是听得见的频段。
 *
 * 步进按各波段的实际电台间隔选，同时把总点数控制在 600 以内：
 * 每个点要等 90ms 让 DSP 稳定，600 点约 54 秒，是可接受的上限。
 */
data class ScanBand(
    val name: String,
    val fromHz: Long,
    val toHz: Long,
    val stepHz: Long,
    val mode: SdrEngine.Mode,
    /** 结果行的模式标签（测量模式不一定等于收听模式） */
    val listenMode: SdrEngine.Mode = mode
)

val ScanBands = listOf(
    ScanBand("FM 广播", 87_500_000L, 108_000_000L, 100_000L, SdrEngine.Mode.NFM, SdrEngine.Mode.WFM),
    ScanBand("航空对讲", 118_000_000L, 137_000_000L, 25_000L, SdrEngine.Mode.AM, SdrEngine.Mode.AM),
    ScanBand("业余 2m", 144_000_000L, 146_000_000L, 25_000L, SdrEngine.Mode.NFM, SdrEngine.Mode.NFM),
    ScanBand("对讲 UHF", 400_000_000L, 450_000_000L, 100_000L, SdrEngine.Mode.NFM, SdrEngine.Mode.NFM)
)

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
    // 扫描器实例保留，用于读回实测噪声底（判断灵敏度/是否搜得到台）
    var scanner by remember { mutableStateOf<com.pocketsdr.app.FmScanner?>(null) }
    var floorDb by remember { mutableStateOf<Float?>(null) }
    // 当前选中的波段：默认跟当前接收模式对上——
    // 用户在 AM 模式下点「搜台」，心里想的多半是航空对讲，不是 FM 广播。
    var bandIdx by remember {
        val m = engine.mode
        val idx = ScanBands.indexOfFirst { it.listenMode == m }
        mutableStateOf(if (idx >= 0) idx else 0)
    }
    val band = ScanBands[bandIdx]
    // 已收藏的频率集合：搜出来的台大多只想留一两个，
    // 以前 ★ 只是只读文字，点了没反应，用户只能「全存收藏」把 20 个台全收下。
    val favFreqs = remember { mutableStateListOf<Long>() }
    /** 正在试听的频率（null = 没在试听） */
    var audition by remember { mutableStateOf<Long?>(null) }
    // 本次扫描是不是被用户中途取消的：取消后要给不同的反馈，
    // 否则用户会以为扫完了却只得到几个台
    var scanCancelled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        favFreqs.clear()
        favFreqs.addAll(store.getFavorites().map { it.freq })
    }
    // 关掉对话框时必须把扫描停掉。
    // 以前关窗只是关 UI，扫描线程还在后台逐点调谐：引擎一直是 muted 状态，
    // 用户听不到原来的台，主界面频率还在自己乱跳——像应用失控了。
    DisposableEffect(Unit) {
        onDispose { scanner?.cancel() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scanProgress in 0..100) "搜索中… $scanProgress% (${scanFound.size} 台)"
            else "自动搜台") },
        text = {
            Column {
                // 波段选择：SDR 不止收广播，航空对讲/业余/对讲机都要能扫
                Text("波段", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScanBands.forEachIndexed { i, b ->
                        val on = i == bandIdx
                        OutlinedButton(
                            onClick = { bandIdx = i },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (on) Color(0xFF00E5FF) else Color(0xFF1A2230),
                                contentColor = if (on) Color(0xFF06121A) else Color(0xFF8FA3B8)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text(b.name, fontSize = 11.sp) }
                    }
                }
                val points = ((band.toHz - band.fromHz) / band.stepHz).toInt() + 1
                val seconds = points * 90 / 1000
                Text(
                    if (scanProgress in 0..100)
                        "扫描 ${formatFreq(band.fromHz)}–${formatFreq(band.toHz)}，请稍候"
                    else "${band.name}：${formatFreq(band.fromHz)}–${formatFreq(band.toHz)}，" +
                        "约 ${points} 个频点，约需 $seconds 秒。",
                    fontSize = 13.sp
                )
                if (scanProgress in 0..100) {
                    LinearProgressIndicator(
                        progress = { scanProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = Color(0xFF00E5FF)
                    )
                }
                // 实测噪声底：真机搜不到台时，这一行能直接说明是"信号太弱"还是"阈值不对"
                floorDb?.let { fl ->
                    Text("实测噪声底 ${"%.1f".format(fl)}dB · 门限 ${"%.1f".format(fl + 8f)}dB",
                        fontSize = 11.sp, color = Color(0xFF5A6B7F))
                }
                if (scanFound.isEmpty()) {
                    Text(
                        if (scanCancelled) "已取消扫描，没找到台。点「开始扫描」重来。"
                        else "尚未找到电台。点右下角「开始扫描」，约 $seconds 秒。",
                        fontSize = 13.sp, color = Color(0xFF5A6B7F))
                } else {
                    if (scanCancelled) {
                        // 中途取消时已找到的台照样能用，只是要说清楚不是扫完的
                        Text("已取消扫描（找到 ${scanFound.size} 个台，可直接试听/收藏）",
                            fontSize = 12.sp, color = Color(0xFFF6D55C))
                    }
                    // 正在试听哪个台：点频率只调谐不关窗，用户可以连续试听，
                    // 听满意了再点 ★ 收藏——原来一点就关窗口，想收藏还得重新打开对话框。
                    audition?.let { f ->
                        Text("正在试听 ${formatFreq(f)} · 点「关闭」返回主界面",
                            fontSize = 12.sp, color = Color(0xFF00E5FF))
                    }
                    // 高度受限 + 可滚动：扫出 20 个台也不会把对话框顶出屏幕
                    LazyColumn(Modifier.height(300.dp).fillMaxWidth()) {
                        items(scanFound, key = { it.freq }) { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    engine.tuneTo(st.freq)
                                    // 切到该波段的收听模式：FM 广播测量用 NFM 更准，
                                    // 但收听必须是 WFM，否则听到的是单声道窄带音频
                                    engine.selectMode(band.listenMode)
                                    audition = st.freq
                                }) { Text(formatFreq(st.freq), fontSize = 14.sp) }
                                Text("${st.levelDb.toInt()}dB", fontSize = 11.sp,
                                    color = Color(0xFF5A6B7F), modifier = Modifier.weight(1f))
                                // ★ 可点：单独收藏/取消收藏这一个台
                                val saved = favFreqs.contains(st.freq)
                                TextButton(
                                    onClick = {
                                        if (saved) {
                                            store.removeFavorite(st.freq)
                                            favFreqs.remove(st.freq)
                                        } else {
                                            store.addFavorite(
                                                com.pocketsdr.app.FreqStore.Ch(
                                                    formatFreq(st.freq), st.freq,
                                                    FreqStore.modeIndex(band.listenMode))
                                            )
                                            favFreqs.add(st.freq)
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text(if (saved) "★" else "☆", fontSize = 16.sp,
                                        color = if (saved) Color(0xFF00E5FF) else Color(0xFF5A6B7F))
                                }
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
                    floorDb = null
                    val sc = FmScanner(engine,
                        onProgress = { p, _ -> onProgress(p) },
                        onDone = { stations ->
                            onFound(stations.sortedByDescending { it.levelDb })
                            floorDb = scanner?.noiseFloorDb
                            onProgress(101)
                        })
                    scanner = sc
                    scanCancelled = false
                    // 按选中的波段扫：范围、步进、测量模式都跟着波段走。
                    // 测量模式必须匹配：航空对讲是 AM，用 NFM 测会一个台都搜不到。
                    sc.scan(band.fromHz, band.toHz, band.stepHz, null, band.mode)
                }
            }) {
                if (scanProgress in 0..100) {
                    // 扫描中：按钮变成「取消」。
                    // 长波段（短波/对讲 UHF）要扫 45~54 秒，不能让人干等没法中止——
                    // 引擎的 muted 状态会一直挂着，用户连原来的台都听不到。
                    TextButton(onClick = {
                        scanCancelled = true
                        scanner?.cancel()
                    }) { Text("取消", color = Color(0xFFFF6B6B)) }
                } else {
                    Text("开始扫描")
                }
            }
        },
        dismissButton = {
            Row {
                if (scanProgress > 100 && scanFound.isNotEmpty()) {
                    // 已经全收藏了就别再显示这个按钮——重复点没有意义，
                    // 用户会以为收藏出了重复项
                    val allSaved = scanFound.all { favFreqs.contains(it.freq) }
                    if (!allSaved) {
                        TextButton(onClick = {
                            scanFound.forEach { st ->
                                store.addFavorite(com.pocketsdr.app.FreqStore.Ch(
                                    formatFreq(st.freq), st.freq,
                                    FreqStore.modeIndex(band.listenMode)))
                                if (!favFreqs.contains(st.freq)) favFreqs.add(st.freq)
                            }
                        }) { Text("★ 全存收藏") }
                    }
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
    // 播放器：对话框内单实例，正在播的条目高亮 + 图标切换
    var playingPath by remember { mutableStateOf<String?>(null) }
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            try { player.stop() } catch (_: IllegalStateException) {}
            player.release()
        }
    }
    fun stopPlay() {
        try { player.stop() } catch (_: IllegalStateException) {}
        playingPath = null
    }
    AlertDialog(
        onDismissRequest = { stopPlay(); onDismiss() },
        title = { Text("录音文件 (${files.size})") },
        text = {
            if (files.isEmpty()) {
                Text("暂无录音。设置页点「⏺ 录音」开始。", fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.height(340.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        val isPlaying = playingPath == f.absolutePath
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            IconButton(onClick = {
                                if (isPlaying) { stopPlay(); return@IconButton }
                                try {
                                    player.reset()
                                    player.setDataSource(f.absolutePath)
                                    player.prepare()
                                    player.start()
                                    playingPath = f.absolutePath
                                    player.setOnCompletionListener { playingPath = null }
                                } catch (_: Exception) {}
                            }, modifier = Modifier.padding(0.dp)) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    if (isPlaying) "停止" else "播放",
                                    tint = if (isPlaying) Color(0xFFF6D55C) else Color(0xFF00E5FF),
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                // 原始文件名长这样：sdr_20261021_023015_98300kHz.wav
                                // 直接甩给用户等于让人自己数数字。解析成
                                // 「98.300 MHz · 10-21 02:30」才是人能读的东西。
                                val nic = niceRecordingLabel(f)
                                Text(nic.title, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = if (isPlaying) Color(0xFFF6D55C) else Color(0xFFD0DAE4),
                                    maxLines = 1)
                                Text(
                                    "${nic.sub}  " + wavDuration(f) + "  %.1fMB".format(f.length() / 1e6),
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
                            IconButton(onClick = {
                                if (isPlaying) stopPlay()
                                f.delete(); files = listWavFiles(context)
                            }) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { stopPlay(); onDismiss() }) { Text("关闭") } }
    )
}

private fun listWavFiles(context: android.content.Context): List<File> {
    val dir = File(context.getExternalFilesDir(null), "recordings")
    return dir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/**
 * 把录音文件名解析成能直接读的标题 + 副标题。
 *
 * 文件名由 WavRecorder 生成：sdr_yyyyMMdd_HHmmss_<频率kHz>.wav
 * 例：sdr_20261021_023015_98300kHz.wav
 *
 * 录音最重要的是知道「录的是哪个台」——文件名里本来就有，但用户看到的是
 * 机器名，得自己数数字。解析成「98.300 MHz」才是人能读的东西。
 * 解析不了（老文件、手动改过名）就回退到原始文件名，不丢人。
 */
private data class RecLabel(val title: String, val sub: String)

private val RecNameRe = Regex("""sdr_(\d{8})_(\d{6})_(\d+)kHz\.wav""")

private fun niceRecordingLabel(f: File): RecLabel {
    val m = RecNameRe.find(f.name)
    if (m == null) {
        return RecLabel(f.name, SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))
    }
    val freqHz = m.groupValues[3].toLongOrNull()?.times(1000L)
    val date = m.groupValues[1]   // yyyyMMdd
    val time = m.groupValues[2]   // HHmmss
    val title = if (freqHz != null) formatFreq(freqHz) else f.name
    // 文件名里的日期时间本来就是格式化好的数字串，直接切片，
    // 不用再 parse 成 Date（parse 还会受时区影响，纯属绕路）
    val sub = try {
        val mm = date.substring(4, 6)
        val dd = date.substring(6, 8)
        val hh = time.substring(0, 2)
        val mi = time.substring(2, 4)
        "$mm-$dd $hh:$mi"
    } catch (_: Exception) {
        "$date $time"
    }
    return RecLabel(title, sub)
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
