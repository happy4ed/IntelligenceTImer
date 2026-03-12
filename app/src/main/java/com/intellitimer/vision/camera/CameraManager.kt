package com.intellitimer.vision.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.intellitimer.vision.model.TrackedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraManager"

/**
 * CameraX 파이프라인 전담 클래스.
 *
 * Rule.md §2 CameraX Strict Setup:
 * - OUTPUT_IMAGE_FORMAT_RGBA_8888: YUV→RGB CPU 변환 오버헤드 제거
 * - STRATEGY_KEEP_ONLY_LATEST: 프레임 밀림(backpressure) 방지
 * - imageProxy.close() 반드시 finally 블록에서 호출
 *
 * Rule.md §3 Threading:
 * - 분석 콜백은 analysisExecutor(단일 백그라운드 스레드)에서 실행
 * - 이후 전처리/추론/트래킹은 Dispatchers.Default 코루틴으로 위임
 *
 * @param onResults  추론+트래킹 결과를 Main Thread 에서 수신할 콜백
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val frameProcessor: FrameProcessor,
    private val onResults: (List<TrackedObject>) -> Unit
) {
    // 단일 백그라운드 스레드: ImageAnalysis 콜백 수신 전용
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 파이프라인 처리용 코루틴 스코프 (Dispatchers.Default — Main Thread 아님)
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 추론 파이프라인 실행 중 여부.
     * analysisExecutor 에서 쓰고 Dispatchers.Default 에서 읽으므로 @Volatile 필수.
     * true 이면 새 프레임을 복사 없이 즉시 드롭 → sourceBitmap 경합 방지.
     */
    @Volatile private var inferenceRunning = false

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        // --- Preview UseCase ---
        // 화면 비율에 맞는 aspect ratio 지정 — PreviewView 와 동일 비율로 바인딩
        val screenAspectRatio = aspectRatio(previewView.width, previewView.height)
        val rotation = previewView.display?.rotation ?: 0

        val preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // --- ImageAnalysis UseCase ---
        // 640×480 타겟: 전처리 비용을 최소화. 모델 입력은 640×640 이므로
        // 카메라 해상도를 낮춰도 모델 품질 손실 없음 (어차피 letterbox 리사이즈).
        // Preview 는 별도 UseCase 이므로 고해상도 유지.
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            Log.d(TAG, "CameraX bound: RGBA_8888 / KEEP_ONLY_LATEST")
        } catch (e: Exception) {
            Log.e(TAG, "CameraX bindToLifecycle failed", e)
        }
    }

    /**
     * 프레임 수신 진입점 (analysisExecutor 스레드에서 호출).
     *
     * FPS 최적화 핵심:
     *  1. copyFrame() 으로 픽셀 복사 (~1-5ms)
     *  2. imageProxy.close() 즉시 → 카메라가 다음 프레임 준비 시작 (30fps 유지)
     *  3. 추론 중이면 해당 프레임 드롭 (sourceBitmap 경합 없음)
     *  4. 추론 아니면 inferenceRunning=true 후 코루틴 발사
     */
    private fun processFrame(imageProxy: ImageProxy) {
        // Step 1: 픽셀 복사 (analysisExecutor, ~1-5ms)
        val copied = frameProcessor.copyFrame(imageProxy)
        // Step 2: 카메라 프레임 즉시 해제 → 카메라 30fps 유지
        imageProxy.close()

        if (!copied) return
        // Step 3: 이전 추론이 아직 실행 중이면 드롭
        if (inferenceRunning) return

        inferenceRunning = true
        // Step 4: 추론 파이프라인 비동기 실행
        processingScope.launch {
            try {
                val results = frameProcessor.runPipeline()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onResults(results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
            } finally {
                inferenceRunning = false
            }
        }
    }

    /** Activity/Fragment onDestroy 시 호출하여 리소스 해제. */
    fun shutdown() {
        processingScope.cancel()
        analysisExecutor.shutdown()
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 화면 너비/높이로 가장 가까운 CameraX AspectRatio 결정.
     * 16:9 또는 4:3 중 편차가 작은 쪽 선택.
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = maxOf(width, height).toDouble() / minOf(width, height)
        return if (kotlin.math.abs(previewRatio - 4.0 / 3.0) <= kotlin.math.abs(previewRatio - 16.0 / 9.0)) {
            AspectRatio.RATIO_4_3
        } else {
            AspectRatio.RATIO_16_9
        }
    }
}
