package com.pocketsdr.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 环形日志：DSP/USB 驱动层的关键事件记录，UI 可查看（真机联调用）。
 * 线程安全，容量 512 条，溢出丢最旧。
 */
object SdrLog {
    private val entries = ConcurrentLinkedDeque<String>()
    private const val MAX = 512
    @Volatile var enabled = true

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        if (!enabled) return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(Date())
        entries.addLast("[$ts] $level/$tag: $msg")
        while (entries.size > MAX) entries.pollFirst()
    }

    /** 全部日志（新→旧） */
    fun dump(): List<String> = entries.toList().reversed()

    fun clear() = entries.clear()

    /** 落盘（分享/排查用） */
    fun save(context: Context): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val f = File(dir, "sdrlog_${System.currentTimeMillis()}.txt")
            f.writeText(entries.joinToString("\n"))
            f.absolutePath
        } catch (_: Exception) { null }
    }
}
