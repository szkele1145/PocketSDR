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
import com.pocketsdr.app.ui.SdrScreen

/**
 * 主 Activity：USB 设备监听/权限 + 引擎持有 + DSP→UI 状态桥。
 */
class MainActivity : ComponentActivity(), UiBridge {

    private var engine: SdrEngine? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager

    // Compose 状态
    private var spectrum by mutableStateOf(FloatArray(0))
    private var status by mutableStateOf("初始化…")
    private var signalDb by mutableStateOf(-120f)
    private var stereoLock by mutableStateOf(0f)
    private var engineReady by mutableStateOf(false)

    private val waterfall = ArrayDeque<FloatArray>()
    private val waterfallMaxRows = 100

    // ---------- UiBridge ----------
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

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // USB 权限广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        startEngine()
        setContent { Host() }
    }

    override fun onDestroy() {
        super.onDestroy()
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
    private fun switchToHardware(device: UsbDevice) {
        try {
            val conn = usbManager.openDevice(device)
            if (conn == null) {
                pushStatus("USB 打开失败（设备忙？）")
                return
            }
            val oldEngine = engine
            val src = RtlUsbSource(conn, device, 1_024_000L)
            engine = SdrEngine.withSource(src, this).also { it.start() }
            oldEngine?.close()
            pushStatus("RTL-SDR 已连接 · FC0013")
        } catch (e: Exception) {
            pushStatus("硬件初始化失败: ${e.message?.take(60)}")
            switchToSimulated()
        }
    }

    private fun switchToSimulated() {
        val old = engine
        val cur = old?.tunedFreq ?: 98_100_000
        engine = SdrEngine.simulated(this, cur - (cur % 100_000)).also { it.start() }
        old?.close()
        pushStatus("仿真模式")
    }

    private fun startEngine() {
        if (engine != null) return
        // 已插着设备且有权限？直接上硬件
        val dev = usbManager.deviceList.values.firstOrNull { isRtlDevice(it) }
        if (dev != null && usbManager.hasPermission(dev)) {
            switchToHardware(dev)
        } else {
            engine = SdrEngine.simulated(this, 98_000_000).also { it.start() }
            pushStatus("仿真模式 · 插入 RTL-SDR 自动连接")
        }
        engineReady = true
    }

    private fun isRtlDevice(d: UsbDevice): Boolean =
        (d.vendorId == 0x0bda && (d.productId == 0x2832 || d.productId == 0x2838)) ||
        (d.vendorId == 0x0413 && d.productId == 0x6680)

    // ---------- Compose ----------
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
