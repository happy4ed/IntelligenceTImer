package com.intellitimer.vision.tracking

import android.graphics.PointF
import android.graphics.RectF
import com.intellitimer.vision.model.AppSettings
import com.intellitimer.vision.model.Detection
import com.intellitimer.vision.model.TrackedObject

/**
 * ByteTrack 스타일 다중 객체 트래커 — Predict → Match → Update 파이프라인.
 *
 * [지침 2] Tracker 연산 공간 = AI(YOLO) 원본 416×416.
 * [지침 3] AI 공간 이탈 즉시 Kill (Coasting 허용 안 함).
 *           Coasting 마찰력 0.6f → 제자리에서 스르륵 소멸.
 *
 * Zero-GC: update() 내 신규 객체 생성 = 0.
 */
class ByteTracker {

    companion object {
        const val MAX_TRACKS        = 50
        const val TRAJ_STEPS        = 20
        const val IOU_THRESHOLD     = 0.25f
        const val IOU_LOW_THRESH    = 0.15f
        const val HIGH_CONF         = 0.40f
        const val LOW_CONF_MIN      = 0.10f

        // [지침 3] AI 공간 경계 (416×416)
        private const val AI_SIZE = 416f

        // [지침 1] 사전 Global Motion: 80px 이내 명확한 짝 (빠른 카메라 패닝 대응)
        private const val PRE_PAIR_THRESH_SQ = 80f * 80f

        // [지침 3] Coasting 마찰력: 매 프레임 곱해서 스르륵 소멸
        private const val COASTING_FRICTION  = 0.6f

        private const val TRAIL_MIN_SPEED    = 1.5f
    }

    private var nextId = 1

    private val trackPool: Array<TrackedObject> = Array(MAX_TRACKS) {
        TrackedObject(
            trackId             = -1,
            detection           = Detection(classId = 0, confidence = 0f, rect = RectF()),
            velocityX           = 0f,
            velocityY           = 0f,
            predictedTrajectory = ArrayList<PointF>(TRAJ_STEPS).also { l ->
                repeat(TRAJ_STEPS) { l.add(PointF()) }
            },
            invisibleCount      = 0
        )
    }

    private val slotActive = BooleanArray(MAX_TRACKS)
    private val missingCnt = IntArray(MAX_TRACKS)
    private val confirmCnt = IntArray(MAX_TRACKS)

    // [지침 2] Predict-First: 사전 예측 좌표
    private val predictedCx   = FloatArray(MAX_TRACKS)
    private val predictedCy   = FloatArray(MAX_TRACKS)
    private val predictedRect = Array(MAX_TRACKS) { RectF() }

    private val detMatched  = BooleanArray(100)
    private val trkMatched  = BooleanArray(MAX_TRACKS)
    private val isHighDet   = BooleanArray(100)
    private val pendingTi   = IntArray(MAX_TRACKS)
    private val pendingDi   = IntArray(MAX_TRACKS)
    private var pendingCount = 0

    private val result  = ArrayList<TrackedObject>(MAX_TRACKS)
    private val dxBuf   = FloatArray(MAX_TRACKS)
    private val dyBuf   = FloatArray(MAX_TRACKS)
    private val sortBuf = FloatArray(MAX_TRACKS)

    // [지침 2] 매칭 후 실제 rawDx/rawDy 수집 → post-global motion 계산 (Zero-GC)
    private val matchedRawDx = FloatArray(MAX_TRACKS)
    private val matchedRawDy = FloatArray(MAX_TRACKS)
    private val matchedTi    = IntArray(MAX_TRACKS)
    private var matchedCount  = 0

