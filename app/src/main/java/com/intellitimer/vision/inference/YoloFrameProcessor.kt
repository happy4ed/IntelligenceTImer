package com.intellitimer.vision.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.camera.core.ImageProxy
import com.intellitimer.vision.camera.FrameProcessor
import com.intellitimer.vision.model.TrackedObject
import com.intellitimer.vision.tracking.ByteTracker
import com.intellitimer.vision.tracking.GyroEgoMotion
import com.intellitimer.vision.tracking.TrackerConfig
import com.intellitimer.vision.ui.LogCollector
import com.intellitimer.vision.ui.OverlayView
import com.intellitimer.vision.ui.PipelineStats
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "YoloFrameProcessor"

/**
 * 전체 비전 파이프라인 오케스트레이터 (Phase 2 ~ 5).
 *
 *  Phase 2 — Letterbox 전처리     (ImagePreprocessor)
 *  Phase 3 — YOLO11n 추론         (YoloInferenceEngine: GPU→CPU)
 *  Phase 4 — Confidence 필터+역산 (YoloPostProcessor)
 *  Phase 5 — ByteTrack + 궤적     (ByteTracker + Ego-Motion 보정)
 *
 * FPS 최적화:
 *  copyFrame()   — analysisExecutor 에서 호출. 픽셀 복사만 (~1-5ms). 완료 즉시 imageProxy.close().
 *  runPipeline() — Dispatchers.Default 코루틴. 전처리+추론+트래킹 전체 실행.
 */
