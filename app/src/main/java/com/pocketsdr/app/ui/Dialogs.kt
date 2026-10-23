package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun OnboardingDialog(engine: SdrEngine? = null, onDismiss: () -> Unit) {
    // 当前是不是真机：以前这条写死成「现在是仿真模式」，
    // 插上硬件之后这句话就变成假的了。
    val real = engine?.isRealSource == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📻 怎么用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    if (real) "已连上硬件：现在听到的就是真实频段"
                    else "现在是仿真模式（没插硬件也能玩）；RTL-SDR 棒子插上自动变真收音机",
                    fontSize = 13.sp, color = if (real) Color(0xFF00E5FF) else Color(0xFF8FA3B8)
                )
                Text("—— 全部功能都有按钮，不用记手势 ——", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                Text("① 换台：收音页点频谱上的尖峰，或点左下角大数字输入频率", fontSize = 13.sp)
                Text("② 微调：点大数字右边的 ▲▼ 步进（长按连续跳）", fontSize = 13.sp)
                Text("③ 找台：收音页点「🔍 搜台」，选波段自动扫，结果点一下就能听", fontSize = 13.sp)
                Text("④ 收台：点频率右边的 ★ 图标存起来，点「★ N」翻出收藏", fontSize = 13.sp)
                Text("⑤ 录一段：点「⏺ 录音」，录完点底部工具栏的音乐图标进文件里回放/分享",
                    fontSize = 13.sp)
                Text("⑥ 收窄频谱：点频谱右上角「视野」旁的 ⟲ 可复位放大", fontSize = 13.sp)
                Text("⑦ 换调制：收音页第二行直接点 WFM / NFM / AM / SSB", fontSize = 13.sp)
                Text("⑧ 硬件问题：设置页「USB 诊断」里有完整的排查信息和自检报告",
                    fontSize = 13.sp)
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
    /** 已通过常用频段跳到的频率（null = 没跳过） */
    var jumped by remember { mutableStateOf<Long?>(null) }
    // 输入校验：以前输入非法频率（比如 9999 或 abc）点了「调谐」什么也不发生，
    // 对话框直接关掉——用户完全不知道自己输错了，更不知道该输什么。
    val parsedMhz = remember(text) {
        text.trim().replace('．', '.').replace(',', '.').replace('。', '.').toFloatOrNull()
    }
    val freqError: String? = remember(text) {
        val raw = text.trim()
        when {
            raw.isEmpty() -> null                 // 空着不算错，只是还没输
            parsedMhz == null -> "看不懂这个输入，请填数字，比如 98.1"
            parsedMhz < 0.1f -> "频率太小了，最少 0.1 MHz"
            parsedMhz > 1800f -> "太大了，SDR 最多到 1800 MHz"
            else -> null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入频率 (MHz)") },
        text = {
            // 整体限高可滚：输入框 + 最近列表 + 常用频段叠起来可能超过对话框高度，
            // 超出部分会被裁掉，用户看不到底下的频段预设
            Column(
                Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("98.1") }, singleLine = true,
                    isError = freqError != null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                // 明确的错误提示：告诉用户哪里错了、该填什么
                freqError?.let {
                    Text(it, fontSize = 12.sp, color = Color(0xFFFF6B6B))
                }
                if (recents.isNotEmpty()) {
                    Text("最近", fontSize = 12.sp, color = Color(0xFF5A6B7F))
                    // 用可滚动的 Column 而不是 Row：Row 放不下会溢出，且不能滚
                    Column(
                        Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())
                    ) {
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
                                    // 不关窗：可以连续试几个频段，选定后再点「取消」离开
                                    jumped = p.freqHz
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
                // 已跳到哪个频段：告诉用户现在在听哪儿，也提示怎么回去
                jumped?.let { f ->
                    Text("已跳到 ${formatFreq(f)} · 点「取消」返回主界面",
                        fontSize = 12.sp, color = Color(0xFF00E5FF))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedMhz != null && freqError == null,
                onClick = {
                    val mhz = parsedMhz ?: return@TextButton
                    if (mhz in 0.1f..1800f) {
                        val hz = (mhz * 1_000_000).toLong()
                        engine.tuneTo(hz)
                        store.addRecent(hz)
                    }
                    // 输入非法时不关窗：留在原地让用户改，
                    // 关掉等于把「你输错了」这件事藏起来
                    if (freqError == null) onDismiss()
                }
            ) { Text("调谐") }
        },
        dismissButton = {
            Row {
                // 「返回」而不是「取消」：这个对话框现在也能用来连续试频段，
                // 「取消」听起来像「撤销刚才的操作」，会误导
                TextButton(onClick = onDismiss) { Text("返回") }
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
    // 排序：按频率（找特定频段）/ 按加入顺序（找最近收听的）
    // 从 store 读初值：排序是使用习惯，每次进来重设很烦
    var sortByFreq by remember { mutableStateOf(store.favSortByFreq) }
    /** 正在试听的收藏台（点收藏项只调谐、不关窗） */
    var audition by remember { mutableStateOf<Long?>(null) }
    // 试听调谐是否被硬件拒绝（调谐是异步的，延迟一点才能读到结果）
    var auditionFailed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(audition) {
        auditionFailed = false
        if (audition != null) {
            kotlinx.coroutines.delay(300)
            auditionFailed = !engine.lastTuneOk
        }
    }
    val shownFavorites = remember(favorites, sortByFreq) {
        if (sortByFreq) favorites.sortedBy { it.freq } else favorites
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏频道 (${favorites.size})") },
        text = {
            Column {
                // 收藏数据有损坏：以前整份解析失败就静默显示空列表，
                // 用户以为收藏被清空了。现在明确说出来。
                if (store.favoritesLoadFailed) {
                    Text(
                        if (store.favoritesUnreadable)
                            "⚠ 收藏数据无法读取，已停止写入以免损坏原始数据（本次不能新增/删除）"
                        else
                            "⚠ 收藏里有损坏的条目，已跳过（其余条目正常）",
                        fontSize = 11.sp, color = Color(0xFFF6D55C)
                    )
                }
                if (favorites.isEmpty()) {
                    Text("暂无收藏。收听到喜欢的台后点「★ 收藏当前」添加。", fontSize = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("排序", fontSize = 11.sp, color = Color(0xFF5A6B7F))
                        TextButton(onClick = {
                            sortByFreq = !sortByFreq
                            store.favSortByFreq = sortByFreq
                        },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (sortByFreq) "按频率" else "按加入顺序", fontSize = 11.sp,
                                color = Color(0xFF00E5FF))
                        }
                    }
                    // 正在试听：点收藏项只切台不关窗，可以连续对比多个台
                    audition?.let { f ->
                        Text("正在试听 ${formatFreq(f)} · 点「关闭」返回主界面",
                            fontSize = 12.sp, color = Color(0xFF00E5FF))
                        // 硬件没接受这次调谐：听到的还是原来那个台
                        if (auditionFailed) {
                            Text("⚠ 硬件没接受这次调谐（还是原来那个台）——检查 USB 连接后重试",
                                fontSize = 11.sp, color = Color(0xFFFF6B6B))
                        }
                    }
                }
                // 列表必须能滚：收藏多了以后超出对话框的部分会被直接裁掉，
                // 用户根本看不到后面的条目，也就没法跳台或删除。
                // 限高 300.dp，条目再多也只占这一块。
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                ) {
                    shownFavorites.forEach { ch ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            // 模式标签：收藏里混着 FM/AM/NFM/SSB 时，不标模式分不清
                            val mLabel = SdrEngine.Mode.entries.getOrNull(ch.mode)?.label ?: "?"
                            TextButton(onClick = {
                                engine.tuneTo(ch.freq)
                                engine.selectMode(FreqStore.modeFromIndex(ch.mode))
                                mode = engine.mode
                                tuned = engine.tunedFreq
                                // 不关窗：可以连续试听多个收藏台，对比着选
                                audition = ch.freq
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        mLabel,
                                        fontSize = 10.sp,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.padding(end = 5.dp)
                                    )
                                    Text("${ch.name} · ${formatFreq(ch.freq)}", fontSize = 14.sp)
                                }
                            }
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 收藏名优先用 RDS 电台名，没有 RDS 时退回频率（与主界面收藏行为一致）
                val label = engine.rds.stationName.trim().ifEmpty { formatFreq(engine.tunedFreq) }
                store.addFavorite(FreqStore.Ch(
                    label,
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

/**
 * RDS 诊断面板。
 *
 * 台名读不出来时，用户光看「未收到 RDS」这一行字没法判断问题在哪：
 * 是这台电台根本没发 RDS？是信号太弱？还是解码链路坏了？
 *
 * 这里把解码器内部的状态摊开：
 *  - 台名进度（收到 4 段中的几段）
 *  - 通过 CRC 的块数（>0 说明链路是通的）
 *  - 最佳判决相位（-1 说明还没锁定）
 *  - 信号强度与信噪比的定性判断
 */
@Composable
fun RdsDiagDialog(
    name: String,
    crcOk: Int,
    segments: Int,
    bestPhase: Int,
    signalDb: Float,
    onDismiss: () -> Unit,
    piCode: Int = -1,
    groupTypes: String = "",
    rtText: String = "",
    rtSegments: Int = 0,
    pty: Int = -1
) {
    // 台名进度条：4 段，每段 2 个字符
    val segText = buildString {
        for (i in 0 until 4) append(if (i < segments) "■" else "□")
    }
    val verdict = when {
        name.isNotEmpty() -> "已锁定台名「$name」"
        crcOk == 0 -> "没收到可校验的 RDS 块——多半这台电台没发 RDS，或信号太弱"
        segments == 0 -> "RDS 块能校验通过，但没解出字符——可能段地址或字符位有问题"
        else -> "已解出部分台名（$segments/4 段），信号偏弱，还在等剩下的段"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RDS 诊断") },
        text = {
            Column {
                Text(verdict, fontSize = 13.sp, color = Color(0xFF9FB4CC))
                Spacer(Modifier.height(10.dp))
                Text("台名进度  $segText  ($segments/4 段)", fontSize = 12.sp)
                if (rtSegments > 0) {
                    Text("滚动文本进度  $rtSegments/16 段", fontSize = 12.sp)
                }
                Text(
                    "PI 码（电台识别）  " + if (piCode >= 0) piDescribe(piCode) else "未锁定",
                    fontSize = 12.sp
                )
                if (groupTypes.isNotEmpty()) {
                    Text("收到的组  $groupTypes", fontSize = 12.sp)
                }
                if (pty in 0..31) {
                    Text("节目类型  " + ptyName(pty) + "（PTY $pty）", fontSize = 12.sp)
                }
                Text("CRC 通过块数  $crcOk", fontSize = 12.sp)
                Text(
                    "判决相位  " + if (bestPhase >= 0) "第 $bestPhase 号候选已锁定" else "未锁定",
                    fontSize = 12.sp
                )
                Text(
                    "信号  " + "%.1f".format(signalDb) + " dB" +
                        if (signalDb > -60) "（够强）" else "（偏弱，RDS 可能读不出）",
                    fontSize = 12.sp
                )
                if (rtText.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("滚动文本  $rtText", fontSize = 12.sp, color = Color(0xFFB9E6A8))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "台名最长 8 个字符分 4 段发送，滚动文本最长 64 个字符分 16 段发送，" +
                        "每段重复若干遍后才确认——弱信号下先显示已确认的部分。",
                    fontSize = 11.sp, color = Color(0xFF5A6B7F)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
