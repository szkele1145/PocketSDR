package com.pocketsdr.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    // 扫描结束后的说明（取消/失败/有频点被丢弃）。null = 正常扫完，不用多说。
    var scanNote by remember { mutableStateOf<String?>(null) }
    // 结果排序：默认按信号强度降序——扫出 20 个台时，用户最想先听的是最强的那几个，
    // 按频率排会把弱台和强台混在一起。记住上次的选择。
    var sortByLevel by remember { mutableStateOf(store.scanSortByLevel) }
    val shownList = if (sortByLevel) scanFound.sortedByDescending { it.levelDb } else scanFound
    // 扫到哪了：扫描线程每 90ms 跳一个频点，主界面状态行是 250ms 轮询，
    // 这里自己 200ms 轮一次 tunedFreq，用户就能看到频率一格一格走
    var scanCurFreq by remember { mutableStateOf<Long?>(null) }
    // 试听调谐失败的提示：调谐是异步的（UI 入队 → DSP 线程 → 硬件 I2C 写），
    // 点了频率后要等一下才知道硬件到底接不接受。
    var auditionFailed by remember { mutableStateOf(false) }
    // 试听时实时显示 RDS 信息，减少真机盲目
    var audRdsName by remember { mutableStateOf("") }
    var audRdsText by remember { mutableStateOf("") }
    var audRdsPty by remember { mutableStateOf(-1) }
    var audRdsTa by remember { mutableStateOf(false) }
    var audRdsTp by remember { mutableStateOf(false) }
    var audRdsLock by remember { mutableStateOf(false) }
    LaunchedEffect(audition) {
        auditionFailed = false
        audRdsName = ""
        audRdsText = ""
        audRdsPty = -1
        audRdsTa = false
        audRdsTp = false
        audRdsLock = false
        if (audition != null) {
            kotlinx.coroutines.delay(300)
            auditionFailed = !engine.lastTuneOk
        }
    }
    LaunchedEffect(audition) {
        if (audition == null) {
            audRdsName = ""
            audRdsText = ""
            audRdsPty = -1
            audRdsTa = false
            audRdsTp = false
            audRdsLock = false
            return@LaunchedEffect
        }
        while (true) {
            val r = engine.rds
            audRdsName = r.stationName
            audRdsText = r.radioText
            audRdsPty = r.pty
            audRdsTa = r.trafficAnnouncement
            audRdsTp = r.trafficProgram
            audRdsLock = r.dataLock
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(scanner != null) {
        while (scanner != null) {
            scanCurFreq = engine.tunedFreq
            kotlinx.coroutines.delay(200)
        }
        scanCurFreq = null
    }
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
                    // 正在扫的频率：只看进度条不知道扫到哪儿了，
                    // 频率一格格走才直观地看出「它在正常工作」
                    scanCurFreq?.let { cf ->
                        Text("正在扫 ${formatFreq(cf)}", fontSize = 12.sp, color = Color(0xFF00E5FF))
                    }
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
                    // 排序切换：强度优先（默认）看最强的台，频率序像一张频段地图
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("排序", fontSize = 11.sp, color = Color(0xFF5A6B7F))
                        TextButton(onClick = {
                            sortByLevel = !sortByLevel
                            store.scanSortByLevel = sortByLevel
                        }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                            Text(
                                if (sortByLevel) "按强度" else "按频率",
                                fontSize = 12.sp,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }
                    // 正在试听哪个台：点频率只调谐不关窗，用户可以连续试听，
                    // 听满意了再点 ★ 收藏——原来一点就关窗口，想收藏还得重新打开对话框。
                    audition?.let { f ->
                        val rdsLine = buildString {
                            if (audRdsName.isNotEmpty()) append("「${audRdsName}」")
                            if (audRdsTa) append(" ·TA")
                            else if (audRdsTp) append(" ·TP")
                            if (audRdsPty in 0..31) append(" ·${ptyName(audRdsPty)}")
                            if (audRdsText.isNotEmpty()) {
                                val rt = if (audRdsText.length > 60) audRdsText.take(60) + "…" else audRdsText
                                append("  |  $rt")
                            }
                        }
                        Column {
                            Text("正在试听 ${formatFreq(f)} · 点「关闭」返回主界面",
                                fontSize = 12.sp, color = Color(0xFF00E5FF))
                            if (rdsLine.isNotEmpty()) {
                                Text(rdsLine, fontSize = 11.sp, color = if (audRdsTa) Color(0xFFFF5252) else Color(0xFF8FA3B8), maxLines = 2)
                            } else {
                                Text(if (audRdsLock) "RDS 搜寻中…" else "无 RDS 信息", fontSize = 11.sp, color = Color(0xFF5A6B7F))
                            }
                        }
                    }
                    if (auditionFailed) {
                        Text("⚠ 硬件没接受这次调谐（还是原来那个台）——检查 USB 连接后重试",
                            fontSize = 11.sp, color = Color(0xFFFF6B6B))
                    }
                    // 扫描结束说明：取消 / 失败 / 有频点被丢弃，都要说清楚。
                    scanNote?.let { n ->
                        Text(n, fontSize = 11.sp, color = Color(0xFFF6D55C))
                    }
                    // 高度受限 + 可滚动：扫出 20 个台也不会把对话框顶出屏幕
                    LazyColumn(Modifier.height(300.dp).fillMaxWidth()) {
                        items(shownList, key = { it.freq }) { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    engine.tuneTo(st.freq)
                                    // 切到该波段的收听模式：FM 广播测量用 NFM 更准，
                                    // 但收听必须是 WFM，否则听到的是单声道窄带音频
                                    engine.selectMode(band.listenMode)
                                    audition = st.freq
                                }) {
                                    // 「±」标记：这个频率是细扫出来的真实载频，
                                    // 不是粗扫的网格点——用户按它调谐能对得更准。
                                    // 有 RDS 台名就显示台名（扫的时候就顺手读了），
                                    // 没有就退回频率
                                    Text(
                                        if (st.name.isNotEmpty()) st.name
                                        else formatFreq(st.freq) + if (st.refined) " ±" else "",
                                        fontSize = 14.sp
                                    )
                                    // 有台名时频率放右边小字，两个信息都不丢
                                    if (st.name.isNotEmpty()) {
                                        Text(
                                            formatFreq(st.freq) + if (st.refined) " ±" else "",
                                            fontSize = 11.sp, color = Color(0xFF5A6B7F)
                                        )
                                    }
                                }
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
                                                    // 有台名就用台名当收藏名，否则退回频率
                                                    st.name.ifEmpty { formatFreq(st.freq) }, st.freq,
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
                            // 扫完要说清楚发生了什么：正常扫完 / 被取消 / 硬件调谐失败。
                            // 以前一律显示「扫描完成，找到 N 个台」——取消时也这么显示，
                            // 用户会以为剩下的频段确实没有台。
                            val s = scanner
                            scanNote = when {
                                s?.scanFailed == true ->
                                    "扫描失败：硬件调谐全部未成功，检查 USB 连接后再试"
                                s?.wasCancelled == true ->
                                    "已取消（只扫了一部分，剩下的频段没扫）"
                                (s?.tuneFailures ?: 0) > 0 ->
                                    "扫描完成，${stations.size} 个台（${s?.tuneFailures} 个频点调谐失败已丢弃）"
                                else -> null
                            }
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
                                if (favFreqs.contains(st.freq)) return@forEach
                                store.addFavorite(com.pocketsdr.app.FreqStore.Ch(
                                    st.name.ifEmpty { formatFreq(st.freq) }, st.freq,
                                    FreqStore.modeIndex(band.listenMode)))
                                favFreqs.add(st.freq)
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
                    // 崩溃堆栈是多行的：只有第一行带 E 标记，后面几十行堆栈
                    // 都是普通行。这里做一次「崩溃态传染」——一旦出现 CRASH，
                    // 紧随其后的缩进行（at xxx / Caused by / ...）都按错误色显示，
                    // 否则用户在一片灰色里根本看不出这是一整段异常。
                    var inCrash = false
                    logLines.take(400).forEach { line ->
                        if (line.contains("CRASH")) inCrash = true
                        else if (line.isNotBlank() && !line.trimStart().startsWith("at ") &&
                            !line.trimStart().startsWith("Caused by") &&
                            !line.trimStart().startsWith("...") &&
                            !line.trimStart().startsWith("Suppressed")) {
                            inCrash = false
                        }
                        Text(line, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            color = when {
                                inCrash || line.contains("/E/") -> Color(0xFFFF5252)
                                line.contains("/W/") -> Color(0xFFF6D55C)
                                else -> Color(0xFF8FA3B8)
                            })
                    }
                }
            }
        },
        confirmButton = {
            val context = LocalContext.current
            Row {
                TextButton(onClick = {
                    com.pocketsdr.app.SdrLog.save(context)?.let {
                        android.widget.Toast.makeText(context, "已保存: $it", android.widget.Toast.LENGTH_SHORT).show()
                    } ?: android.widget.Toast.makeText(
                        context, "日志落盘失败（存储不可用）", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Text("落盘") }
                // 分享：落盘只给出一个文件路径，用户还得自己去文件管理器翻。
                // 崩溃排查的实际动作是「把日志发给开发者」，所以直接给分享入口。
                TextButton(onClick = {
                    val path = com.pocketsdr.app.SdrLog.save(context)
                    if (path == null) {
                        android.widget.Toast.makeText(
                            context, "日志落盘失败（存储不可用），无法分享",
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".files",
                                java.io.File(path))
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "PocketSDR 运行日志")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "分享日志"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context, "分享失败：${e.message ?: "没有可用的应用"}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("分享") }
            }
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
    // 播放/分享/删除的失败原因。
    // 以前这三处都是空 catch：点播放没反应、点分享没反应、删除后文件又回来了，
    // 全都没有任何提示，用户完全不知道发生了什么。
    var recNote by remember { mutableStateOf<String?>(null) }
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
            Column {
                // 失败提示放在最上面：播放/分享/删除都可能失败，
                // 不说的话用户只会看到「点了没反应」。
                val note = recNote
                if (note != null) {
                    Text("⚠ $note", fontSize = 11.sp, color = Color(0xFFFF6B6B))
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.height(4.dp))
                }
            if (files.isEmpty()) {
                Text("暂无录音。设置页点「⏺ 录音」开始。", fontSize = 13.sp)
            } else {
                // 说明录音的收录规则：用户静音或静噪关闭时，录音照样继续，
                // 所以文件里会有「没信号的时段」。不解释的话，
                // 回放时看到一段静音会以为录坏了。
                Text("录音始终收录：静音/静噪关闭时扬声器不出声，但文件照录（空段是正常的）",
                    fontSize = 10.sp, color = Color(0xFF5A6B7F))
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(4.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        val isPlaying = playingPath == f.absolutePath
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            IconButton(onClick = {
                                if (isPlaying) { stopPlay(); return@IconButton }
                                recNote = null
                                try {
                                    player.reset()
                                    player.setDataSource(f.absolutePath)
                                    player.prepare()
                                    player.start()
                                    playingPath = f.absolutePath
                                    player.setOnCompletionListener { playingPath = null }
                                } catch (e: Exception) {
                                    // 播放失败必须说出来：文件损坏/被删/编码不支持时，
                                    // 以前是空 catch，用户只看到「点了没反应」。
                                    playingPath = null
                                    recNote = "无法播放 ${f.name}：${e.message?.takeIf { it.isNotBlank() } ?: "文件可能已损坏"}"
                                }
                            }, modifier = Modifier.padding(0.dp)) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    if (isPlaying) "停止" else "播放",
                                    tint = if (isPlaying) Color(0xFFF6D55C) else Color(0xFF00E5FF),
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                            val nic = niceRecordingLabel(f)
                            var showMenu by remember { mutableStateOf(false) }
                            @OptIn(ExperimentalFoundationApi::class)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showMenu = true }
                                    )
                            ) {
                                Text(nic.title, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = if (isPlaying) Color(0xFFF6D55C) else Color(0xFFD0DAE4),
                                    maxLines = 1)
                                Text(
                                    "${nic.sub}  " + wavDuration(f) + "  %.1fMB".format(f.length() / 1e6),
                                    fontSize = 10.sp, color = Color(0xFF5A6B7F)
                                )
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("复制文件名") }, onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pocketsdr", f.name))
                                    recNote = "已复制：${f.name}"
                                    showMenu = false
                                })
                                DropdownMenuItem(text = { Text("在文件管理器中查看") }, onClick = {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                        intent.setDataAndType(uri, "audio/wav")
                                        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(android.content.Intent.createChooser(intent, "查看"))
                                    } catch (e: Exception) {
                                        recNote = "无法打开：${e.message?.takeIf { it.isNotBlank() } ?: "不支持"}"
                                    }
                                    showMenu = false
                                })
                                DropdownMenuItem(text = { Text("取消") }, onClick = { showMenu = false })
                            }
                            IconButton(onClick = {
                                recNote = null
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".files", f)
                                    context.startActivity(android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "audio/x-wav"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, nic.title)
                                            putExtra(android.content.Intent.EXTRA_TEXT, "${nic.title} · ${nic.sub}")
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "分享录音"))
                                } catch (e: Exception) {
                                    // 分享失败（没有可接收的应用 / 路径未被 provider 覆盖）
                                    // 以前是空 catch：点分享完全没反应。
                                    recNote = "无法分享 ${f.name}：${e.message?.takeIf { it.isNotBlank() } ?: "没有可用的分享应用"}"
                                }
                            }, modifier = Modifier.padding(0.dp)) {
                                Icon(Icons.Filled.Share, "分享", tint = Color(0xFF7DF3FF))
                            }
                            IconButton(onClick = {
                                recNote = null
                                if (isPlaying) stopPlay()
                                // 删除失败必须说明：以前不管成没成功都刷新列表，
                                // 删除不掉的文件会「又回来了」，看起来像删除没生效。
                                if (!f.delete()) {
                                    recNote = "无法删除 ${f.name}（文件被占用或没有权限）"
                                }
                                files = listWavFiles(context)
                            }) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFF5A6B7F))
                            }
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

