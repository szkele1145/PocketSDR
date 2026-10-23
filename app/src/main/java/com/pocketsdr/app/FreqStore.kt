package com.pocketsdr.app

import android.content.Context
import org.json.JSONArray

/**
 * 频率记忆：最近使用频率 + 收藏频道，SharedPreferences JSON 持久化。
 */
class FreqStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("pocketsdr", Context.MODE_PRIVATE) }
    private val maxRecents = 8

    /**
     * 最近调谐过的频率（Hz，最近在前，去重），用于输入频率对话框的快捷跳台。
     *
     * 逐条容错：原来整体 try/catch，一条非法数据就让**整份历史消失**，
     * 而且紧接着 addRecent 会拿空列表回写，原始历史被永久抹掉。
     */
    fun getRecents(): MutableList<Long> {
        val arr = prefs.getString("recents", null) ?: return mutableListOf()
        return try {
            val ja = JSONArray(arr)
            val out = ArrayList<Long>(ja.length())
            for (i in 0 until ja.length()) {
                try {
                    out.add(ja.getLong(i))
                } catch (_: Exception) {
                    // 单条坏数据：跳过这一条，不影响其余
                }
            }
            out
        } catch (e: Exception) {
            SdrLog.e("STORE", "最近频率数据无法解析: ${e.message?.take(40)}")
            mutableListOf()
        }
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

    /**
     * 只用仿真源（不连真实硬件）。
     *
     * 真机到货后如果只想验证界面/录放/搜台逻辑，或者怀疑棒子干扰判断，
     * 打开它就能强制走仿真源——插着棒子也不切硬件。
     */
    var simOnly: Boolean
        get() = prefs.getBoolean("simOnly", false)
        set(v) { prefs.edit().putBoolean("simOnly", v).apply() }

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

    // ---------- 硬件设置 ----------
    // 这几个以前全是 remember 本地态：用户真机上把增益调到刚好的位置，
    // 退出应用再进来又回到默认值，等于每次都要重调一遍。

    /** 手动增益模式（true = 用手动 LNA 档位，false = 自动） */
    var hwManualGain: Boolean
        get() = prefs.getBoolean("hwManualGain", false)
        set(v) { prefs.edit().putBoolean("hwManualGain", v).apply() }

    /** LNA 增益档位索引（对应 SdrEngine.lnaGains 的下标） */
    var hwGainIndex: Int
        get() = prefs.getInt("hwGainIndex", 14)
        set(v) { prefs.edit().putInt("hwGainIndex", v).apply() }

    /** HF 直接采样模式：0 关 / 1 I-ADC / 2 Q-ADC */
    var hwDirectSampling: Int
        get() = prefs.getInt("hwDirectSampling", 0)
        set(v) { prefs.edit().putInt("hwDirectSampling", v).apply() }

    /** 偏移调谐（offset tuning，主要给 AM/短波用） */
    var hwOffsetTuning: Boolean
        get() = prefs.getBoolean("hwOffsetTuning", false)
        set(v) { prefs.edit().putBoolean("hwOffsetTuning", v).apply() }

    /** PPM 频率校准值 */
    var hwPpm: Int
        get() = prefs.getInt("hwPpm", 0)
        set(v) { prefs.edit().putInt("hwPpm", v).apply() }

    /**
     * 自动 dB 范围（纵轴随实测噪声底/峰值自动缩放）。
     *
     * 这个开关以前不存盘：用户关掉（想固定看 -100..0 的绝对刻度），
     * 退出应用再进来又变回自动，纵轴刻度会突然变样，看着像出了 bug。
     */
    var autoDbRange: Boolean
        get() = prefs.getBoolean("autoDbRange", true)
        set(v) { prefs.edit().putBoolean("autoDbRange", v).apply() }

    /**
     * 频谱视野跨度（Hz）。
     *
     * 存的是用户放大后的视野；比采样率大说明没放大过，用采样率即可。
     * 0 = 未缩放。
     */
    var viewSpan: Long
        get() = prefs.getLong("viewSpan", 0L)
        set(v) { prefs.edit().putLong("viewSpan", v).apply() }

    /**
     * 收藏列表排序方式：true = 按频率，false = 按加入顺序。
     *
     * 存下来是因为这是个使用习惯——习惯按频率找台的人，
     * 每次进来都要多点一下才能回到按频率排序。
     */
    var favSortByFreq: Boolean
        get() = prefs.getBoolean("favSortByFreq", true)
        set(v) { prefs.edit().putBoolean("favSortByFreq", v).apply() }

    /**
     * 搜台结果排序方式：true = 按信号强度降序，false = 按频率。
     *
     * 默认按强度：扫出 20 个台时，用户最想先听的是最强的那几个；
     * 按频率排更像一张频段地图，是另一种需求。记住用户的选择。
     */
    var scanSortByLevel: Boolean
        get() = prefs.getBoolean("scanSortByLevel", true)
        set(v) { prefs.edit().putBoolean("scanSortByLevel", v).apply() }

    /**
     * 步进档位，存的是**步长值（Hz）**而不是列表序号。
     *
     * 存值不存序号：档位表以后增删档（比如加个 9kHz 业余信道间隔），
     * 存序号的话老用户存的序号会指向另一个档位，存的步长值则永远是对的。
     *
     * 这是使用习惯：找 FM 台用 100kHz 一格一格挪很慢，
     * 而短波/对讲又需要 1kHz 细调。每次进来都要重新选很烦。
     */
    var tuneStepHz: Long
        get() = prefs.getLong("tuneStepHz", 0L)
        set(v) { prefs.edit().putLong("tuneStepHz", v).apply() }

    /**
     * 步进档位是否跟随接收模式自动调整。
     *
     * 默认 true：FM 广播台隔 100/200kHz，航空对讲和业余无线电是 25kHz 频道，
     * 短波（USB/LSB）则挤在 1kHz 级别——档位跟着模式走，用户永远不用记规则。
     * 一旦用户自己点了档位，就转成 false，尊重用户的显式选择。
     */
    var tuneStepAuto: Boolean
        get() = prefs.getBoolean("tuneStepAuto", true)
        set(v) { prefs.edit().putBoolean("tuneStepAuto", v).apply() }

    /**
     * 上次读取收藏时，是否有**部分条目损坏**（好的条目仍照常显示）。
     * UI 可据此提示用户，而不是让收藏「莫名其妙变少」。
     */
    @Volatile var favoritesLoadFailed: Boolean = false
        private set

    /**
     * 上次读取收藏时，是否**整份数据都无法解析**。
     *
     * 两种情况要分开：
     * - 部分条目损坏：好的条目照常显示、也允许写入（保留好的 + 新的）
     * - 整份解析不了：磁盘上的东西根本没被理解，这时写回去等于毁数据，
     *   必须拒绝写入。
     */
    @Volatile var favoritesUnreadable: Boolean = false
        private set

    /**
     * 收藏频道列表（JSON 数组：[{"n":"电台名","f":98100000,"m":0}, …]）
     *
     * 必须**逐条**容错。原来是整体 try/catch：任意一条缺字段就抛异常，
     * 整个 catch 掉返回空列表——用户收藏全部消失且毫无提示。
     * 更糟的是紧接着的 addFavorite/removeFavorite 会拿这个空列表回写磁盘，
     * 把真实收藏**永久覆盖掉**。
     * 现在只跳过坏的那一条，其余照常加载。
     */
    fun getFavorites(): MutableList<Ch> {
        val arr = prefs.getString("favorites", null) ?: return mutableListOf()
        favoritesLoadFailed = false
        favoritesUnreadable = false
        return try {
            val ja = JSONArray(arr)
            val out = ArrayList<Ch>(ja.length())
            var bad = 0
            for (i in 0 until ja.length()) {
                try {
                    val o = ja.getJSONObject(i)
                    out.add(Ch(o.getString("n"), o.getLong("f"), o.getInt("m")))
                } catch (_: Exception) {
                    bad++
                }
            }
            if (bad > 0) {
                favoritesLoadFailed = true
                SdrLog.w("STORE", "收藏里有 $bad 条数据损坏，已跳过（其余 ${out.size} 条正常）")
            }
            out
        } catch (e: Exception) {
            // 整份 JSON 都解析不了：不动磁盘，返回空并标记「不可读」。
            // 关键是不能让调用方把这个空列表写回去覆盖原文。
            favoritesLoadFailed = true
            favoritesUnreadable = true
            SdrLog.e("STORE", "收藏数据无法解析: ${e.message?.take(40)}")
            mutableListOf()
        }
    }

    fun saveFavorites(list: List<Ch>) {
        // 护栏：整份数据读不出来时，把「空列表」写回去 = 毁掉用户原始收藏。
        // 宁可这次不保存，也不能毁数据。
        if (favoritesUnreadable) {
            SdrLog.w("STORE", "收藏数据读不出来，跳过本次写入以免覆盖原始数据")
            return
        }
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
