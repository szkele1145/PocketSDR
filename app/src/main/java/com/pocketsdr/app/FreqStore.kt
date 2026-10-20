package com.pocketsdr.app

import android.content.Context
import org.json.JSONArray

/**
 * 频率记忆：最近使用频率 + 收藏频道，SharedPreferences JSON 持久化。
 */
class FreqStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("pocketsdr", Context.MODE_PRIVATE) }
    private val maxRecents = 8

    /** 最近调谐过的频率（Hz，最近在前，去重），用于输入频率对话框的快捷跳台 */
    fun getRecents(): MutableList<Long> {
        val arr = prefs.getString("recents", null) ?: return mutableListOf()
        return try {
            val ja = JSONArray(arr)
            MutableList(ja.length()) { i -> ja.getLong(i) }
        } catch (_: Exception) { mutableListOf() }
    }

    fun addRecent(freqHz: Long) {
        val l = getRecents()
        l.removeAll { it == freqHz }
        l.add(0, freqHz)
        if (l.size > maxRecents) l.removeAt(l.size - 1)
        val ja = JSONArray()
        l.forEach { ja.put(it) }
        prefs.edit().putString("recents", ja.toString()).apply()
    }

    /** 上次收听状态 */
    var lastFreq: Long
        get() = prefs.getLong("lastFreq", 98_100_000)
        set(v) { prefs.edit().putLong("lastFreq", v).apply() }

    var lastCenter: Long
        get() = prefs.getLong("lastCenter", 98_000_000)
        set(v) { prefs.edit().putLong("lastCenter", v).apply() }

    var lastMode: Int
        get() = prefs.getInt("lastMode", 0)
        set(v) { prefs.edit().putInt("lastMode", v).apply() }

    var lastVolume: Float
        get() = prefs.getFloat("lastVolume", 0.8f)
        set(v) { prefs.edit().putFloat("lastVolume", v).apply() }

    /** 是否已看过新手引导 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) { prefs.edit().putBoolean("onboardingDone", v).apply() }

    /** 频谱峰值保持开关（黄线） */
    var peakHold: Boolean
        get() = prefs.getBoolean("peakHold", false)
        set(v) { prefs.edit().putBoolean("peakHold", v).apply() }

    /** 瀑布速度档位：0 慢 / 1 中 / 2 快 */
    var waterfallSpeed: Int
        get() = prefs.getInt("waterfallSpeed", 1)
        set(v) { prefs.edit().putInt("waterfallSpeed", v).apply() }

    /**
     * 静噪门限（dBFS）。
     *
     * 以前这个值不存盘：真机每次连接都被强制拉到 -100（全开），用户辛苦调好的
     * 静噪一重启就没了，底噪全放出来吵得慌。现在记住用户的选择。
     */
    var lastSquelch: Float
        get() = prefs.getFloat("lastSquelch", -40f)
        set(v) { prefs.edit().putFloat("lastSquelch", v).apply() }

    /**
     * 用户是否亲手调过静噪。
     *
     * 只有「从没调过」的第一次真机连接才自动全开（-100）：那是为了确保用户
     * 一插上就能听到声音，而不是对着静默界面以为设备坏了。调过之后就以用户为准。
     */
    var squelchTuned: Boolean
        get() = prefs.getBoolean("squelchTuned", false)
        set(v) { prefs.edit().putBoolean("squelchTuned", v).apply() }

    /** 收藏频道列表（JSON 数组：[{"n":"电台名","f":98100000,"m":0}, …]） */
    fun getFavorites(): MutableList<Ch> {
        val arr = prefs.getString("favorites", null) ?: return mutableListOf()
        return try {
            val ja = JSONArray(arr)
            MutableList(ja.length()) { i ->
                val o = ja.getJSONObject(i)
                Ch(o.getString("n"), o.getLong("f"), o.getInt("m"))
            }
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveFavorites(list: List<Ch>) {
        val ja = JSONArray()
        list.forEach { c ->
            val o = org.json.JSONObject()
            o.put("n", c.name)
            o.put("f", c.freq)
            o.put("m", c.mode)
            ja.put(o)
        }
        prefs.edit().putString("favorites", ja.toString()).apply()
    }

    fun addFavorite(c: Ch) {
        val l = getFavorites()
        if (l.none { it.freq == c.freq }) {
            l.add(c)
            saveFavorites(l)
        }
    }

    fun removeFavorite(freq: Long) {
        val l = getFavorites()
        l.removeAll { it.freq == freq }
        saveFavorites(l)
    }

    /** 重命名收藏 */
    fun renameFavorite(freq: Long, newName: String) {
        val l = getFavorites()
        val idx = l.indexOfFirst { it.freq == freq }
        if (idx >= 0) {
            l[idx] = l[idx].copy(name = newName.take(20))
            saveFavorites(l)
        }
    }

    /** 频道条目 */
    data class Ch(val name: String, val freq: Long, val mode: Int)

    companion object {
        /** 模式序号 ↔ 引擎模式 */
        fun modeIndex(m: SdrEngine.Mode): Int = when (m) {
            SdrEngine.Mode.WFM -> 0
            SdrEngine.Mode.NFM -> 1
            SdrEngine.Mode.AM -> 2
            SdrEngine.Mode.USB -> 3
            SdrEngine.Mode.LSB -> 4
        }

        fun modeFromIndex(i: Int): SdrEngine.Mode = when (i) {
            0 -> SdrEngine.Mode.WFM
            1 -> SdrEngine.Mode.NFM
            2 -> SdrEngine.Mode.AM
            3 -> SdrEngine.Mode.USB
            else -> SdrEngine.Mode.LSB
        }
    }
}
