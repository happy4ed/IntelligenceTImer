package com.intellitimer.vision.inference

import android.graphics.Matrix
import android.graphics.RectF
import com.intellitimer.vision.model.AppSettings
import com.intellitimer.vision.model.Detection
import com.intellitimer.vision.tracking.TrackerConfig
import com.intellitimer.vision.ui.LogCollector

private const val TAG = "YoloPostProcessor"

/**
 * Phase 4: YOLO11n 추론 결과 후처리기 — AI Space Only Edition.
 *
 * [지침 1] dispScale / startX / startY / letterbox 역산 완전 제거.
 *   Clamp(coerceIn / Math.max / Math.min) 없음.
 *   COORD_FAIL 없음.
 *
 *   출력 좌표 = 순수 AI(YOLO) 원본 텐서 공간 (416×416 기준).
 *   화면 변환은 OverlayView.onDraw() 에서만 수행.
 */
class YoloPostProcessor {

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.30f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MAX_CANDIDATES    = 300
        private const val NUM_CLASSES       = 80
        private const val NUM_CHANNELS      = YoloInferenceEngine.NUM_CHANNELS  // 84
        private const val DIAG_INTERVAL     = 30
        private val INPUT_SIZE = ImagePreprocessor.INPUT_SIZE.toFloat()  // 416
    }

    // [지침 3] BBox 역회전용 — pre-alloc (Zero-GC)
    private val rotMatrix = Matrix()

    private var rawOutput    = FloatArray(0)
    private val candConf     = FloatArray(MAX_CANDIDATES)
    private val candClassId  = IntArray(MAX_CANDIDATES)
    private val candXmin     = FloatArray(MAX_CANDIDATES)
    private val candYmin     = FloatArray(MAX_CANDIDATES)
    private val candXmax     = FloatArray(MAX_CANDIDATES)
    private val candYmax     = FloatArray(MAX_CANDIDATES)
    private val suppressed   = BooleanArray(MAX_CANDIDATES)
    private var numCandidates = 0

    private val detectionPool    = List(MAX_CANDIDATES) { Detection(0, 0f, RectF()) }
    private val activeDetections = mutableListOf<Detection>()

    var lastRawCount: Int = 0
        private set
    var lastNmsCount: Int = 0
        private set

    private var diagFrameCount = 0
    private var coordScale: Float? = null

    /**
     * 추론 결과를 AI(416) 좌표계 Detection 리스트로 변환.
     * letterboxParams / displayWidth / displayHeight 파라미터 완전 제거.
     */
    fun decode(inferenceEngine: YoloInferenceEngine): List<Detection> {
        activeDetections.clear()

        val shape = inferenceEngine.outputShape
        val numAnchors: Int
        val isTransposed: Boolean

        when {
            shape.size >= 3 && shape[1] == NUM_CHANNELS -> { numAnchors = shape[2]; isTransposed = true }
            shape.size >= 3 && shape[2] == NUM_CHANNELS -> { numAnchors = shape[1]; isTransposed = false }
            else -> { numAnchors = inferenceEngine.numAnchors; isTransposed = true }
        }

        val needed = NUM_CHANNELS * numAnchors
        if (rawOutput.size < needed) rawOutput = FloatArray(needed)

        inferenceEngine.outputBuffer.rewind()
        inferenceEngine.outputBuffer.asFloatBuffer().get(rawOutput, 0, needed)

        val threshold = AppSettings.confidenceThreshold
        diagFrameCount++
        val doLog = (diagFrameCount % DIAG_INTERVAL == 0)

        // ── 첫 프레임 raw 텐서 덤프 ────────────────────────────────────────────
        if (diagFrameCount == 1) {
            val sb = StringBuilder("RAW_DUMP(첫5앵커): ")
            for (k in 0 until minOf(5, numAnchors)) {
                val cx0 = if (isTransposed) rawOutput[0 * numAnchors + k] else rawOutput[k * NUM_CHANNELS + 0]
                val cy0 = if (isTransposed) rawOutput[1 * numAnchors + k] else rawOutput[k * NUM_CHANNELS + 1]
                val w0  = if (isTransposed) rawOutput[2 * numAnchors + k] else rawOutput[k * NUM_CHANNELS + 2]
                val h0  = if (isTransposed) rawOutput[3 * numAnchors + k] else rawOutput[k * NUM_CHANNELS + 3]
                sb.append("[k$k cx=${"%.3f".format(cx0)} cy=${"%.3f".format(cy0)} w=${"%.3f".format(w0)} h=${"%.3f".format(h0)}] ")
            }
            LogCollector.i(TAG, sb.toString())
        }

        numCandidates = 0
        var maxConfSeen = 0f
        var firstCandLogged = false

        for (i in 0 until numAnchors) {
            val cxRaw: Float; val cyRaw: Float; val wRaw: Float; val hRaw: Float
            var bestScore = 0f; var bestClassId = 0

            if (isTransposed) {
                cxRaw = rawOutput[0 * numAnchors + i]
                cyRaw = rawOutput[1 * numAnchors + i]
                wRaw  = rawOutput[2 * numAnchors + i]
                hRaw  = rawOutput[3 * numAnchors + i]
                for (c in 0 until NUM_CLASSES) {
                    val s = rawOutput[(4 + c) * numAnchors + i]
                    if (s > bestScore) { bestScore = s; bestClassId = c }
                }
            } else {
                cxRaw = rawOutput[i * NUM_CHANNELS + 0]
                cyRaw = rawOutput[i * NUM_CHANNELS + 1]
                wRaw  = rawOutput[i * NUM_CHANNELS + 2]
                hRaw  = rawOutput[i * NUM_CHANNELS + 3]
                for (c in 0 until NUM_CLASSES) {
                    val s = rawOutput[i * NUM_CHANNELS + 4 + c]
                    if (s > bestScore) { bestScore = s; bestClassId = c }
                }
            }

            if (bestScore > maxConfSeen) maxConfSeen = bestScore
            if (bestScore < threshold) continue
            if (!TrackerConfig.classFilter[bestClassId]) continue   // [지침 5] Early reject — NMS·RectF 생성 없이 즉시 skip
            if (numCandidates >= MAX_CANDIDATES) break

            // ── 좌표 스케일 자동 결정 (최초 후보 기준) ──────────────────────────
            if (coordScale == null) {
                coordScale = if (cxRaw < 2.0f && cxRaw > 0f) INPUT_SIZE else 1f
                LogCollector.i(TAG,
                    "COORD_AUTO: anchor=$i cxRaw=${"%.4f".format(cxRaw)} → coordScale=$coordScale")
            }

            val cs = coordScale ?: 1f
            val cx = cxRaw * cs; val cy = cyRaw * cs
            val w  = wRaw  * cs; val h  = hRaw  * cs

            if (!firstCandLogged) {
                LogCollector.w(TAG,
                    "FIRST_CAND: anchor=$i cx416=${"%.2f".format(cx)} cy416=${"%.2f".format(cy)} " +
                    "w=${"%.2f".format(w)} h=${"%.2f".format(h)} conf=${"%.4f".format(bestScore)} " +
                    "class=$bestClassId coordScale=$cs")
                firstCandLogged = true
            }

            val halfW = w * 0.5f; val halfH = h * 0.5f
            candXmin[numCandidates]    = cx - halfW; candYmin[numCandidates] = cy - halfH
            candXmax[numCandidates]    = cx + halfW; candYmax[numCandidates] = cy + halfH
            candConf[numCandidates]    = bestScore
            candClassId[numCandidates] = bestClassId
            numCandidates++
        }

        lastRawCount = numCandidates

        if (doLog) {
            LogCollector.d(TAG,
                "shape=${shape.toList()} transposed=$isTransposed anchors=$numAnchors " +
                "maxConf=${"%.4f".format(maxConfSeen)} raw=$numCandidates " +
                "thresh=${"%.2f".format(threshold)} coordScale=$coordScale")
        }

        if (numCandidates == 0) { lastNmsCount = 0; return activeDetections }

        suppressed.fill(false, 0, numCandidates)
        nmsSort(numCandidates)

        for (i in 0 until numCandidates) {
            if (suppressed[i]) continue
            for (j in i + 1 until numCandidates) {
                if (!suppressed[j] && iou(i, j) > NMS_IOU_THRESHOLD) suppressed[j] = true
            }
        }

        // ── [지침 3] 역회전 Matrix 설정 (NMS 후, 출력 전) ──────────────────────
        // AI는 deviceRotation 만큼 정방향으로 회전된 이미지를 보고 BBox를 출력함.
        // Tracker/OverlayView는 카메라 원본 공간(회전 전)을 기대하므로 반대로 되돌림.
        val deviceRot = TrackerConfig.deviceRotation
        val cs = coordScale ?: INPUT_SIZE
        if (deviceRot != 0) {
            rotMatrix.setRotate(-deviceRot.toFloat(), cs / 2f, cs / 2f)
        }

        // ── [지침 1] AI(416) 원본 좌표 그대로 출력 — letterbox역산 없음, Clamp 없음 ──
        var outputIdx = 0
        for (i in 0 until numCandidates) {
            if (suppressed[i]) continue
            if (outputIdx >= MAX_CANDIDATES) break

            val sx = candXmin[i]; val sy = candYmin[i]
            val ex = candXmax[i]; val ey = candYmax[i]

            if (ex <= sx || ey <= sy) continue  // 퇴화 박스만 제거

            val det = detectionPool[outputIdx++]
            det.classId = candClassId[i]; det.confidence = candConf[i]
            det.rect.set(sx, sy, ex, ey)

            // [지침 3] 짐벌 역회전: AI 업라이트 공간 → 카메라 416 공간
            if (deviceRot != 0) rotMatrix.mapRect(det.rect)

            activeDetections.add(det)
        }

        lastNmsCount = activeDetections.size

        if (doLog) LogCollector.i(TAG, "NMS: ${activeDetections.size}개 (raw=$numCandidates)")

        return activeDetections
    }

    private fun iou(a: Int, b: Int): Float {
        val ix = (minOf(candXmax[a], candXmax[b]) - maxOf(candXmin[a], candXmin[b])).coerceAtLeast(0f)
        val iy = (minOf(candYmax[a], candYmax[b]) - maxOf(candYmin[a], candYmin[b])).coerceAtLeast(0f)
        val inter = ix * iy
        if (inter == 0f) return 0f
        val aA = (candXmax[a] - candXmin[a]) * (candYmax[a] - candYmin[a])
        val aB = (candXmax[b] - candXmin[b]) * (candYmax[b] - candYmin[b])
        return inter / (aA + aB - inter)
    }

    private fun nmsSort(n: Int) {
        for (i in 1 until n) {
            val cf = candConf[i]; val cl = candClassId[i]
            val x1 = candXmin[i]; val y1 = candYmin[i]
            val x2 = candXmax[i]; val y2 = candYmax[i]
            var j = i - 1
            while (j >= 0 && candConf[j] < cf) {
                candConf[j+1] = candConf[j]; candClassId[j+1] = candClassId[j]
                candXmin[j+1] = candXmin[j]; candYmin[j+1] = candYmin[j]
                candXmax[j+1] = candXmax[j]; candYmax[j+1] = candYmax[j]
                j--
            }
            candConf[j+1] = cf; candClassId[j+1] = cl
            candXmin[j+1] = x1; candYmin[j+1] = y1
            candXmax[j+1] = x2; candYmax[j+1] = y2
        }
    }
}
