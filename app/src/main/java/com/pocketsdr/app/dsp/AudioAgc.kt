package com.pocketsdr.app.dsp

/**
 * 慢速音频 AGC（SSB/AM 接收用）。
 *
 * 峰值检测 + 平滑增益：
 *  - 快启动（attack ~10ms）：信号突然变强立即压增益，防爆音
 *  - 慢释放（release ~500ms）：信号走弱缓慢提增益，听感平稳
 *  - 目标峰值 0.3（留余量给瞬时峰）
 */
class AudioAgc(
    private val sampleRate: Float,
    private val targetPeak: Float = 0.3f
) {
    private var gain = 1f
    private var peakEnv = 0f

    // 一阶包络滤波系数（attack/release 不同系数）
    private val attackA = exp2(-1f / (0.010f * sampleRate))   // 10ms
    private val releaseA = exp2(-1f / (0.5f * sampleRate))    // 500ms

    /** 处理一个音频样本，返回增益后样本 */
    fun process(x: Float): Float {
        val ax = if (x < 0f) -x else x
        // 包络：上升快 / 下降慢
        peakEnv = if (ax > peakEnv) attackA * peakEnv + (1f - attackA) * ax
                 else releaseA * peakEnv + (1f - releaseA) * ax
        // 增益：目标峰值 / 当前包络（限幅 1..30）
        if (peakEnv > 1e-5f) {
            val want = targetPeak / peakEnv
            // 增益平滑（慢跟随防抽搐）
            gain += (want.coerceIn(0.05f, 30f) - gain) * 0.002f
        }
        return (x * gain).coerceIn(-1f, 1f)
    }

    fun reset() { gain = 1f; peakEnv = 0f }

    private fun exp2(x: Float): Float = java.lang.Math.exp(x.toDouble()).toFloat()
}
