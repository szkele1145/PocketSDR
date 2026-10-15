package com.pocketsdr.app.ui

import android.graphics.Bitmap

/**
 * 瀑布图双缓冲 Bitmap。
 *
 * 性能设计：避免 Compose Canvas 逐像素 drawRect（每帧 10 万次调用）。
 * 写线程（DSP）pushRow 更新 back 页并整页拷贝到 front 页原子发布；
 * 绘制线程读 front 页——无锁、无撕裂（Bitmap 像素访问非线程安全，双缓冲根治）。
 *
 * 新行写在顶部（时间从上往下），push 时历史整体下移一行。
 */
class WaterfallBitmap(
    val bins: Int,
    val rows: Int,
    private val dbMin: Float = -90f,
    private val dbMax: Float = 0f
) {
    private var backBitmap = Bitmap.createBitmap(bins, rows, Bitmap.Config.ARGB_8888)
    private var frontBitmap = Bitmap.createBitmap(bins, rows, Bitmap.Config.ARGB_8888)

    /** 绘制用 Bitmap（front 页，发布后只读） */
    @Volatile var bitmap: Bitmap = frontBitmap
        private set

    private val rowPixels = IntArray(bins)
    private val shiftBuf = IntArray(bins * (rows - 1))

    /** LUT：dB → ARGB（与谱线一致的 SDR 配色） */
    private val lut = IntArray(256).also { arr ->
        val palette = intArrayOf(
            0xFF050B12.toInt(), 0xFF0D2B52.toInt(), 0xFF0A5FA8.toInt(),
            0xFF00A8E8.toInt(), 0xFF00E5FF.toInt(), 0xFF7DF3FF.toInt(),
            0xFFF6D55C.toInt(), 0xFFFF4040.toInt(), 0xFFFFF0F0.toInt()
        )
        for (i in 0 until 256) {
            val v = i / 255f
            val idx = when {
                v < 0.10f -> 0
                v < 0.22f -> 1
                v < 0.34f -> 2
                v < 0.46f -> 3
                v < 0.58f -> 4
                v < 0.70f -> 5
                v < 0.82f -> 6
                v < 0.93f -> 7
                else -> 8
            }
            arr[i] = palette[idx]
        }
    }

    /** 推一行 dB 数据（长度 ≥ bins）。DSP 线程调用，无锁。 */
    fun pushRow(db: FloatArray) {
        val range = dbMax - dbMin
        for (x in 0 until bins) {
            val v = ((db[x] - dbMin) / range).coerceIn(0f, 1f)
            rowPixels[x] = lut[(v * 255f).toInt()]
        }
        // back 页：历史下移 + 新行进 row0
        backBitmap.getPixels(shiftBuf, 0, bins, 0, 0, bins, rows - 1)
        backBitmap.setPixels(shiftBuf, 0, bins, 0, 1, bins, rows - 1)
        backBitmap.setPixels(rowPixels, 0, bins, 0, 0, bins, 1)
        // 发布：交换 front/back（旧 front 变 back 供下次写入，back 变 front 供绘制）
        val newFront = backBitmap
        backBitmap = frontBitmap  // 此时 back = 旧 front（含完整历史，下次在其上继续滚动）
        frontBitmap = newFront
        bitmap = newFront
    }

    /** 清空（初始化为噪底色） */
    fun clear() {
        val dark = lut[0]
        java.util.Arrays.fill(rowPixels, dark)
        for (y in 0 until rows) {
            backBitmap.setPixels(rowPixels, 0, bins, 0, y, bins, 1)
            frontBitmap.setPixels(rowPixels, 0, bins, 0, y, bins, 1)
        }
    }
}
