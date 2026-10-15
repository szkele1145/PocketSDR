package com.pocketsdr.app.ui

import android.graphics.Bitmap

/**
 * 瀑布图 Bitmap 环形缓冲。
 *
 * 性能设计：避免 Compose Canvas 逐像素 drawRect（每帧 10 万次调用）。
 * 行数据写入 Bitmap 像素，绘制时一次 drawImage 全屏贴图。
 *
 * 新行写在顶部（时间从上往下），push 时整体下移一行。
 * 1024 bin × 200 行，一次行平移拷贝 ~200KB，15 行/秒毫无压力。
 */
class WaterfallBitmap(
    val bins: Int,
    val rows: Int,
    private val dbMin: Float = -90f,
    private val dbMax: Float = 0f
) {
    val bitmap: Bitmap = Bitmap.createBitmap(bins, rows, Bitmap.Config.ARGB_8888)

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

    /** 推一行 dB 数据（长度 ≥ bins） */
    @Synchronized
    fun pushRow(db: FloatArray) {
        val range = dbMax - dbMin
        for (x in 0 until bins) {
            val v = ((db[x] - dbMin) / range).coerceIn(0f, 1f)
            rowPixels[x] = lut[(v * 255f).toInt()]
        }
        // 下移一行：row 0..rows-2 → 1..rows-1，新行写入 row 0（最新在上）
        bitmap.getPixels(shiftBuf, 0, bins, 0, 0, bins, rows - 1)
        bitmap.setPixels(shiftBuf, 0, bins, 0, 1, bins, rows - 1)
        bitmap.setPixels(rowPixels, 0, bins, 0, 0, bins, 1)
    }

    /** 清空（初始化为噪底色） */
    @Synchronized
    fun clear() {
        val dark = lut[0]
        java.util.Arrays.fill(rowPixels, dark)
        for (y in 0 until rows) {
            bitmap.setPixels(rowPixels, 0, bins, 0, y, bins, 1)
        }
    }
}