    fun update(detections: List<Detection>): List<TrackedObject> {
        result.clear()
        val nDet = detections.size.coerceAtMost(detMatched.size)

        detMatched.fill(false, 0, nDet)
        trkMatched.fill(false)
        isHighDet.fill(false, 0, nDet)
        pendingCount = 0

        for (di in 0 until nDet) isHighDet[di] = detections[di].confidence > HIGH_CONF

        // ══════════════════════════════════════════════════════════════════════
        // Phase 1 — 사전 Global Motion 추정 (30px 이내 명확한 짝)
        // ══════════════════════════════════════════════════════════════════════
        var preCount = 0
        for (ti in 0 until MAX_TRACKS) {
            if (!slotActive[ti]) continue
            val cx = trackPool[ti].detection.rect.centerX()
            val cy = trackPool[ti].detection.rect.centerY()
            var minD2 = PRE_PAIR_THRESH_SQ; var bDi = -1
            for (di in 0 until nDet) {
                val ddx = detections[di].rect.centerX() - cx
                val ddy = detections[di].rect.centerY() - cy
                val d2  = ddx * ddx + ddy * ddy
                if (d2 < minD2) { minD2 = d2; bDi = di }
            }
            if (bDi >= 0) {
                dxBuf[preCount] = detections[bDi].rect.centerX() - cx
                dyBuf[preCount] = detections[bDi].rect.centerY() - cy
                preCount++
            }
        }
        val globalDx = if (preCount >= 2) median(dxBuf, preCount) else 0f
        val globalDy = if (preCount >= 2) median(dyBuf, preCount) else 0f

        // ══════════════════════════════════════════════════════════════════════
        // Phase 2 — Predict-First
        //   predicted = current + velocity + globalDx
        // ══════════════════════════════════════════════════════════════════════
        for (ti in 0 until MAX_TRACKS) {
            if (!slotActive[ti]) continue
            val r   = trackPool[ti].detection.rect
            val hw  = r.width() / 2f; val hh = r.height() / 2f
            val pcx = r.centerX() + trackPool[ti].velocityX + globalDx
            val pcy = r.centerY() + trackPool[ti].velocityY + globalDy
            predictedCx[ti] = pcx; predictedCy[ti] = pcy
            predictedRect[ti].set(pcx - hw, pcy - hh, pcx + hw, pcy + hh)
        }

        // ══════════════════════════════════════════════════════════════════════
        // Phase 3a — 1차 매칭: 예측 트랙 ↔ High 박스 (IoU Greedy)
        // ══════════════════════════════════════════════════════════════════════
        for (di in 0 until nDet) {
            if (!isHighDet[di]) continue
            var bestIou = IOU_THRESHOLD; var bestTi = -1
            for (ti in 0 until MAX_TRACKS) {
                if (!slotActive[ti] || trkMatched[ti]) continue
                val v = iou(detections[di].rect, predictedRect[ti])
                if (v > bestIou) { bestIou = v; bestTi = ti }
            }
            if (bestTi >= 0) {
                pendingTi[pendingCount] = bestTi; pendingDi[pendingCount] = di; pendingCount++
                detMatched[di] = true; trkMatched[bestTi] = true
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // Phase 3b — 2차 매칭: 예측 트랙 ↔ Low 박스 (IoU Greedy)
        // ══════════════════════════════════════════════════════════════════════
        for (di in 0 until nDet) {
            if (detMatched[di] || isHighDet[di] || detections[di].confidence < LOW_CONF_MIN) continue
            var bestIou = IOU_LOW_THRESH; var bestTi = -1
            for (ti in 0 until MAX_TRACKS) {
                if (!slotActive[ti] || trkMatched[ti]) continue
                val v = iou(detections[di].rect, predictedRect[ti])
                if (v > bestIou) { bestIou = v; bestTi = ti }
            }
            if (bestTi >= 0) {
                pendingTi[pendingCount] = bestTi; pendingDi[pendingCount] = di; pendingCount++
                detMatched[di] = true; trkMatched[bestTi] = true
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // Phase 3c — 3차 매칭: 예측 중심점 ↔ 미매칭 High 박스 (Euclidean Lock-on)
        // ══════════════════════════════════════════════════════════════════════
        val matchRadiusSq = TrackerConfig.matchRadius * TrackerConfig.matchRadius
        for (di in 0 until nDet) {
            if (detMatched[di] || !isHighDet[di]) continue
            val detCx = detections[di].rect.centerX(); val detCy = detections[di].rect.centerY()
            var bestD2 = matchRadiusSq; var bestTi = -1
            for (ti in 0 until MAX_TRACKS) {
                if (!slotActive[ti] || trkMatched[ti]) continue
                val dx = detCx - predictedCx[ti]; val dy = detCy - predictedCy[ti]
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; bestTi = ti }
            }
            if (bestTi >= 0) {
                pendingTi[pendingCount] = bestTi; pendingDi[pendingCount] = di; pendingCount++
                detMatched[di] = true; trkMatched[bestTi] = true
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // Phase 4 — Two-Pass Update (Fix: velocityX/Y uses postGlobalDx)
        // Pass 1: rawDx 수집 + rect/meta 업데이트만 (velocity 는 아직 건드리지 않음)
        // ══════════════════════════════════════════════════════════════════════
        matchedCount = 0
        for (p in 0 until pendingCount) {
            val ti = pendingTi[p]; val di = pendingDi[p]
            val oldCx = trackPool[ti].detection.rect.centerX()
            val oldCy = trackPool[ti].detection.rect.centerY()
            matchedRawDx[matchedCount] = detections[di].rect.centerX() - oldCx
            matchedRawDy[matchedCount] = detections[di].rect.centerY() - oldCy
            matchedTi[matchedCount++]  = ti
            updateSlotMeta(ti, detections[di])
        }

        // [지침 2] 매칭 후 rawDx 중앙값 → 정확한 post-global motion
        val postGlobalDx = if (matchedCount >= 2) median(matchedRawDx, matchedCount) else globalDx
        val postGlobalDy = if (matchedCount >= 2) median(matchedRawDy, matchedCount) else globalDy

        // globalDx 추정 신뢰성: 2개 이상 참조쌍이 있어야 카메라↔객체 분리 가능
        val hasReliableGlobal = matchedCount >= 2 || preCount >= 2

        // Pass 2: 정확한 postGlobalDx 로 velocityX/Y + trueVx/isMoving 갱신
        for (m in 0 until matchedCount) {
            val ti    = matchedTi[m]
            val track = trackPool[ti]
            updateSlotVelocity(ti, matchedRawDx[m], matchedRawDy[m], postGlobalDx, postGlobalDy)
            if (hasReliableGlobal) {
                val instTrueVx = matchedRawDx[m] - postGlobalDx
                val instTrueVy = matchedRawDy[m] - postGlobalDy
                track.trueVx   = track.trueVx * 0.8f + instTrueVx * 0.2f
                track.trueVy   = track.trueVy * 0.8f + instTrueVy * 0.2f
                track.isMoving = Math.hypot(track.trueVx.toDouble(), track.trueVy.toDouble()) >= 1.0
            } else {
                // 참조쌍 부족 — 카메라 움직임과 객체 움직임 분리 불가 → 보수적으로 처리
                track.trueVx  *= 0.8f
                track.trueVy  *= 0.8f
                track.isMoving = false
            }
        }

        // Phase 5 — Spawn
        for (di in 0 until nDet) {
            if (!detMatched[di] && isHighDet[di]) spawnTrack(detections[di])
        }

        // ══════════════════════════════════════════════════════════════════════
        // [지침 3] Phase 6 — Coasting + AI 공간 이탈 즉시 Kill
        // ══════════════════════════════════════════════════════════════════════
        val maxCoast = TrackerConfig.maxCoastFrames
        for (ti in 0 until MAX_TRACKS) {
            if (!slotActive[ti] || trkMatched[ti]) continue

            // AI 공간 이탈 체크 (Coasting 시작 전) — Phantom Track 원천 차단
            val cx0 = trackPool[ti].detection.rect.centerX()
            val cy0 = trackPool[ti].detection.rect.centerY()
            if (cx0 < 0f || cx0 > AI_SIZE || cy0 < 0f || cy0 > AI_SIZE) {
                slotActive[ti] = false; confirmCnt[ti] = 0; continue
            }

            missingCnt[ti]++
            trackPool[ti].invisibleCount = missingCnt[ti]

            if (missingCnt[ti] > maxCoast) {
                slotActive[ti] = false; confirmCnt[ti] = 0; continue
            }

            // [지침 3] 마찰력 0.6f + globalDx 앵커링
            val r = trackPool[ti].detection.rect
            r.offset(
                trackPool[ti].velocityX * COASTING_FRICTION + globalDx,
                trackPool[ti].velocityY * COASTING_FRICTION + globalDy
            )
            trackPool[ti].velocityX *= COASTING_FRICTION
            trackPool[ti].velocityY *= COASTING_FRICTION

            // Coasting 중 AI 공간 이탈 시 즉시 Kill
            val cx = r.centerX(); val cy = r.centerY()
            if (cx < 0f || cx > AI_SIZE || cy < 0f || cy > AI_SIZE) {
                slotActive[ti] = false; confirmCnt[ti] = 0
            }
        }

        // Phase 7 — 궤적 계산 + futurePoints + 결과 수집
        val confirmThresh = AppSettings.confirmFrames
        val maxDet        = AppSettings.maxDetections
        for (ti in 0 until MAX_TRACKS) {
            if (!slotActive[ti]) continue
            if (confirmCnt[ti] < confirmThresh) continue
            val track = trackPool[ti]
            val spd = Math.hypot(track.velocityX.toDouble(), track.velocityY.toDouble())
            if (spd >= TRAIL_MIN_SPEED) computeTrajectory(ti)

            // [지침 4] Ego-Motion 보정 속도로 20프레임 미래 예측 (마찰력 0.9f)
            if (track.isMoving) {
                var tvx = track.trueVx; var tvy = track.trueVy
                var px  = track.detection.rect.centerX()
                var py  = track.detection.rect.centerY()
                for (k in 0 until 20) {
                    px += tvx; py += tvy
                    tvx *= 0.9f; tvy *= 0.9f
                    track.futurePoints[k].set(px, py)
                }
            }

            result.add(track)
            if (result.size >= maxDet) break
        }

        return result
    }

    /** Pass 1: rect/meta 업데이트만. velocity 는 updateSlotVelocity 에서 처리. */
    private fun updateSlotMeta(ti: Int, det: Detection) {
        val track = trackPool[ti]
        track.detection.classId    = det.classId
        track.detection.confidence = det.confidence
        missingCnt[ti]       = 0
        track.invisibleCount = 0
        if (confirmCnt[ti] < 255) confirmCnt[ti]++
        // BBox raw 스냅 (EMA 없음)
        track.detection.rect.set(det.rect)
    }

    /** Pass 2: 정확한 postGlobalDx 로 velocityX/Y EMA 갱신. */
    private fun updateSlotVelocity(ti: Int, rawDx: Float, rawDy: Float, postGlobalDx: Float, postGlobalDy: Float) {
        val track = trackPool[ti]
        // [지침 4] 순수 이동량 = rawDx - postGlobalDx
        val pureDx = rawDx - postGlobalDx
        val pureDy = rawDy - postGlobalDy

        val dz = TrackerConfig.deadZone.toDouble()
        val instVx: Float; val instVy: Float
        if (Math.hypot(pureDx.toDouble(), pureDy.toDouble()) < dz) {
            instVx = 0f; instVy = 0f
        } else {
            instVx = pureDx; instVy = pureDy
        }

        val alpha = TrackerConfig.velocityEMA
        val beta  = 1f - alpha
        track.velocityX = beta * instVx + alpha * track.velocityX
        track.velocityY = beta * instVy + alpha * track.velocityY
    }

    private fun spawnTrack(det: Detection) {
        val ti = (0 until MAX_TRACKS).firstOrNull { !slotActive[it] } ?: return
        val track = trackPool[ti]
        track.trackId              = nextId++
        track.detection.classId    = det.classId
        track.detection.confidence = det.confidence
        track.detection.rect.set(det.rect)
        track.velocityX            = 0f
        track.velocityY            = 0f
        track.invisibleCount       = 0
        track.trueVx               = 0f
        track.trueVy               = 0f
        track.isMoving             = false
        slotActive[ti] = true; missingCnt[ti] = 0; confirmCnt[ti] = 1
        val cx = det.rect.centerX(); val cy = det.rect.centerY()
        track.predictedTrajectory.forEach { pt -> pt.set(cx, cy) }
    }

    private fun computeTrajectory(ti: Int) {
        val track = trackPool[ti]
        val cx = track.detection.rect.centerX(); val cy = track.detection.rect.centerY()
        val vx = track.velocityX;                val vy = track.velocityY
        val n  = TRAJ_STEPS.toFloat()
        val p0x = cx; val p0y = cy
        val p1x = cx + vx * n / 3f; val p1y = cy + vy * n / 3f
        val p2x = cx + vx * n * 2f / 3f; val p2y = cy + vy * n * 2f / 3f
        val p3x = cx + vx * n; val p3y = cy + vy * n
        for (i in 0 until TRAJ_STEPS) {
            val t = (i + 1) / n
            val mt = 1f - t; val mt2 = mt * mt; val t2 = t * t
            track.predictedTrajectory[i].set(
                mt2 * mt * p0x + 3f * mt2 * t * p1x + 3f * mt * t2 * p2x + t2 * t * p3x,
                mt2 * mt * p0y + 3f * mt2 * t * p1y + 3f * mt * t2 * p2y + t2 * t * p3y
            )
        }
    }

    private fun median(buf: FloatArray, n: Int): Float {
        System.arraycopy(buf, 0, sortBuf, 0, n)
        for (i in 1 until n) {
            val key = sortBuf[i]; var j = i - 1
            while (j >= 0 && sortBuf[j] > key) { sortBuf[j + 1] = sortBuf[j]; j-- }
            sortBuf[j + 1] = key
        }
        val mid = n / 2
        return if (n % 2 == 0) (sortBuf[mid - 1] + sortBuf[mid]) / 2f else sortBuf[mid]
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left); val iR = minOf(a.right, b.right)
        val iT = maxOf(a.top,  b.top);  val iB = minOf(a.bottom, b.bottom)
        if (iR <= iL || iB <= iT) return 0f
        val inter = (iR - iL) * (iB - iT)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }
}
