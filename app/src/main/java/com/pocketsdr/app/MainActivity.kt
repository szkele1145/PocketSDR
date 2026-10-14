package com.pocketsdr.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.pocketsdr.app.ui.SdrScreen

/**
 * 主 Activity：持有 SdrEngine，桥接 DSP 线程 → Compose 状态。
 */
class MainActivity : ComponentActivity(), UiBridge {

    private var engine: SdrEngine? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // Compose 状态（主线程写）
    private var spectrum by mutableStateOf(FloatArray(0))
    private var status by mutableStateOf("初始化…")
    private var signalDb by mutableStateOf(-120f)
    private var stereoLock by mutableStateOf(0f)
    private var engineReady by mutableStateOf(false)

    // 瀑布缓冲（主线程读写，DSP 线程 post 过来）
    private val waterfall = ArrayDeque<FloatArray>()
    private val waterfallMaxRows = 100

    // 推送节流：DSP 线程直接 post 行数据，主线程入列
    override fun pushWaterfallRow(rowDb: FloatArray) {
        uiHandler.post {
            waterfall.addLast(rowDb)
            while (waterfall.size > waterfallMaxRows) waterfall.removeFirst()
            spectrum = engine?.latestSpectrum ?: spectrum
            signalDb = engine?.signalDb ?: -120f
            stereoLock = engine?.stereoLock ?: 0f
        }
    }

    override fun pushStatus(text: String) {
        uiHandler.post { status = text }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startEngine()
        setContent { Host() }
    }

    private fun startEngine() {
        if (engine != null) return
        engine = SdrEngine.simulated(this, 98_000_000).also { it.start() }
        engineReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        engine = null
    }

    @Composable
    private fun Host() {
        val eng = engine
        if (engineReady && eng != null) {
            SdrScreen(
                engine = eng,
                spectrum = spectrum,
                waterfall = waterfall,
                status = status,
                signalDb = signalDb,
                stereoLock = stereoLock,
                onExit = { finish() }
            )
        } else {
            Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0E14)),
                contentAlignment = Alignment.Center
            ) {
                Text("PocketSDR 启动中…", color = Color(0xFF8FA3B8))
            }
        }
    }
}
