package com.intellitimer.vision.model

import android.graphics.RectF

/**
 * YOLOv10n 추론 결과 단일 탐지 객체.
 *
 * Rule.md Zero-GC 정책 준수:
 * - classId, confidence 는 var 로 선언하여 매 프레임 객체 재생성 없이 값만 덮어씀.
 * - rect 는 val RectF 로 단 한 번 할당되며, set(l, t, r, b) 로 좌표만 갱신.
 *
 * 좌표계: 화면 픽셀(px) 기준 — Inverse Letterbox Scaling 완료 후.
 */
data class Detection(
    var classId: Int,
    var confidence: Float,
    val rect: RectF       // 재사용 가능 RectF; Phase 4 역산 후 set() 으로 갱신
)
