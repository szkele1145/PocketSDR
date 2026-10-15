package com.pocketsdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用外壳（横屏）：左侧窄导航轨 + 右侧大画布，参考 SDR# 桌面版的分区思路。
 * 横屏横向空间富余，导航放左侧不占垂直空间——频谱和瀑布能铺到最大，
 * 这是"竖屏上下翻页"最难用的地方：频谱被压扁、按钮要点两次才够得着。
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

    Scaffold(containerColor = Color(0xFF0B0E14)) { pad ->
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxSize().padding(pad).background(Color(0xFF0B0E14))
        ) {
            NavRail(page = page, onSelect = { page = it }, onExit = onExit)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (page) {
                    0 -> RxPage(
                        engine, spectrum, waterfall, status,
                        signalDb, stereoLock, rdsName
                    )
                    1 -> SettingsPage(engine, status, onExit = onExit)
                }
            }
        }
    }
}

/** 左侧导航轨：收音 / 设置 + 底部退出，每个按钮都带文字，不用猜图标 */
@Composable
private fun NavRail(page: Int, onSelect: (Int) -> Unit, onExit: () -> Unit) {
    Surface(color = Color(0xFF101826), modifier = Modifier.fillMaxHeight().width(76.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight().padding(vertical = 10.dp)
        ) {
            RailButton(Icons.Filled.Tune, "收音", page == 0) { onSelect(0) }
            Spacer(Modifier.height(10.dp))
            RailButton(Icons.Filled.Settings, "设置", page == 1) { onSelect(1) }
            Spacer(Modifier.height(10.dp))
            RailButton(Icons.Filled.ExitToApp, "退出", false, activeColor = Color(0xFFFF7961)) {
                onExit()
            }
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    activeColor: Color = Color(0xFF00E5FF),
    onClick: () -> Unit
) {
    val tint = if (selected) activeColor else Color(0xFF5A6B7F)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(66.dp)
            .clickable { onClick() }
            .background(
                if (selected) Color(0x3300E5FF) else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(vertical = 9.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
