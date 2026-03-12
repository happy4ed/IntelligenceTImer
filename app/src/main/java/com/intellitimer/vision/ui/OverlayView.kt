package com.intellitimer.vision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.intellitimer.vision.model.CocoLabels
import com.intellitimer.vision.model.TrackedObject
import com.intellitimer.vision.tracking.ByteTracker
import com.intellitimer.vision.tracking.TrackerConfig
import kotlin.math.sqrt

/**
 * Tesla FSD 스타일 탐지/트래킹/궤적 오버레이 커스텀 뷰.
 *
 * [지침 4] Render-time Coordinate Mapping — AI(416) → Display 단 한 번 변환:
 *   displayX = ai416X * renderScale + renderOffsetX
 *   displayY = ai416Y * renderScale + renderOffsetY
 *
 *   Clamp 절대 없음 — Canvas 가 뷰 경계 밖을 자연스럽게 클리핑.
 *
 * Zero-GC: 모든 Paint / Path / RectF 는 init 1회 할당. onDraw() 신규 객체 없음.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val TRACK_COLORS = intArrayOf(
        Color.rgb(  0, 229, 255),
        Color.rgb( 57, 255, 115),
        Color.rgb(255, 214,   0),
        Color.rgb(255,  85,  85),
        Color.rgb(187, 134, 252),
        Color.rgb(255, 171,  64),
        Color.rgb( 64, 196, 255),
        Color.rgb(255, 255, 255),
    )

    // ─── Pre-allocated Paint ──────────────────────────────────────────────────
    private val boxFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }

    private val boxStrokePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }

    private val trajDashPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val trajSolidPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.5f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.argb(190, 8, 8, 18); isAntiAlias = false
    }

    private val labelTextPaint = Paint().apply {
        style = Paint.Style.FILL; textSize = 30f; isFakeBoldText = true; isAntiAlias = true
    }

    private val coastingBorderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val debugTextPaint = Paint().apply {
        style = Paint.Style.FILL; textSize = 34f; isFakeBoldText = true; isAntiAlias = true
        color = Color.rgb(255, 214, 0); setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    // ─── Pre-allocated Path / RectF ──────────────────────────────────────────
    private val trajPath    = Path()
    private val segPath     = Path()
    private val arrowPath   = Path()
    private val cornerPath  = Path()
    private val labelBgRect = RectF()
    private val dispRect    = RectF()   // effCam/AI → display 변환 후 임시 보관

    // [지침 5] Ego-Motion 보정 미래 궤적 (두꺼운 시안 선)
    private val futureTrajPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        isAntiAlias = true; color = Color.rgb(0, 229, 255)
    }
    private val futurePath = Path()

    // ─── [지침 4] Render-time Transform: AI(416) → Display ───────────────────
    // displayX = ai416X * renderScale + renderOffsetX
    // YoloFrameProcessor 가 매 프레임 setRenderParams() 로 갱신.
    @Volatile private var renderScale   = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f

    /**
     * AI(416) → Display 변환 파라미터 설정.
     * @param scale    dispScale / letterboxScale  (AI px → display px)
     * @param offsetX  -padLeft * scale + startX
     * @param offsetY  -padTop  * scale + startY
     */
    fun setRenderParams(scale: Float, offsetX: Float, offsetY: Float) {
        renderScale   = scale
        renderOffsetX = offsetX
        renderOffsetY = offsetY
    }

    // ─── State ───────────────────────────────────────────────────────────────
    private val displayBuffer = ArrayList<TrackedObject>(50)
    private var debugRaw = 0
    private var debugNms = 0

    fun updateResults(objects: List<TrackedObject>) {
        displayBuffer.clear()
        displayBuffer.addAll(objects)
        postInvalidate()
    }

    fun setDebugInfo(raw: Int, nms: Int) {
        debugRaw = raw
        debugNms = nms
    }

    // ─── Drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawText("Raw(>0.3): $debugRaw | NMS: $debugNms", 24f, 80f, debugTextPaint)

        for (obj in displayBuffer) {
            val color = TRACK_COLORS[obj.trackId.and(0x7FFF_FFFF) % TRACK_COLORS.size]
            // [지침 5] Ego-Motion 보정 미래 궤적 — canvas.rotate() 스코프 밖에서 직접 렌더
            drawFutureTrajectory(canvas, obj)
            drawTrajectory(canvas, obj, color)
            drawBoundingBox(canvas, obj, color)
            drawLabel(canvas, obj, color)
        }
    }

    // ─── Layer 0: Ego-Motion 보정 미래 궤적 (두꺼운 시안, canvas.rotate 없음) ──

    // [지침 5] futurePoints 는 AI(416) 공간 — AI→display 변환만 적용.
    //          canvas.rotate() 스코프 밖에서 직접 렌더링.
    private fun drawFutureTrajectory(canvas: Canvas, obj: TrackedObject) {
        if (!obj.isMoving) return
        val sc = renderScale; val ox = renderOffsetX; val oy = renderOffsetY
        val startX = obj.detection.rect.centerX() * sc + ox
        val startY = obj.detection.rect.centerY() * sc + oy

        futurePath.reset()
        futurePath.moveTo(startX, startY)
        for (k in 0 until 20) {
            val fx = obj.futurePoints[k].x * sc + ox
            val fy = obj.futurePoints[k].y * sc + oy
            futurePath.lineTo(fx, fy)
        }

        // alpha 200 고정 (항상 잘 보임)
        futureTrajPaint.alpha = 200
        canvas.drawPath(futurePath, futureTrajPaint)
    }

    // ─── Layer 1-3: 궤적 (AI416 → display 인라인 변환) ───────────────────────

    private fun drawTrajectory(canvas: Canvas, obj: TrackedObject, color: Int) {
        if (!obj.isMoving) return
        val traj = obj.predictedTrajectory
        val n = traj.size
        if (n < 2) return

        val vx = obj.velocityX; val vy = obj.velocityY
        val speed = sqrt(vx * vx + vy * vy)
        if (speed < 1.5f) return

        // AI416 → display 변환 상수 로컬 캐시
        val sc = renderScale; val ox = renderOffsetX; val oy = renderOffsetY

        val cx = obj.detection.rect.centerX() * sc + ox
        val cy = obj.detection.rect.centerY() * sc + oy

        // Pass 1: 전체 Bezier 점선
        trajPath.reset()
        trajPath.moveTo(cx, cy)
        for (i in 0 until n) {
            val qx = traj[i].x * sc + ox
            val qy = traj[i].y * sc + oy
            val ex = if (i == n - 1) qx else (traj[i].x + traj[i + 1].x) / 2f * sc + ox
            val ey = if (i == n - 1) qy else (traj[i].y + traj[i + 1].y) / 2f * sc + oy
            trajPath.quadTo(qx, qy, ex, ey)
        }
        trajDashPaint.color = color; trajDashPaint.alpha = 55
        canvas.drawPath(trajPath, trajDashPaint)

        // Pass 2: 세그먼트별 alpha 감쇠
        trajSolidPaint.color = color
        var sPrevX = cx; var sPrevY = cy
        for (i in 0 until n) {
            val alpha = (240 * (1f - i.toFloat() / n)).toInt().coerceAtLeast(0)
            if (alpha < 8) break
            trajSolidPaint.alpha = alpha
            val qx = traj[i].x * sc + ox
            val qy = traj[i].y * sc + oy
            val ex = if (i == n - 1) qx else (traj[i].x + traj[i + 1].x) / 2f * sc + ox
            val ey = if (i == n - 1) qy else (traj[i].y + traj[i + 1].y) / 2f * sc + oy
            segPath.reset()
            segPath.moveTo(sPrevX, sPrevY)
            segPath.quadTo(qx, qy, ex, ey)
            canvas.drawPath(segPath, trajSolidPaint)
            sPrevX = ex; sPrevY = ey
        }

        // Pass 3: 진행방향 화살표 (display 좌표로 변환 후 전달)
        val arrowIdx = (n / 3).coerceAtLeast(1).coerceAtMost(n - 1)
        drawArrow(canvas, traj[arrowIdx].x * sc + ox, traj[arrowIdx].y * sc + oy, vx, vy, color)
    }

    private fun drawArrow(canvas: Canvas, tipX: Float, tipY: Float, vx: Float, vy: Float, color: Int) {
        val speed = sqrt(vx * vx + vy * vy)
        if (speed < 0.5f) return
        val nx = vx / speed; val ny = vy / speed
        val px = -ny;        val py = nx
        val len = 20f; val half = 8f
        val bx = tipX - nx * len; val by = tipY - ny * len
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(bx + px * half, by + py * half)
        arrowPath.lineTo(bx - px * half, by - py * half)
        arrowPath.close()
        arrowPaint.color = color; arrowPaint.alpha = 210
        canvas.drawPath(arrowPath, arrowPaint)
    }

    // ─── Layer 4: 바운딩 박스 ────────────────────────────────────────────────

    private fun drawBoundingBox(canvas: Canvas, obj: TrackedObject, color: Int) {
        // AI416 → display 변환 (Clamp 없음)
        val sc = renderScale; val ox = renderOffsetX; val oy = renderOffsetY
        val src = obj.detection.rect
        dispRect.set(src.left * sc + ox, src.top * sc + oy, src.right * sc + ox, src.bottom * sc + oy)
        val r = dispRect
        val isCoasting = obj.invisibleCount > 0

        if (isCoasting) {
            coastingBorderPaint.color = color; coastingBorderPaint.alpha = 128
            canvas.drawRect(r, coastingBorderPaint)
        } else {
            boxFillPaint.color = (color and 0x00FF_FFFF) or 0x18000000
            canvas.drawRect(r, boxFillPaint)
            boxStrokePaint.color = color; boxStrokePaint.alpha = 110
            canvas.drawRect(r, boxStrokePaint)
        }

        val cl = r.width() * 0.20f; val ct = r.height() * 0.20f
        cornerPaint.color = color; cornerPaint.alpha = if (isCoasting) 128 else 255
        cornerPath.reset()
        cornerPath.moveTo(r.left,        r.top + ct);     cornerPath.lineTo(r.left,  r.top);   cornerPath.lineTo(r.left + cl,  r.top)
        cornerPath.moveTo(r.right - cl,  r.top);          cornerPath.lineTo(r.right, r.top);   cornerPath.lineTo(r.right,      r.top + ct)
        cornerPath.moveTo(r.left,        r.bottom - ct);  cornerPath.lineTo(r.left,  r.bottom);cornerPath.lineTo(r.left + cl,  r.bottom)
        cornerPath.moveTo(r.right - cl,  r.bottom);       cornerPath.lineTo(r.right, r.bottom);cornerPath.lineTo(r.right,      r.bottom - ct)
        canvas.drawPath(cornerPath, cornerPaint)
    }

    // ─── Layer 5: 레이블 ─────────────────────────────────────────────────────
    // [지침 4] 배경+텍스트 전체를 canvas.rotate(-dr, anchorX, anchorY) 로 회전.
    //
    // 사용자 시야 기준 TL 코너 (screen 좌표):
    //   dr=  0: (left,  top)     dr= 90: (left,  bottom)
    //   dr=180: (right, bottom)  dr=270: (right, top)
    //
    // screen → user-space 변환 (검증완료):
    //   dr=  0: uax=ax,    uay=ay
    //   dr= 90: uax=vh-ay, uay=ax
    //   dr=180: uax=vw-ax, uay=vh-ay
    //   dr=270: uax=ay,    uay=vw-ax
    //
    // canvas.rotate(-dr) 후 local+x=user RIGHT, local+y=user DOWN.

    private fun drawLabel(canvas: Canvas, obj: TrackedObject, color: Int) {
        val sc = renderScale; val ox = renderOffsetX; val oy = renderOffsetY
        val src = obj.detection.rect
        dispRect.set(src.left * sc + ox, src.top * sc + oy, src.right * sc + ox, src.bottom * sc + oy)
        val r = dispRect

        val label = "#${obj.trackId}  ${CocoLabels.get(obj.detection.classId)}" +
                    "  ${"%.0f".format(obj.detection.confidence * 100)}%"
        val tw = labelTextPaint.measureText(label)
        val th = labelTextPaint.textSize
        val ph = 6f; val pv = 5f
        val labelW = tw + 2f * ph
        val labelH = th + 2f * pv
        val vw = width.toFloat(); val vh = height.toFloat()
        val dr = TrackerConfig.deviceRotation

        val usw = if (dr == 90 || dr == 270) vh else vw
        val ush = if (dr == 90 || dr == 270) vw else vh

        fun toUax(ax: Float, ay: Float) = when (dr) { 90 -> vh - ay; 180 -> vw - ax; 270 -> ay; else -> ax }
        fun toUay(ax: Float, ay: Float) = when (dr) { 90 -> ax;      180 -> vh - ay; 270 -> vw - ax; else -> ay }

        fun fitsAbove(uax: Float, uay: Float) =
            uax >= 0f && uax + labelW <= usw && uay - labelH >= 0f
        fun fitsBelow(uax: Float, uay: Float) =
            uax >= 0f && uax + labelW <= usw && uay + labelH <= ush

        // 4개 anchor 후보 (screen 좌표): 사용자 시야 TL → TR → BL → BR
        val axList = floatArrayOf(
            if (dr == 180 || dr == 270) r.right else r.left,  // TL
            if (dr ==  90 || dr == 180) r.left  else r.right, // TR
            if (dr ==  90 || dr == 180) r.right else r.left,  // BL
            if (dr == 180 || dr == 270) r.left  else r.right  // BR
        )
        val ayList = floatArrayOf(
            if (dr ==  90 || dr == 180) r.bottom else r.top,  // TL
            if (dr == 180 || dr == 270) r.bottom else r.top,  // TR
            if (dr == 180 || dr == 270) r.top    else r.bottom, // BL
            if (dr ==  90 || dr == 180) r.top    else r.bottom  // BR
        )

        var chosenAx = axList[0]; var chosenAy = ayList[0]; var chosenAbove = true
        var placed = false
        for (i in 0 until 4) {
            val uax = toUax(axList[i], ayList[i])
            val uay = toUay(axList[i], ayList[i])
            if (fitsAbove(uax, uay)) {
                chosenAx = axList[i]; chosenAy = ayList[i]; chosenAbove = true; placed = true; break
            } else if (fitsBelow(uax, uay)) {
                chosenAx = axList[i]; chosenAy = ayList[i]; chosenAbove = false; placed = true; break
            }
        }
        if (!placed) { chosenAx = axList[0]; chosenAy = ayList[0]; chosenAbove = true }

        val localBgY = if (chosenAbove) -labelH else 0f

        canvas.save()
        canvas.rotate(-dr.toFloat(), chosenAx, chosenAy)
        labelBgRect.set(chosenAx, chosenAy + localBgY, chosenAx + labelW, chosenAy + localBgY + labelH)
        canvas.drawRoundRect(labelBgRect, 8f, 8f, labelBgPaint)
        labelTextPaint.color = color
        canvas.drawText(label, chosenAx + ph, chosenAy + localBgY + th + pv, labelTextPaint)
        canvas.restore()
    }
}
