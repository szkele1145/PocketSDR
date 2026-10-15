package com.pocketsdr.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.pocketsdr.app.source.rtl.RtlUsbSource
import com.pocketsdr.app.ui.AppShell

/**
 * 主 Activity：USB 设备监听/权限 + 引擎持有 + DSP→UI 状态桥。
 */
class MainActivity : ComponentActivity(), UiBridge {

    private var engine: SdrEngine? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager
    private lateinit var wakeLock: android.os.PowerManager.WakeLock

    // Compose 状态
    private var spectrum by mutableStateOf(FloatArray(0))
    private var status by mutableStateOf("初始化…")
    private var signalDb by mutableStateOf(-120f)
    private var stereoLock by mutableStateOf(0f)
    private var rdsName by mutableStateOf("")
    private var engineReady by mutableStateOf(false)

    // 瀑布 Bitmap 环形缓冲（1024 bin × 180 行）
    private val waterfallBmp = com.pocketsdr.app.ui.WaterfallBitmap(1024, 180)
    private var wfVersion by mutableStateOf(0)

    // ---------- UiBridge ----------
    override fun pushWaterfallRow(rowDb: FloatArray) {
        // DSP 线程直接写 Bitmap（synchronized 保护），UI 帧读取
        waterfallBmp.pushRow(rowDb)
        // 每 4 行更新一次文本状态（谱线数据由引擎双缓冲直接读）
        if (statusTick++ % 4 == 0) {
            uiHandler.post {
                wfVersion += 1
                signalDb = engine?.signalDb ?: -120f
                stereoLock = engine?.stereoLock ?: 0f
                rdsName = engine?.rds?.stationName ?: ""
            }
        }
    }

    private var statusTick = 0

    override fun pushStatus(text: String) {
        uiHandler.post { status = text }
    }

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // 收音机场景保持 CPU 运转（屏幕可熄灭省电）
        wakeLock = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PocketSDR::rx")
            .also { it.setReferenceCounted(false); it.acquire() }

        // USB 权限广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Android 13+ 必须声明导出标志（USB 系统广播需要 EXPORTED），否则 SecurityException 闪退
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        startEngine()
        setContent { Host() }
    }

    override fun onStop() {
        super.onStop()
        // 后台切换时即保存状态（系统可能随时回收进程，不一定走 onDestroy）
        engine?.let { eng ->
            try {
                val s = com.pocketsdr.app.FreqStore(this)
                s.lastFreq = eng.tunedFreq
                s.lastCenter = eng.centerFreq
                s.lastMode = com.pocketsdr.app.FreqStore.modeIndex(eng.mode)
                s.lastVolume = eng.volume
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRxService()
        if (wakeLock.isHeld) wakeLock.release()
        unregisterReceiver(usbReceiver)
        engine?.close()
        engine = null
        engineReady = false
    }

    // ---------- USB ----------
    private val ACTION_USB_PERMISSION = "com.pocketsdr.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null) maybeRequestPermission(d)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && d != null) switchToHardware(d)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    pushStatus("设备已拔出，回到仿真模式")
                    switchToSimulated()
                }
            }
        }
    }

    private fun maybeRequestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            switchToHardware(device)
            return
        }
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, pi)
    }

    /** 切到真实硬件（USB 权限已授予） */
    private fun switchToHardware(device: UsbDevice, centerHint: Long = 98_000_000, tuneHint: Long = 98_100_000) {
        try {
            val conn = usbManager.openDevice(device)
            if (conn == null) {
                pushStatus("USB 打开失败（设备忙？）")
                return
            }
            // 先停旧引擎（释放 AudioTrack/停止推 UiBridge），再起新的——避免双 DSP 线程并发
            engine?.let { old ->
                engine = null
                engineReady = false
                old.close()
            }
            val store = com.pocketsdr.app.FreqStore(this)
            val src = RtlUsbSource(conn, device, 1_024_000L)
            engine = SdrEngine.withSource(src, this).also {
                it.start()
                it.tuneTo(tuneHint)
                restoreCommon(it, store)
                engineReady = true
            }
            pushStatus("RTL-SDR 已连接 · FC0013")
        } catch (e: Exception) {
            pushStatus("硬件初始化失败: ${e.message?.take(60)}")
            switchToSimulated()
        }
    }

    private fun switchToSimulated() {
        val cur = engine?.tunedFreq ?: 98_100_000
        engine?.let { old ->
            engine = null
            engineReady = false
            old.close()
        }
        engine = SdrEngine.simulated(this, cur - (cur % 100_000)).also {
            it.start()
            engineReady = true
        }
        pushStatus("仿真模式")
    }

    private fun startEngine() {
        if (engine != null) return
        val store = com.pocketsdr.app.FreqStore(this)
        val lastF = store.lastFreq
        val lastC = store.lastCenter
        // 已插着设备且有权限？直接上硬件
        val dev = usbManager.deviceList.values.firstOrNull { isRtlDevice(it) }
        if (dev != null && usbManager.hasPermission(dev)) {
            switchToHardware(dev, lastC, lastF)
        } else {
            engine = SdrEngine.simulated(this, lastC).also {
                it.tuneWithinSpan(lastF)
                restoreCommon(it, store)
                it.start()
            }
            pushStatus("仿真模式 · 插入 RTL-SDR 自动连接")
        }
        startRxService(lastF)
        engineReady = true
    }

    private fun startRxService(freq: Long) {
        try {
            val intent = Intent(this, com.pocketsdr.app.RxService::class.java)
                .putExtra("freq", freq)
                .putExtra("mode", "WFM")
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun stopRxService() {
        try { stopService(Intent(this, com.pocketsdr.app.RxService::class.java)) } catch (_: Exception) {}
    }

    private fun restoreCommon(eng: SdrEngine, store: com.pocketsdr.app.FreqStore) {
        eng.selectMode(com.pocketsdr.app.FreqStore.modeFromIndex(store.lastMode))
        eng.volume = store.lastVolume
    }

    private fun isRtlDevice(d: UsbDevice): Boolean =
        (d.vendorId == 0x0bda && (d.productId == 0x2832 || d.productId == 0x2838)) ||
        (d.vendorId == 0x0413 && d.productId == 0x6680)

    // ---------- Compose ----------
    @Composable
    private fun Host() {
        val eng = engine
        // 读 version 触发重组（瀑布双缓冲发布）
        @Suppress("UNUSED_EXPRESSION") wfVersion
        if (engineReady && eng != null) {
            AppShell(
                engine = eng,
                spectrum = eng.latestSpectrum,
                waterfall = waterfallBmp,
                status = status,
                signalDb = signalDb,
                stereoLock = stereoLock,
                rdsName = rdsName,
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