// 文件名：sdr_<日期>_<时间>_<频率>kHz[_<台名>].wav
// 台名段是可选的（老录音没有；没收到 RDS 时也不会有）
private val RecNameRe = Regex("""sdr_(\d{8})_(\d{6})_(\d+)kHz(?:_([^.]+))?\.wav""")

private fun niceRecordingLabel(f: File): RecLabel {
    val m = RecNameRe.find(f.name)
    if (m == null) {
        return RecLabel(f.name, SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))
    }
    val freqHz = m.groupValues[3].toLongOrNull()?.times(1000L)
    val date = m.groupValues[1]   // yyyyMMdd
    val time = m.groupValues[2]   // HHmmss
    // 台名段（可选）：有就接在频率后面，回听时一眼认得出是哪个台
    val station = m.groupValues.getOrNull(4)?.replace('_', ' ')?.trim().orEmpty()
    val title = when {
        freqHz != null && station.isNotEmpty() -> formatFreq(freqHz) + " · " + station
        freqHz != null -> formatFreq(freqHz)
        else -> f.name
    }
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

/**
 * 增益自动寻优对话框。
 *
 * 让用户不用再「一档一档推着听」——扫完全部档位，量出每档的噪声底与峰值，
 * 挑出「不过载前提下信噪比最高」的那一档。
 *
 * 结果表要展示出来而不只是给一个结论：用户能看到「哪一档开始削顶」，
 * 这在排查天线/前端故障时是有用信息，不是纯装饰。
 */
