package com.intellitimer.vision.ui

/** 매 프레임 파이프라인 계측값 — HudView 로 전달. */
data class PipelineStats(
    val delegateName: String = "init",   // GPU / CPU / init
    val fps: Float          = 0f,
    val preprocessMs: Long  = 0L,
    val inferenceMs: Long   = 0L,
    val nmsMs: Long         = 0L,
    val detectionCount: Int = 0
)
