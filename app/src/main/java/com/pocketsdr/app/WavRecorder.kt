package com.pocketsdr.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WAV 录音器：接收解调后的 16bit 立体声帧，追加写入 WAV 文件。
 * 写在 DSP 线程上调用，内部缓冲满 64KB 批量落盘。
 */
class WavRecorder(private val context: Context) {

    @Volatile var recording = false
        private set
    @Volatile var paused = false
        set(value) { field = value }
    @Volatile var filePath: String? = null
        private set

    /**
     * 录音期间发生的写入失败次数。
     *
     * 磁盘满、存储被其它应用占满、SD 卡拔出——这些都会让 write 抛异常。
     * 以前异常直接冒到 DSP 线程（写调用没有 try/catch），后果是
     * **DSP 线程死掉**：频谱冻结、音频停止，而界面还显示「录音中」。
     */
    @Volatile var writeFailures: Int = 0
        private set

    /** 最近一次失败原因（null = 没失败过）；用于在界面上说明为什么停了 */
    @Volatile var lastError: String? = null
        private set

    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L
    private var sampleRate = 32_000
    private val buf = java.io.ByteArrayOutputStream(1 shl 16)
    private val ioLock = Any()   // DSP 写 与 UI 停止/启动 的互斥

    /** 开始录音（新建带时间戳的文件） */
    fun start(sr: Int, freqHz: Long): Boolean = synchronized(ioLock) {
        if (recording) return true
        try {
            sampleRate = sr
            val dir = File(context.getExternalFilesDir(null), "recordings")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "sdr_${ts}_${freqHz / 1000}kHz.wav")
            raf = RandomAccessFile(f, "rw").also {
                it.setLength(0)
                it.write(ByteArray(44))  // WAV 头占位
            }
            dataBytes = 0
            buf.reset()
            filePath = f.absolutePath
            recording = true
            lastError = null
            writeFailures = 0
            true
        } catch (e: Exception) {
            // 失败原因必须留下来：以前只返回 false，用户只知道「没开始」，
            // 不知道是存储空间不足还是目录建不出来。
            lastError = "无法创建录音文件：${e.message?.takeIf { it.isNotBlank() } ?: "存储不可用"}"
            SdrLog.e("REC", lastError!!)
            recording = false
            false
        }
    }

    /** DSP 线程调用：追加一帧立体声 16bit。暂停时直接丢弃（WAV 是连续流，没有暂停语义）。 */
    fun write(frame: ShortArray, count: Int) {
        if (!recording || paused) return
        val chunk = ByteArray(count * 2)
        for (k in 0 until count) {
            val v = frame[k]
            chunk[k * 2] = (v.toInt() and 0xFF).toByte()
            chunk[k * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        synchronized(ioLock) {
            if (!recording) return
            buf.write(chunk)
            if (buf.size() >= 1 shl 16) flushLocked()
        }
    }

    private fun flush() = synchronized(ioLock) { flushLocked() }

    /**
     * 落盘。失败（磁盘满/存储被占）时**必须吞掉异常**：
     * 这个方法在 DSP 线程上被调用，异常冒出去会直接杀死 DSP 循环，
     * 现象是频谱冻结但界面毫无提示。
     */
    private fun flushLocked() {
        val r = raf ?: return
        val data = buf.toByteArray()
        if (data.isEmpty()) return
        try {
            r.write(data)
            dataBytes += data.size
            buf.reset()
        } catch (e: Exception) {
            writeFailures++
            lastError = "录音写入失败：${e.message?.takeIf { it.isNotBlank() } ?: "存储空间不足或被占用"}"
            SdrLog.e("REC", "$lastError（已写 ${dataBytes / 1024} KB）")
            // 停止录音：继续写只会一直失败，而且文件已经不完整了
            recording = false
            buf.reset()
        }
    }

    /** 停止并回填 WAV 头 */
    fun stop(): String? {
        synchronized(ioLock) {
            if (!recording) return null
            recording = false
            flushLocked()
            val r = raf ?: return filePath
            try {
                // 回填 RIFF 头
                r.seek(0)
                val header = makeHeader(dataBytes, sampleRate)
                r.write(header)
            } catch (e: Exception) {
                // 回填失败 = 文件头是坏的，播放器打不开。
                // 以前直接吞掉，用户看到「录音已保存」，打开却是坏文件。
                lastError = "录音文件头写入失败，文件可能无法播放：${e.message?.takeIf { it.isNotBlank() } ?: "存储错误"}"
                SdrLog.e("REC", lastError!!)
            }
            try { r.close() } catch (_: Exception) {}
            raf = null
            return filePath
        }
    }

    companion object {
        /** 标准 44 字节 WAV 头（16bit PCM 立体声） */
        fun makeHeader(dataLen: Long, sampleRate: Int): ByteArray {
            val byteRate = sampleRate * 4  // 16bit × 2ch
            val h = ByteArray(44)
            h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte()
            h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
            writeInt(h, 4, (36 + dataLen).toInt())
            h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte()
            h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
            h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte()
            h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
            writeInt(h, 16, 16)          // PCM chunk size
            writeShort(h, 20, 1)         // PCM format
            writeShort(h, 22, 2)         // stereo
            writeInt(h, 24, sampleRate)
            writeInt(h, 28, byteRate)
            writeShort(h, 32, 4)         // block align
            writeShort(h, 34, 16)        // bits per sample
            h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte()
            h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
            writeInt(h, 40, dataLen.toInt())
            return h
        }

        private fun writeInt(a: ByteArray, off: Int, v: Int) {
            a[off] = (v and 0xFF).toByte()
            a[off + 1] = ((v shr 8) and 0xFF).toByte()
            a[off + 2] = ((v shr 16) and 0xFF).toByte()
            a[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        private fun writeShort(a: ByteArray, off: Int, v: Int) {
            a[off] = (v and 0xFF).toByte()
            a[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }
}
