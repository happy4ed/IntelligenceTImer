package com.intellitimer.vision.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.intellitimer.vision.model.LetterboxParams
import com.intellitimer.vision.tracking.TrackerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 2: RGBA_8888 ImageProxy → 회전 보정 → Letterbox 640×640 → Float32 ByteBuffer.
 *
 * ┌────────────────────────────────────────────────────────────────────┐
 * │  FPS 최적화 — 두 단계 분리                                           │
 * │  copyFromProxy()     : analysisExecutor 스레드. 픽셀 복사만. ~1-5ms  │
 * │  processFromBitmap() : Dispatchers.Default. letterbox+정규화. ~10ms │
 * │  → imageProxy.close() 를 copyFromProxy() 직후 호출 가능              │
 * └────────────────────────────────────────────────────────────────────┘
 *
 * Rule.md Zero-GC:
 *   모든 버퍼/비트맵/캔버스는 class init 에서 단 한 번 할당.
 *   호출당 신규 객체 생성 = 0.
 */
class ImagePreprocessor {

    // ─── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val INPUT_SIZE = 416
        private const val BYTES_PER_FLOAT = 4
        private const val CHANNELS = 3
        private const val INV_255 = 1f / 255f
    }

    // ─── Pre-allocated Fixed-size Buffers (Zero-GC) ─────────────────────────────

    val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * CHANNELS * BYTES_PER_FLOAT)
        .apply { order(ByteOrder.nativeOrder()) }

    val letterboxParams = LetterboxParams()

    private val letterboxBitmap: Bitmap =
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)

    private val letterboxCanvas: Canvas = Canvas(letterboxBitmap)
    private val scalePaint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect: Rect  = Rect()
    private val dstRectF: RectF = RectF()

    private val pixelArray: IntArray  = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val normFloats: FloatArray = FloatArray(INPUT_SIZE * INPUT_SIZE * CHANNELS)

    /** outputBuffer FloatBuffer 뷰 캐시 — asFloatBuffer() 매번 객체 생성 방지. */
    private val outputFloatBuf = outputBuffer.asFloatBuffer()

    // ─── Lazy-initialized (camera resolution dependent) ─────────────────────────
    private var sourceBitmap: Bitmap? = null
    private var srcPixelArray: IntArray? = null
    private var lastWidth  = 0
    private var lastHeight = 0

    /** copyFromProxy() 에서 저장한 메타데이터 — processFromBitmap() 에서 참조. */
    private var pendingRotation = 0

    // ─── Public API — Stage 1: analysisExecutor 스레드 ─────────────────────────

    /**
     * Stage 1: ImageProxy 픽셀을 sourceBitmap 에 복사하고 회전 정보를 저장.
     * 완료 즉시 imageProxy.close() 가 CameraManager 에서 호출됨.
     *
     * @return false 이면 해당 프레임 스킵
     */
    fun copyFromProxy(imageProxy: ImageProxy): Boolean {
        val rawW = imageProxy.width
        val rawH = imageProxy.height
        if (rawW <= 0 || rawH <= 0) return false

        ensureSourceBuffers(rawW, rawH)
        copyToSourceBitmap(imageProxy)
        pendingRotation = imageProxy.imageInfo.rotationDegrees
        return true
    }

    // ─── Public API — Stage 2: Dispatchers.Default 코루틴 ──────────────────────

    /**
     * Stage 2: sourceBitmap → 회전 보정 letterbox → Float32 정규화 → outputBuffer.
     * copyFromProxy() 완료 후 Dispatchers.Default 코루틴에서 호출.
     */
    fun processFromBitmap(): Boolean {
        val rawW = lastWidth
        val rawH = lastHeight
        if (rawW <= 0 || rawH <= 0) return false

        val rotation = pendingRotation
        val effW = if (rotation == 90 || rotation == 270) rawH else rawW
        val effH = if (rotation == 90 || rotation == 270) rawW else rawH

        val scale   = minOf(INPUT_SIZE.toFloat() / effW, INPUT_SIZE.toFloat() / effH)
        val scaledW = (effW * scale).toInt()
        val scaledH = (effH * scale).toInt()
        val padLeft = (INPUT_SIZE - scaledW) * 0.5f
        val padTop  = (INPUT_SIZE - scaledH) * 0.5f
        letterboxParams.update(scale, padLeft, padTop, effW, effH)

        letterboxCanvas.drawColor(Color.BLACK)

        // [지침 2] 가상 짐벌: 416 중심(208,208) 기준 deviceRotation 만큼 정방향 회전
        // → AI는 중력 기준 똑바로 선 피사체를 보게 됨
        letterboxCanvas.save()
        letterboxCanvas.rotate(TrackerConfig.deviceRotation.toFloat(), INPUT_SIZE / 2f, INPUT_SIZE / 2f)

        letterboxCanvas.save()
        letterboxCanvas.translate(padLeft + scaledW * 0.5f, padTop + scaledH * 0.5f)
        letterboxCanvas.rotate(rotation.toFloat())

        val dstW = if (rotation == 90 || rotation == 270) scaledH.toFloat() else scaledW.toFloat()
        val dstH = if (rotation == 90 || rotation == 270) scaledW.toFloat() else scaledH.toFloat()

        srcRect.set(0, 0, rawW, rawH)
        dstRectF.set(-dstW * 0.5f, -dstH * 0.5f, dstW * 0.5f, dstH * 0.5f)
        letterboxCanvas.drawBitmap(sourceBitmap!!, srcRect, dstRectF, scalePaint)
        letterboxCanvas.restore()   // sensor rotation restore

        letterboxCanvas.restore()   // gimbal rotation restore

        // Bitmap → FloatArray (단순 배열 쓰기) → outputBuffer bulk 복사
        letterboxBitmap.getPixels(pixelArray, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        var fi = 0
        for (pixel in pixelArray) {
            normFloats[fi++] = ((pixel shr 16) and 0xFF) * INV_255
            normFloats[fi++] = ((pixel shr  8) and 0xFF) * INV_255
            normFloats[fi++] = ( pixel         and 0xFF) * INV_255
        }
        outputFloatBuf.rewind()
        outputFloatBuf.put(normFloats)
        outputBuffer.rewind()
        return true
    }

    fun release() {
        if (!letterboxBitmap.isRecycled) letterboxBitmap.recycle()
        sourceBitmap?.let { if (!it.isRecycled) it.recycle() }
        sourceBitmap = null
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private fun ensureSourceBuffers(width: Int, height: Int) {
        if (width == lastWidth && height == lastHeight) return
        sourceBitmap?.let { if (!it.isRecycled) it.recycle() }
        sourceBitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        srcPixelArray = IntArray(width * height)
        lastWidth  = width
        lastHeight = height
    }

    private fun copyToSourceBitmap(imageProxy: ImageProxy) {
        val plane     = imageProxy.planes[0]
        val buffer    = plane.buffer
        val rowStride = plane.rowStride
        val width     = lastWidth
        val height    = lastHeight

        buffer.rewind()

        if (rowStride == width * 4) {
            sourceBitmap!!.copyPixelsFromBuffer(buffer)
        } else {
            val arr = srcPixelArray!!
            for (y in 0 until height) {
                val rowBase = y * rowStride
                val arrBase = y * width
                for (x in 0 until width) {
                    val p = rowBase + x * 4
                    val r = buffer.get(p    ).toInt() and 0xFF
                    val g = buffer.get(p + 1).toInt() and 0xFF
                    val b = buffer.get(p + 2).toInt() and 0xFF
                    val a = buffer.get(p + 3).toInt() and 0xFF
                    arr[arrBase + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            sourceBitmap!!.setPixels(arr, 0, width, 0, 0, width, height)
        }
    }
}
