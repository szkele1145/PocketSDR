package com.pocketsdr.app

import android.content.Context
import org.json.JSONArray

/**
 * 频率记忆：最近使用频率 + 收藏频道，SharedPreferences JSON 持久化。
 */
class FreqStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("pocketsdr", Context.MODE_PRIVATE) }

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

    /** 频道条目 */
    data class Ch(val name: String, val freq: Long, val mode: Int)

    companion object {
        /** 模式序号 ↔ 引擎模式 */
        fun modeIndex(m: SdrEngine.Mode): Int = when (m) {
            SdrEngine.Mode.WFM -> 0
            SdrEngine.Mode.NFM -> 1
            SdrEngine.Mode.AM -> 2
        }

        fun modeFromIndex(i: Int): SdrEngine.Mode = when (i) {
            0 -> SdrEngine.Mode.WFM
            1 -> SdrEngine.Mode.NFM
            else -> SdrEngine.Mode.AM
        }
    }
}
