package com.intellitimer.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.intellitimer.vision.camera.CameraManager
import com.intellitimer.vision.databinding.ActivityMainBinding
import com.intellitimer.vision.inference.YoloFrameProcessor
import com.intellitimer.vision.model.AppSettings
import com.intellitimer.vision.model.TrackedObject
import com.intellitimer.vision.tracking.TrackerConfig
import com.intellitimer.vision.ui.LogCollector
import com.intellitimer.vision.ui.LogViewerActivity
import com.intellitimer.vision.ui.PipelineStats
import com.intellitimer.vision.ui.SettingsBottomSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

/**
 * 진입점 Activity.
 *
 * 역할:
 * - 카메라 권한 요청
 * - overlayView layout 완료 대기 후 파이프라인 시작 (displayW/H 실측 보장)
 * - OverlayView 에 TrackedObject 결과 전달 (Main Thread)
 * - HudView 에 PipelineStats 업데이트 (Main Thread)
 * - LOG 버튼 → LogViewerActivity 진입
 * - OrientationEventListener 센서 생명주기 관리 (onResume/onPause)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraManager: CameraManager? = null
    private var frameProcessor: YoloFrameProcessor? = null

    // 매 프레임 HudView 갱신용 — onDestroy에서 cancel() 필수 (CoroutineScope 누수 방지)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // [지침 1] 물리 회전 감지 → UI 제자리 회전
    private var orientationListener: OrientationEventListener? = null

    // ── Permission ────────────────────────────────────────────────────────────

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVisionPipeline()   // 권한 승인 후 호출 — 이미 onGlobalLayout 완료 상태
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 저장된 설정 복원 — 파이프라인 시작 전 반드시 호출
        AppSettings.load(this)
        TrackerConfig.loadClassFilter(this)   // [지침 1] 클래스 필터 복원
        LogCollector.i(TAG, "onCreate — 레이아웃 측정 대기")

        // LOG 버튼: LogViewerActivity 진입
        binding.btnDebug.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // 설정 FAB: SettingsBottomSheet 표시
        binding.btnSettings.setOnClickListener {
            SettingsBottomSheet().show(supportFragmentManager, SettingsBottomSheet.TAG)
        }

        // [지침 1] OrientationEventListener — 0/90/180/270 스냅 → UI 제자리 역회전
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snapped = ((orientation + 45) / 90 * 90) % 360
                if (snapped != TrackerConfig.deviceRotation) {
                    TrackerConfig.deviceRotation = snapped
                    val rot = -snapped.toFloat()
                    binding.btnDebug.animate().rotation(rot).setDuration(300).start()
                    binding.btnSettings.animate().rotation(rot).setDuration(300).start()
                    binding.hudView.animate().rotation(rot).setDuration(300).start()
                }
            }
        }

        // overlayView layout 완료 후 카메라 시작 → 첫 프레임부터 실측 displayW/H 보장
        binding.overlayView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    binding.overlayView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    LogCollector.i(TAG, "overlayView measured: ${binding.overlayView.width}×${binding.overlayView.height}")
                    if (hasCameraPermission()) {
                        startVisionPipeline()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener?.disable()
        mainScope.cancel()          // CoroutineScope 누수 방지
        cameraManager?.shutdown()
        frameProcessor?.release()
        LogCollector.i(TAG, "onDestroy — pipeline released")
    }

    // ── Pipeline Bootstrap ────────────────────────────────────────────────────

    private fun startVisionPipeline() {
        LogCollector.i(TAG, "파이프라인 시작")

        val processor = YoloFrameProcessor(
            context     = this,
            overlayView = binding.overlayView
        ).also {
            it.onStats = ::onPipelineStats
            frameProcessor = it
        }

        cameraManager = CameraManager(
            context        = this,
            lifecycleOwner = this,
            previewView    = binding.previewView,
            frameProcessor = processor,
            onResults      = ::onVisionResults
        )
        cameraManager?.startCamera()
    }

    // ── Callbacks (Main Thread) ───────────────────────────────────────────────

    /** 추론+트래킹 결과 수신. Main Thread 에서 호출됨 (CameraManager.withContext(Main)). */
    private fun onVisionResults(results: List<TrackedObject>) {
        binding.overlayView.updateResults(results)
    }

    /**
     * 파이프라인 stats 수신. Dispatchers.Default 에서 호출되므로 Main 으로 전환.
     * HudView 는 UI thread 에서만 갱신 가능.
     */
    private fun onPipelineStats(stats: PipelineStats) {
        // mainScope 재사용 — 매 프레임 new CoroutineScope() 생성하지 않음 (OOM 방지)
        mainScope.launch {
            binding.hudView.update(stats)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}
