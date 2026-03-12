package com.intellitimer.vision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 우상단 파이프라인 HUD 오버레이.
 *
 * 표시 항목:
 *  - 추론 Delegate (NNAPI / GPU / CPU)
 *  - FPS
 *  - 전처리 / 추론 지연(ms)
 *  - 탐지 수
 *  - 프로세스 CPU 점유율(%)
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var stats = PipelineStats()

    // ─── Paint ─────────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply {
        color = Color.argb(175, 8, 8, 20)
        isAntiAlias = false
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val accentPaint = Paint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
        textSize = 28f
    }
    private val bgRect = RectF()

    // ─── Public API ────────────────────────────────────────────────────────────

    fun update(s: PipelineStats) {
        stats = s
        postInvalidate()
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val pad  = 14f
        val lineH = textPaint.textSize + 6f
        val lines = buildLines()
        val maxW  = lines.maxOf { textPaint.measureText(it.second) } + pad * 2

        val boxW = maxW
        val boxH = lines.size * lineH + pad * 2
        val bx   = width - boxW - 16f
        val by   = 16f

        bgRect.set(bx, by, bx + boxW, by + boxH)
        canvas.drawRoundRect(bgRect, 12f, 12f, bgPaint)

        var y = by + pad + textPaint.textSize
        for ((color, text) in lines) {
            accentPaint.color = color
            canvas.drawText(text, bx + pad, y, accentPaint)
            y += lineH
        }
    }

    private fun buildLines(): List<Pair<Int, String>> {
        val delegateColor = if (stats.delegateName == "GPU") Color.rgb(57, 255, 115)
                            else Color.rgb(255, 214, 0)
        val fpsColor = if (stats.fps >= 20f) Color.rgb(57, 255, 115)
                       else if (stats.fps >= 10f) Color.rgb(255, 214, 0)
                       else Color.rgb(255, 85, 85)

        return listOf(
            delegateColor to "▶ ${stats.delegateName}",
            fpsColor      to "FPS  ${stats.fps.toInt()}",
            Color.WHITE   to "Pre: ${stats.preprocessMs}ms | AI(${stats.delegateName}): ${stats.inferenceMs}ms | NMS(CPU): ${stats.nmsMs}ms",
            Color.WHITE   to "Det  ${stats.detectionCount}"
        )
    }
}
