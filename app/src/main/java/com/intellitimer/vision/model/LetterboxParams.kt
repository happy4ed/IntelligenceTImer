package com.intellitimer.vision.model

/**
 * Letterbox 전처리 파라미터 — Phase 4 Inverse Scaling 에 그대로 재사용.
 *
 * Rule.md Zero-GC: 단 한 번 할당 후 update() 로 값만 갱신.
 *
 * @param scale      회전 보정 후 실효 이미지 → 640 정방형 스케일 비율
 * @param padLeft    가로 방향 좌측 패딩(px, 640 좌표계)
 * @param padTop     세로 방향 상단 패딩(px, 640 좌표계)
 * @param effCamW    회전 보정 후 실효 카메라 너비 (px) — AI→화면 역산용
 * @param effCamH    회전 보정 후 실효 카메라 높이 (px) — AI→화면 역산용
 */
data class LetterboxParams(
    var scale: Float = 1f,
    var padLeft: Float = 0f,
    var padTop: Float = 0f,
    var effCamW: Int = 640,
    var effCamH: Int = 640
) {
    fun update(scale: Float, padLeft: Float, padTop: Float, effCamW: Int, effCamH: Int) {
        this.scale   = scale
        this.padLeft = padLeft
        this.padTop  = padTop
        this.effCamW = effCamW
        this.effCamH = effCamH
    }
}
