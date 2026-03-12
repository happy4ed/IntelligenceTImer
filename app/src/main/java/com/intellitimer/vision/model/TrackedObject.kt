package com.intellitimer.vision.model

import android.graphics.PointF

/**
 * ByteTrack 스타일 트래킹 결과 객체.
 *
 * Rule.md Zero-GC 정책 준수:
 * - trackId, velocityX, velocityY 는 var — 매 프레임 덮어쓰기.
 * - detection 은 val 이지만 내부 필드(classId, confidence, rect)가 var/mutable.
 * - predictedTrajectory 는 MutableList<PointF> 로 단 한 번 할당 후 clear()+add() 로 재사용.
 *   단, PointF 도 풀(Pool)에서 가져와 재사용 — 새 PointF() 생성 금지.
 * - futurePoints: Array<PointF> 는 생성 시 1회만 할당 — 매 프레임 재사용.
 *
 * Phase 5 Prediction:
 * - velocityX/Y = 최근 N프레임 중심점 이동 평균.
 * - predictedTrajectory = 현재 중심점 + vx/vy * t (t = 1..N_STEPS).
 * - trueVx/trueVy = Ego-Motion 제거 후 순수 객체 속도.
 * - futurePoints = 마찰력 0.9 로 감쇠되는 20프레임 미래 예측 좌표 (AI 416 공간).
 */
data class TrackedObject(
    var trackId: Int,
    val detection: Detection,
    var velocityX: Float,
    var velocityY: Float,
    val predictedTrajectory: MutableList<PointF>,  // Pool에서 재사용 — 새 PointF() 금지
    var invisibleCount: Int = 0,                   // 연속 미탐지 프레임 수 (Coasting 제어)
    var trueVx: Float = 0f,                        // Ego-Motion 보정 후 순수 X 속도
    var trueVy: Float = 0f,                        // Ego-Motion 보정 후 순수 Y 속도
    var isMoving: Boolean = false,                 // 동적 객체 여부 (hypot >= 0.5f)
    val futurePoints: Array<PointF> = Array(20) { PointF() }  // 20프레임 예측, 1회만 할당
)
