package com.intellitimer.vision.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * 자이로스코프 기반 카메라 Ego-Motion 추정 — Singleton.
 *
 * 동작 원리:
 *   1. SENSOR_DELAY_FASTEST 로 자이로스코프 이벤트 수신 (센서 스레드)
 *   2. 각속도(rad/s) × dt(s) = 프레임 내 회전각 → 누적
 *   3. consumeDelta() 호출 시 누적 픽셀 변위 반환 & 리셋 (AI 스레드에서 호출)
 *   4. deviceRotation 적용: AI 캔버스가 deviceRotation 만큼 CW 회전되므로 동일 각도 보정
 *
 * 초점거리 근사 (AI 416×416 기준):
 *   FOCAL_LENGTH_PX = (416 / 2) / tan(HFOV / 2)
 *   스마트폰 일반적 수평 FOV ~60° → focal ≈ 360px
 *
 * 축 정의 (폰 portrait 기준):
 *   values[0] = X축 각속도 (pitch — 화면 위쪽이 앞으로 기울어짐)
 *   values[1] = Y축 각속도 (yaw   — 화면이 오른쪽으로 회전)
 *   values[2] = Z축 각속도 (roll  — 사용 안 함)
 *
 * Zero-GC: SensorEvent 처리에서 신규 객체 생성 없음.
 */
object GyroEgoMotion : SensorEventListener {

    private const val FOCAL_LENGTH_PX = 360f   // 60° HFOV 근사 (AI 416px 기준)
    private const val NOISE_FLOOR_RAD  = 0.008f // rad/s — 손 떨림 노이즈 제거 임계값
    private const val MAX_DT_SEC       = 0.15f  // 이보다 긴 간격은 순간 점프 → 무시

    /** 자이로 센서 사용 가능 여부 */
    @Volatile var isAvailable = false
        private set

    // ─── 누적 버퍼 (portrait 기준 raw 픽셀 변위) ─────────────────────────────
    // 센서 스레드와 AI 스레드 동시 접근 — @Volatile 으로 가시성 보장
    @Volatile private var accumRawDx = 0f
    @Volatile private var accumRawDy = 0f
    @Volatile private var lastTs = 0L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun register(context: Context) {
        val sm   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
            isAvailable = true
        }
    }

    fun unregister(context: Context) {
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .unregisterListener(this)
        isAvailable  = false
        accumRawDx   = 0f
        accumRawDy   = 0f
        lastTs       = 0L
    }

    // ─── AI 스레드 API ────────────────────────────────────────────────────────

    /**
     * 마지막 consumeDelta() 호출 이후 누적된 픽셀 변위를 AI 416 공간으로 변환해 반환.
     * 호출 후 누적 버퍼 리셋.
     *
     * deviceRotation 변환 (AI 캔버스가 deviceRotation CW 회전 적용되므로 동일 역회전):
     *   dr=  0: AI_dx =  rawDx,  AI_dy =  rawDy
     *   dr= 90: AI_dx =  rawDy,  AI_dy = -rawDx
     *   dr=180: AI_dx = -rawDx,  AI_dy = -rawDy
     *   dr=270: AI_dx = -rawDy,  AI_dy =  rawDx
     *
     * @param deviceRotation TrackerConfig.deviceRotation (0/90/180/270)
     * @return (pixelDx, pixelDy) in AI 416 space
     */
    fun consumeDelta(deviceRotation: Int): Pair<Float, Float> {
        val rx = accumRawDx; val ry = accumRawDy
        accumRawDx = 0f;     accumRawDy = 0f

        val aiDx = when (deviceRotation) {
            90  ->  ry
            180 -> -rx
            270 -> -ry
            else -> rx
        }
        val aiDy = when (deviceRotation) {
            90  -> -rx
            180 -> -ry
            270 ->  rx
            else -> ry
        }
        return Pair(aiDx, aiDy)
    }

    // ─── SensorEventListener ─────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        val now = event.timestamp                   // nanoseconds
        val dt  = if (lastTs == 0L) 0f else (now - lastTs) * 1e-9f
        lastTs  = now
        if (dt <= 0f || dt > MAX_DT_SEC) return     // 첫 샘플 / 너무 긴 간격 무시

        // 노이즈 플로어 이하 → 0 처리 (정적 드리프트 억제)
        val ωYaw   = if (abs(event.values[1]) > NOISE_FLOOR_RAD) event.values[1] else 0f
        val ωPitch = if (abs(event.values[0]) > NOISE_FLOOR_RAD) event.values[0] else 0f

        // portrait 기준 픽셀 변위 누적
        //   yaw right (+Y)  → 장면이 왼쪽으로 이동 → rawDx negative
        //   pitch down (-X) → 장면이 위로 이동     → rawDy positive (카메라 앞이 내려감)
        accumRawDx += -ωYaw   * dt * FOCAL_LENGTH_PX
        accumRawDy += -ωPitch * dt * FOCAL_LENGTH_PX
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