class YoloFrameProcessor(
    private val context: Context,
    private val overlayView: View? = null
) : FrameProcessor {

    // ─── Stats 콜백 ──────────────────────────────────────────────────────────
    var onStats: ((PipelineStats) -> Unit)? = null

    // ─── FPS 카운터 ───────────────────────────────────────────────────────────
    private var frameCount  = 0
    private var fpsWindowMs = 0L
    @Volatile private var currentFps = 0f

    // ─── copyFrame() 에서 저장한 프레임 메타데이터 (로깅용) ───────────────────
    @Volatile private var lastFrameW   = 0
    @Volatile private var lastFrameH   = 0
    @Volatile private var lastRotation = 0

    // ─── GPU thread dispatcher (TFLite GPU Delegate requires single fixed thread) ──
    private val gpuThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tflite-gpu").also { it.isDaemon = true }
    }.asCoroutineDispatcher()

    // ─── Sub-components ──────────────────────────────────────────────────────

    private val preprocessor = ImagePreprocessor()

    private val inferenceEngineLazy: Lazy<YoloInferenceEngine> =
        lazy {
            YoloInferenceEngine(context).also {
                LogCollector.i(TAG, "TFLite 초기화 완료 — delegate: ${it.delegateName}")
            }
        }
    private val inferenceEngine: YoloInferenceEngine by inferenceEngineLazy

    private val postProcessor = YoloPostProcessor()
    private val tracker       = ByteTracker()

    // ─── FrameProcessor impl — Stage 1: analysisExecutor ───────────────────

    /**
     * ImageProxy 픽셀을 복사하고 메타데이터 저장.
     * 이 함수 반환 직후 CameraManager 가 imageProxy.close() 를 호출.
     */
    override fun copyFrame(imageProxy: ImageProxy): Boolean {
        lastFrameW   = imageProxy.width
        lastFrameH   = imageProxy.height
        lastRotation = imageProxy.imageInfo.rotationDegrees
        return preprocessor.copyFromProxy(imageProxy)
    }

    // ─── FrameProcessor impl — Stage 2: Dispatchers.Default ────────────────

    /**
     * 복사된 프레임 데이터로 전처리 → 추론 → 후처리 → 트래킹 전체 실행.
     */
    override suspend fun runPipeline(): List<TrackedObject> {
        val tTotal = SystemClock.uptimeMillis()

        // ── Phase 2: Letterbox 전처리 ──────────────────────────────────────
        val t2 = SystemClock.uptimeMillis()
        val preprocessOk = preprocessor.processFromBitmap()
        val preprocessMs = SystemClock.uptimeMillis() - t2

        if (!preprocessOk) {
            LogCollector.w(TAG, "Preprocessing failed — skipping frame (cam ${lastFrameW}×${lastFrameH})")
            return emptyList()
        }

        // ── Phase 3: TFLite 추론 (GPU Delegate는 고정 스레드 필요) ──────────
        val t3 = SystemClock.uptimeMillis()
        val inferenceOk = withContext(gpuThread) {
            inferenceEngine.run(preprocessor.outputBuffer)
        }
        val inferenceMs = SystemClock.uptimeMillis() - t3

        if (!inferenceOk) {
            LogCollector.w(TAG, "Inference failed — skipping frame")
            return emptyList()
        }

        // ── Phase 4: 화면 크기 확인 + 좌표 역산 ────────────────────────────
        val displayW = overlayView?.width  ?: 0
        val displayH = overlayView?.height ?: 0
        if (displayW <= 0 || displayH <= 0) {
            LogCollector.d(TAG, "OverlayView 미측정(${displayW}×${displayH}) — 프레임 스킵")
            return emptyList()
        }

        val params = preprocessor.letterboxParams
        LogCollector.v(TAG,
            "frame cam=${lastFrameW}×${lastFrameH} rot=${lastRotation}° " +
            "effCam=${params.effCamW}×${params.effCamH} display=${displayW}×${displayH} " +
            "scale=%.3f padL=%.1f padT=%.1f".format(params.scale, params.padLeft, params.padTop)
        )

        val t4 = SystemClock.uptimeMillis()
        // [지침 1] PostProcessor: AI(416) 원본 좌표만 반환 — letterboxParams / display 제거
        val detections = postProcessor.decode(inferenceEngine)
        val nmsMs = SystemClock.uptimeMillis() - t4

        // [지침 4] OverlayView 에 AI(416) → Display 합산 변환 파라미터 전달
        //   renderScale   = dispScale / letterboxScale
        //   renderOffsetX = -padLeft * renderScale + startX
        //   renderOffsetY = -padTop  * renderScale + startY
        val effCamW       = params.effCamW.toFloat()
        val effCamH       = params.effCamH.toFloat()
        val dispScale     = maxOf(displayW / effCamW, displayH / effCamH)
        val startX        = (displayW - effCamW * dispScale) * 0.5f
        val startY        = (displayH - effCamH * dispScale) * 0.5f
        val renderScale   = dispScale / params.scale
        val renderOffsetX = -params.padLeft * renderScale + startX
        val renderOffsetY = -params.padTop  * renderScale + startY
        (overlayView as? OverlayView)?.setRenderParams(renderScale, renderOffsetX, renderOffsetY)

        // 디버그 카운터 OverlayView 에 전달
        (overlayView as? OverlayView)
            ?.setDebugInfo(postProcessor.lastRawCount, postProcessor.lastNmsCount)
        val totalMs = SystemClock.uptimeMillis() - tTotal

        val perfMsg = "Pre=${preprocessMs}ms  Infer=${inferenceMs}ms  NMS=${nmsMs}ms  Total=${totalMs}ms" +
            "  det=${detections.size}  delegate=${inferenceEngine.delegateName}"
        Log.d("VisionPipeline", perfMsg)
        LogCollector.d("VisionPipeline", perfMsg)

        // ── FPS 계산 ───────────────────────────────────────────────────────
        frameCount++
        val nowMs = SystemClock.uptimeMillis()
        if (fpsWindowMs == 0L) fpsWindowMs = nowMs
        val elapsed = nowMs - fpsWindowMs
        if (elapsed >= 1000L) {
            currentFps = frameCount * 1000f / elapsed
            if (detections.isNotEmpty() || frameCount % 30 == 0) {
                LogCollector.d(TAG,
                    "FPS=%.1f  pre=${preprocessMs}ms  infer=${inferenceMs}ms  nms=${nmsMs}ms  total=${totalMs}ms  det=${detections.size}  delegate=${inferenceEngine.delegateName}"
                        .format(currentFps))
            }
            frameCount  = 0
            fpsWindowMs = nowMs
        }

        // ── Stats 콜백 ──────────────────────────────────────────────────────
        onStats?.invoke(
            PipelineStats(
                delegateName   = if (inferenceEngineLazy.isInitialized()) inferenceEngine.delegateName else "init",
                fps            = currentFps,
                preprocessMs   = preprocessMs,
                inferenceMs    = inferenceMs,
                nmsMs          = nmsMs,
                detectionCount = detections.size
            )
        )

        // ── Phase 5: Absolute Lock-on ByteTrack + Hybrid Ego-Motion 보정
        val (gyroDx, gyroDy) = GyroEgoMotion.consumeDelta(TrackerConfig.deviceRotation)
        return tracker.update(detections, gyroDx, gyroDy)
    }

    fun release() {
        preprocessor.release()
        if (inferenceEngineLazy.isInitialized()) {
            // GPU Delegate는 생성된 것과 동일한 고정 스레드에서 close() 해야 함
            runBlocking(gpuThread) { inferenceEngine.close() }
        }
        gpuThread.close()
        LogCollector.i(TAG, "Pipeline released")
    }
}
