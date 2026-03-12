package com.intellitimer.vision.model

import android.content.Context

/**
 * 앱 전역 설정 싱글톤. SharedPreferences 에 영속.
 *
 * @Volatile 필드 — 추론 스레드(Dispatchers.Default)에서 읽고
 *                  UI 스레드에서 쓰므로 가시성 보장 필수.
 */
object AppSettings {

    private const val PREFS  = "intellitimer_settings"
    private const val K_CONF  = "confidenceThreshold"
    private const val K_CFRM  = "confirmFrames"
    private const val K_MDET  = "maxDetections"
    private const val K_MMIS  = "maxMissing"

    // ─── 설정 값 (기본값) ─────────────────────────────────────────────────
    /** 탐지 신뢰도 임계값 (0.15 – 0.85). 낮을수록 민감. */
    @Volatile var confidenceThreshold: Float = 0.40f

    /** 화면에 표시되기 위한 연속 확인 프레임 수 (1 – 5). 높을수록 오탐 감소. */
    @Volatile var confirmFrames: Int = 2

    /** 최대 동시 탐지 객체 수 (1 – 20). */
    @Volatile var maxDetections: Int = 10

    /** 트랙 소실 허용 프레임 수 (1 – 15). 낮을수록 빠르게 사라짐. */
    @Volatile var maxMissing: Int = 5

    // ─── 영속 저장/복원 ───────────────────────────────────────────────────

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        confidenceThreshold = p.getFloat(K_CONF, confidenceThreshold)
        confirmFrames       = p.getInt  (K_CFRM, confirmFrames)
        maxDetections       = p.getInt  (K_MDET, maxDetections)
        maxMissing          = p.getInt  (K_MMIS, maxMissing)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat(K_CONF, confidenceThreshold)
            putInt  (K_CFRM, confirmFrames)
            putInt  (K_MDET, maxDetections)
            putInt  (K_MMIS, maxMissing)
            apply()
        }
    }
}
