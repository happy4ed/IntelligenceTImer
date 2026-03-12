package com.intellitimer.vision.camera

import androidx.camera.core.ImageProxy
import com.intellitimer.vision.model.TrackedObject

/**
 * 파이프라인 Phase 2~5 실행 인터페이스.
 *
 * FPS 최적화를 위해 두 단계로 분리:
 *  1. copyFrame()   — [analysisExecutor] 스레드에서 동기 호출. imageProxy 데이터 복사만 수행.
 *                     반환 즉시 imageProxy.close() 가 CameraManager 에서 호출됨.
 *  2. runPipeline() — Dispatchers.Default 코루틴에서 호출. 전처리 → 추론 → 트래킹 수행.
 *
 * 이 분리 덕분에 카메라는 추론 완료를 기다리지 않고 다음 프레임 준비 가능 (30fps 유지).
 */
interface FrameProcessor {

    /**
     * ImageProxy 의 픽셀 데이터를 내부 버퍼에 복사하고 메타데이터를 저장.
     * [analysisExecutor] 단일 스레드에서 호출 — 완료 즉시 imageProxy.close() 됨.
     *
     * @return false 이면 해당 프레임 스킵 (해상도 0 등 비정상 프레임)
     */
    fun copyFrame(imageProxy: ImageProxy): Boolean

    /**
     * copyFrame() 으로 복사된 데이터로 전처리 → 추론 → 후처리 → 트래킹 수행.
     * Dispatchers.Default 코루틴에서 호출.
     *
     * @return 화면 좌표계로 변환된 [TrackedObject] 목록
     */
    suspend fun runPipeline(): List<TrackedObject>
}