@Composable
fun GainTuneDialog(
    engine: SdrEngine,
    onDismiss: () -> Unit,
    onAdopt: (gainTenthDb: Int) -> Unit
) {
    var progress by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.pocketsdr.app.GainScanner.Result?>(null) }
    var scanner by remember { mutableStateOf<com.pocketsdr.app.GainScanner?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 窗口关掉时必须停掉扫描线程：否则它会继续改增益，
            // 用户已经离开这个设置项了，硬件却还在被扫。
            scanner?.cancel()
        }
    }

    fun startScan() {
        result = null
        note = null
        progress = 0
        total = engine.lnaGains.size
        running = true
        val s = com.pocketsdr.app.GainScanner(
            engine,
            onProgress = { done, tot ->
                progress = done
                total = tot
            },
            onDone = { r ->
                result = r
                running = false
                if (r.failed) note = r.note
            }
        )
        scanner = s
        s.start()
    }

    AlertDialog(
        onDismissRequest = {
            scanner?.cancel()
            onDismiss()
        },
        title = { Text("增益自动寻优") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (running) {
                    Text("正在扫描 ${total} 个增益档…", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("$progress / $total", fontSize = 11.sp, color = Color(0xFF8FA3B8),
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("扫描期间会短暂改变增益，听到的声音变化是正常的。",
                        fontSize = 11.sp, color = Color(0xFF5A6B7F))
                } else if (result == null) {
                    Text("将逐档测量噪声底与谱峰，找出不过载前提下信噪比最高的增益档。",
                        fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("大约需要 ${total.coerceAtLeast(engine.lnaGains.size)} 秒。",
                        fontSize = 11.sp, color = Color(0xFF5A6B7F))
                } else {
                    val r = result!!
                    note?.let {
                        Text("⚠ $it", fontSize = 12.sp, color = Color(0xFFFF6B6B))
                        Spacer(Modifier.height(6.dp))
                    }
                    if (r.wasCancelled) {
                        Text("已取消（扫了一部分，结果仅供参考）", fontSize = 12.sp,
                            color = Color(0xFFF6D55C))
                        Spacer(Modifier.height(6.dp))
                    }
                    r.best?.let { b ->
                        Surface(color = Color(0xFF12261E), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("推荐增益：%.1f dB".format(b.gainTenthDb / 10f),
                                    fontSize = 15.sp, color = Color(0xFF00E5FF),
                                    fontFamily = FontFamily.Monospace)
                                Text("噪声底 %.0f dB · 峰值 %.0f dB · 信噪比 %.0f dB".format(
                                    b.floorDb, b.peakDb, b.snrDb),
                                    fontSize = 11.sp, color = Color(0xFF8FA3B8),
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (r.steps.isNotEmpty()) {
                        Text("全部档位", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                        Spacer(Modifier.height(3.dp))
                        r.steps.forEach { s ->
                            val isBest = r.best?.gainTenthDb == s.gainTenthDb
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text("%7.1f dB".format(s.gainTenthDb / 10f),
                                    fontSize = 10.sp,
                                    color = if (isBest) Color(0xFF00E5FF) else Color(0xFF8FA3B8),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(66.dp))
                                Text("噪 %5.0f 峰 %5.0f SNR %4.0f".format(s.floorDb, s.peakDb, s.snrDb),
                                    fontSize = 10.sp,
                                    color = when {
                                        s.clipped -> Color(0xFFFF5252)
                                        isBest -> Color(0xFF00E5FF)
                                        else -> Color(0xFF8FA3B8)
                                    },
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (r.steps.any { it.clipped }) {
                            Spacer(Modifier.height(4.dp))
                            Text("红色 = 该档已过载（ADC 削顶，或信噪比已比最佳档回落 3dB 以上）——再往上只会更糟。",
                                fontSize = 10.sp, color = Color(0xFFFF66B6B))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val r = result
            if (running) {
                TextButton(onClick = { scanner?.cancel() }) {
                    Text("停止", color = Color(0xFFFF6B6B))
                }
            } else if (r?.best != null) {
                TextButton(onClick = {
                    onAdopt(r.best.gainTenthDb)
                    onDismiss()
                }) { Text("采用推荐") }
            } else {
                TextButton(onClick = { startScan() }) { Text("开始扫描") }
            }
        },
        dismissButton = {
            if (!running && result == null) {
                TextButton(onClick = { startScan() }) { Text("开始扫描") }
            } else if (!running) {
                TextButton(onClick = { startScan() }) { Text("重新扫描") }
            } else {
                Spacer(Modifier.width(1.dp))
            }
        }
    )
}
