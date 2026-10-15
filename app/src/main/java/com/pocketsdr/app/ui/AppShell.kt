package com.pocketsdr.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 应用外壳：底部导航双页面（收音 / 设置）——参考 SDR-LBJ 的分页 UX。
 * 主页面只留频谱+频率+音量（拇指直接操作），
 * 模式/波段/静噪/增益/搜台/日志全部收进设置页，不再挤一屏。
 */
@Composable
fun AppShell(
    engine: com.pocketsdr.app.SdrEngine,
    spectrum: FloatArray,
    waterfall: WaterfallBitmap,
    status: String,
    signalDb: Float,
    stereoLock: Float,
    rdsName: String,
    onExit: () -> Unit
) {
    var page by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = Color(0xFF0B0E14),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101826)) {
                val selColor = Color(0xFF00E5FF)
                val dimColor = Color(0xFF5A6B7F)
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { page = 0 },
                    icon = { Icon(Icons.Filled.Tune, null, tint = if (page == 0) selColor else dimColor) },
                    label = { Text("收音", color = if (page == 0) selColor else dimColor) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0x3300E5FF))
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { page = 1 },
                    icon = { Icon(Icons.Filled.Settings, null, tint = if (page == 1) selColor else dimColor) },
                    label = { Text("设置", color = if (page == 1) selColor else dimColor) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0x3300E5FF))
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (page) {
                0 -> RxPage(engine, spectrum, waterfall, status, signalDb, stereoLock, rdsName, onExit = onExit)
                1 -> SettingsPage(engine, status, onExit = onExit)
            }
        }
    }
}


