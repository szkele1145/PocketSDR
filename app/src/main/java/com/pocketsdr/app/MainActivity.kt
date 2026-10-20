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
    /** RDS 是否已位同步（还没解出台名时，用于告诉用户"正在等"而不是"坏了"） */
    private var rdsLocked by mutableStateOf(false)
    private var engineReady by mutableStateOf(false)
    /**
     * 插着 USB 设备但还没连上（授权未给 / VID 不在白名单）。
     * 主界面据此显示提示条——「插了没反应」时用户至少知道下一步去哪。
     */
    private var usbPendingText by mutableStateOf<String?>(null)
    /** 最近一次硬件连接失败的原因；不为空时优先占据主界面状态行，直到下次连接尝试 */
    private var hwErrorText by mutableStateOf<String?>(null)
    /**
     * 最近一次硬件连接失败的**完整**原因（不截断）。
     *
     * 横幅上的文字要短，只能截断；但排查真机问题恰恰需要那被砍掉的后半句
     * （比如「I2C 通信无响应——多半是供电不足或 OTG 线接触不良」）。
     * 完整版存下来，在 USB 诊断页整段显示。
     */
    private var hwErrorFull by mutableStateOf<String?>(null)

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
                rdsLocked = engine?.rds?.dataLock ?: false
                // 未连接提示跟着刷新：权限可能在后台被授予/撤销（用户到设置页操作）
                refreshUsbPending()
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
                    // 插 U 盘/键鼠也会收到 ATTACHED 广播，非 SDR 设备不弹授权框。
                    // 白名单外的设备（非官方棒子）可在设置页用「USB 诊断」强制连接。
                    if (d != null && isRtlDevice(d)) maybeRequestPermission(d)
                    refreshUsbPending()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && d != null) switchToHardware(d)
                    else if (!granted) {
                        // 用户点了「拒绝」，或系统授权弹窗超时/被跳过。
                        // 以前这里什么都不做，用户看到主界面毫无反应，
                        // 只能猜是不是设备坏了——必须说清楚发生了什么、下一步去哪。
                        hwErrorText = "⚠ 未获得 USB 授权，读不到设备（点这里到 USB 诊断手动连接）"
                    }
                    refreshUsbPending()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    pushStatus("设备已拔出，回到仿真模式")
                    switchToSimulated()
                    refreshUsbPending()
                }
            }
        }
    }

    /**
     * 更新「插着设备但未连接」提示。
     *
     * 已连上硬件时清空；仿真模式下检查系统 USB 列表：有白名单设备等授权 → 提示授权；
     * 有 bulk-in 端点但白名单外的设备 → 提示强制尝试。这层提示的存在意义：
     * 用户插了棒子看到主界面毫无反应时，能立刻知道下一步去哪，而不是干等。
     */
    private fun refreshUsbPending() {
        if (engine?.isRealSource == true) { hwErrorText = null; usbPendingText = null; return }
        // 硬件连接失败的具体原因比「待授权」更值得留在屏幕上——它才是用户排查的线索。
        // 只有真机连上（上面那行）或下一次连接尝试才会清掉它。
        if (hwErrorText != null) { usbPendingText = hwErrorText; return }
        val entries = try { UsbDiag.snapshot(usbManager) } catch (_: Exception) { emptyList() }
        usbPendingText = when {
            entries.isEmpty() -> null
            entries.any { it.known && !it.granted } -> "USB 设备待授权：看系统弹窗，或到 设置 → USB 诊断 手动连接"
            entries.any { it.bulkIn } -> "检测到 USB 设备（非白名单）：到 设置 → USB 诊断 强制尝试"
            else -> null
        }
    }

    /** 请求授权；已授权则直接上硬件。调用方负责决定要不要对白名单外的设备也开放 */
    internal fun maybeRequestPermission(device: UsbDevice) {
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
        // 有些 ROM 根本弹不出授权框（或者被用户忽略），那样就不会有
        // ACTION_USB_PERMISSION 广播回来——界面会毫无反应，像坏了。
        // 延时检查一次，没拿到授权就明确告诉用户下一步去哪。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (engine?.isRealSource != true && !usbManager.hasPermission(device)) {
                hwErrorText = "⚠ 没等到 USB 授权弹窗（点这里到 USB 诊断手动连接）"
                refreshUsbPending()
            }
        }, 2500)
    }

    /**
     * 白名单外设备的强制连接入口（USB 诊断面板用）。
     * 非官方棒子的 VID/PID 千奇百怪，只要底层真是 RTL2832+FC0013 就该给机会连上。
     * 未授权时先走系统授权弹窗（结果仍从 ACTION_USB_PERMISSION 广播回来）。
     */
    internal fun forceConnect(device: UsbDevice) {
        SdrLog.i("USB", "强制尝试 vid=%04X pid=%04X".format(device.vendorId, device.productId))
        maybeRequestPermission(device)
    }

    /** 切到真实硬件（USB 权限已授予） */
    private fun switchToHardware(device: UsbDevice, centerHint: Long = 98_000_000, tuneHint: Long = 98_100_000) {
        val conn = try {
            usbManager.openDevice(device)
        } catch (e: Exception) {
            // 打开异常也要留住：以前只 pushStatus 一下，状态行几秒就被别的消息顶掉了，
            // 用户只看到「插了没反应」，完全不知道发生了什么。
            val reason = "USB 打开异常：${e.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
            hwErrorFull = reason
            hwErrorText = "⚠ $reason（点这里到 USB 诊断）"
            pushStatus(reason)
            refreshUsbPending()
            null
        }
        if (conn == null) {
            val reason = "USB 打开失败（设备被占用？）——拔插一次，或关掉其它 SDR 应用后重试"
            hwErrorFull = reason
            hwErrorText = "⚠ $reason（点这里到 USB 诊断）"
            pushStatus(reason)
            refreshUsbPending()
            return
        }
        var src: RtlUsbSource? = null
        try {
            // 先停旧引擎（释放 AudioTrack/停止推 UiBridge），再起新的——避免双 DSP 线程并发
            engine?.let { old ->
                engine = null
                engineReady = false
                old.close()
            }
            val store = com.pocketsdr.app.FreqStore(this)
            src = RtlUsbSource(conn, device, 1_024_000L)
            engine = SdrEngine.withSource(src, this).also {
                it.start()
                it.tuneTo(tuneHint)
                restoreCommon(it, store)
                // 只有「从没亲手调过静噪」的首次真机连接才自动全开：
                // -40dB 是仿真源的电平尺度，真机信号若低于它就会「连上了却一片死寂」，
                // 看着像设备坏了。先让用户听到声音，之后他想怎么调都行。
                // 调过一次之后就以存盘值为准（restoreCommon 已经恢复了），否则用户
                // 辛苦调好的门限每次重连都会被重置。
                // 用 -80 而不是 -100：滑条范围是 -80..-10，设成 -100 会让滑条
                // 停在最左端却显示 -100，用户一碰又跳回 -80，看着像坏了。
                if (!store.squelchTuned) it.squelchDb = -80f
                engineReady = true
            }
            pushStatus(
                if (!store.squelchTuned) "RTL-SDR 已连接 · FC0013 · 静噪已全开（有声后可上调）"
                else "RTL-SDR 已连接 · FC0013 · 静噪 ${store.lastSquelch.toInt()}dB"
            )
            verifyHardwareHealth()
        } catch (e: Exception) {
            // 关键：初始化失败必须归还 USB 连接，否则后续重试全部「设备忙」，只能重启应用
            pushStatus("硬件初始化失败: ${e.message?.take(60)}")
            try { src?.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
            // 失败原因必须留住且看得见：switchToSimulated 会把状态行改成「仿真模式」，
            // 用户只看到一秒钟的报错就没了，真机排查时等于没有线索。
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            hwErrorFull = reason
            hwErrorText = "⚠ 硬件连接失败：${reason.take(70)}（点这里到 USB 诊断）"
            switchToSimulated()
            refreshUsbPending()
            return
        }
        hwErrorText = null
        hwErrorFull = null
        refreshUsbPending()
    }

    /**
     * 真机连上后等一下再下结论：此刻 FFT 还在攒第一批数据，
     * 立刻读噪声底多半是初值 -85dB，没有意义。
     *
     * 判断依据是噪声底落在什么区间——这是「设备真的在出数据」最直接的证据：
     *   - 太低（< -110dB）：ADC 几乎全零，多半是数据没流起来或增益设成了 0
     *   - 太高（> -40dB）：输入端过载，LNA 拉太高了，真信号会被压掉
     * 落在中间就说明链路正常，用户可以直接去搜台。
     */
    private fun verifyHardwareHealth() {
        uiHandler.postDelayed({
            val eng = engine ?: return@postDelayed
            if (!eng.isRealSource) return@postDelayed
            val floor = eng.noiseFloorDb
            val peak = eng.spectrumPeakDb
            val blocks = eng.blockCount
            if (blocks <= 0) {
                // 连上了但一个数据块都没收到：这是比"信号弱"严重得多的问题
                hwErrorText = "⚠ 已连接但收不到数据（点这里到 USB 诊断）"
                refreshUsbPending()
                return@postDelayed
            }
            hwErrorText = when {
                floor < -110f ->
                    "⚠ 数据近全零（噪声底${floor.toInt()}dB）：查增益是否过低（点这里到 USB 诊断）"
                floor > -40f ->
                    "⚠ 输入端过载（噪声底${floor.toInt()}dB）：把 LNA 调低（点这里到 USB 诊断）"
                else -> null
            }
            if (hwErrorText == null) {
                pushStatus(
                    "RTL-SDR 工作正常 · 噪声底${floor.toInt()}dB 峰值${peak.toInt()}dB " +
                        "· 可点「搜台」找台"
                )
            }
            refreshUsbPending()
        }, 2_500)
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
        refreshUsbPending()
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
        refreshUsbPending()
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
        eng.peakHoldEnabled = store.peakHold
        eng.autoDbRange = store.autoDbRange
        // 静噪要跟着恢复：以前不存盘，真机连接时被强制拉到 -100，用户调好的门限
        // 一重启就没了，底噪全放出来。现在以存盘值为准（见下面首次连接的例外）。
        eng.squelchDb = store.lastSquelch
        // 硬件设置同样要恢复：手动增益/增益档位/直采/偏移调谐/PPM 以前全是
        // 设置页里的本地态，退出应用就没了，用户每次都得重调一遍。
        if (eng.isRealSource) {
            eng.setHardwareGainMode(store.hwManualGain)
            if (store.hwManualGain) {
                val idx = store.hwGainIndex.coerceIn(0, eng.lnaGains.size - 1)
                eng.setHardwareLnaGain(eng.lnaGains[idx])
            }
            if (store.hwDirectSampling != 0) eng.setHardwareDirectSampling(store.hwDirectSampling)
            if (store.hwOffsetTuning) eng.setHardwareOffsetTuning(true)
            if (store.hwPpm != 0) eng.setHardwarePpm(store.hwPpm)
        }
    }

    /** 白名单判定统一收口到 UsbDiag（与 device_filter.xml 对齐） */
    private fun isRtlDevice(d: UsbDevice): Boolean =
        com.pocketsdr.app.UsbDiag.isKnown(d.vendorId, d.productId)

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
                peak = eng.latestPeak,
                status = status,
                signalDb = signalDb,
                stereoLock = stereoLock,
                rdsName = rdsName,
                rdsLocked = rdsLocked,
                usbPending = usbPendingText,
                usbErrorFull = hwErrorFull,
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
