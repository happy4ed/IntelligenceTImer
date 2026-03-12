package com.intellitimer.vision.tracking

import android.content.Context

/**
 * ByteTracker 실시간 튜닝 파라미터 — Singleton.
 *
 * @Volatile 로 AI 스레드(Dispatchers.Default)와 UI 스레드 간 가시성 보장.
 */
object TrackerConfig {
    @Volatile var matchRadius: Float = 40f
    @Volatile var maxCoastFrames: Int = 15
    @Volatile var velocityEMA: Float = 0.8f
    @Volatile var deadZone: Float = 1.0f
    @Volatile var deviceRotation: Int = 0

    // ─── [지침 1] Zero-GC 클래스 필터 ─────────────────────────────────────────
    // BooleanArray: O(1) 조회, GC 없음. AI 스레드에서 매 프레임 8400회 접근.
    val classFilter = BooleanArray(80) { true }

    /** 기본 활성 클래스: 자율주행 관련 6종 */
    private val DEFAULT_ENABLED = intArrayOf(0, 1, 2, 3, 5, 7)  // person, bicycle, car, motorcycle, bus, truck

    // ─── 트래커 파라미터 영속 ─────────────────────────────────────────────────
    private const val PREFS_TRACKER  = "intellitimer_tracker"
    private const val K_MATCH_RADIUS = "matchRadius"
    private const val K_MAX_COAST    = "maxCoastFrames"
    private const val K_VEL_EMA      = "velocityEMA"
    private const val K_DEAD_ZONE    = "deadZone"

    fun loadTrackerParams(context: Context) {
        val p = context.getSharedPreferences(PREFS_TRACKER, Context.MODE_PRIVATE)
        matchRadius    = p.getFloat(K_MATCH_RADIUS, matchRadius)
        maxCoastFrames = p.getInt  (K_MAX_COAST,    maxCoastFrames)
        velocityEMA    = p.getFloat(K_VEL_EMA,      velocityEMA)
        deadZone       = p.getFloat(K_DEAD_ZONE,     deadZone)
    }

    fun saveTrackerParams(context: Context) {
        context.getSharedPreferences(PREFS_TRACKER, Context.MODE_PRIVATE).edit()
            .putFloat(K_MATCH_RADIUS, matchRadius)
            .putInt  (K_MAX_COAST,    maxCoastFrames)
            .putFloat(K_VEL_EMA,      velocityEMA)
            .putFloat(K_DEAD_ZONE,    deadZone)
            .apply()
    }

    // ─── 클래스 필터 영속 ────────────────────────────────────────────────────
    private const val PREFS_FILTER = "intellitimer_filter"
    private const val K_FILTER     = "classFilter"

    /**
     * SharedPreferences 에서 classFilter 복원.
     * 저장값 없으면 DEFAULT_ENABLED 6종만 활성화.
     */
    fun loadClassFilter(context: Context) {
        val p = context.getSharedPreferences(PREFS_FILTER, Context.MODE_PRIVATE)
        val saved = p.getString(K_FILTER, null)
        if (saved == null) {
            classFilter.fill(false)
            DEFAULT_ENABLED.forEach { classFilter[it] = true }
        } else {
            classFilter.fill(false)
            if (saved.isNotEmpty()) {
                saved.split(",").forEach { s ->
                    s.trim().toIntOrNull()
                        ?.takeIf { it in 0 until 80 }
                        ?.let { classFilter[it] = true }
                }
            }
        }
    }

    /** classFilter 현재 상태를 SharedPreferences 에 저장 */
    fun saveClassFilter(context: Context) {
        val csv = (0 until 80).filter { classFilter[it] }.joinToString(",")
        context.getSharedPreferences(PREFS_FILTER, Context.MODE_PRIVATE)
            .edit().putString(K_FILTER, csv).apply()
    }
}
