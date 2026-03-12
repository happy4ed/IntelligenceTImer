package com.intellitimer.vision.inference

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.intellitimer.vision.ui.LogCollector
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "YoloInferenceEngine"

/**
 * Phase 3: YOLO11n TFLite 추론 엔진.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  Delegate 우선순위 (자동 폴백)                                     │
 * │  1순위: GPU  — Adreno GPU FP16 (목표 5-10ms)                      │
 * │  2순위: CPU  — XNNPACK 4-thread 폴백                               │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * 출력 shape 동적 감지:
 *   [1, 84, N] (transposed) 또는 [1, N, 84] 자동 판별
 *   N = (imgsz/8)² + (imgsz/16)² + (imgsz/32)²
 *     = 3549 for 416×416
 *     = 8400 for 640×640
 */
class YoloInferenceEngine(context: Context) : Closeable {

    companion object {
        const val MODEL_FILENAME  = "yolo11n.tflite"
        const val NUM_CHANNELS    = 84   // 4(box) + 80(classes)
        private const val INPUT_H = ImagePreprocessor.INPUT_SIZE   // 640
        private const val INPUT_W = ImagePreprocessor.INPUT_SIZE   // 640
        private const val INPUT_CH    = 3
        private const val BYTES_PER_FLOAT = 4
        private const val NUM_THREADS = 4
        val EXPECTED_INPUT_BYTES = INPUT_H * INPUT_W * INPUT_CH * BYTES_PER_FLOAT
    }

    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter

    var outputTensorIndex: Int = 0
        private set

    /** 출력 텐서의 실제 shape — buildInterpreter 후 설정. */
    var outputShape: IntArray = intArrayOf()
        private set

    /** 앵커 수 — outputShape 에서 자동 계산. */
    var numAnchors: Int = 0
        private set

    /** 출력 버퍼 — numAnchors 확정 후 할당. */
    var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4)
        private set

    init {
        interpreter = buildInterpreter(context)
        LogCollector.i(TAG,
            "YoloInferenceEngine ready — delegate=$delegateName " +
            "outputTensor=$outputTensorIndex shape=${outputShape.toList()} numAnchors=$numAnchors")
    }

    fun run(inputBuffer: ByteBuffer): Boolean {
        if (inputBuffer.capacity() != EXPECTED_INPUT_BYTES) {
            LogCollector.e(TAG, "입력 버퍼 크기 불일치: ${inputBuffer.capacity()} != $EXPECTED_INPUT_BYTES")
            return false
        }
        if (inputBuffer.order() != ByteOrder.nativeOrder()) {
            LogCollector.e(TAG, "입력 버퍼 ByteOrder 불일치 — nativeOrder 필수")
            return false
        }
        return try {
            inputBuffer.rewind()
            outputBuffer.rewind()
            val inputs  = arrayOf<Any>(inputBuffer)
            val outputs = hashMapOf<Int, Any>(outputTensorIndex to outputBuffer)
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
            outputBuffer.rewind()
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "Inference failed: ${e.message}")
            Log.e(TAG, "Inference failed", e)
            false
        }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
        Log.d(TAG, "YoloInferenceEngine closed")
    }

    private fun buildInterpreter(context: Context): Interpreter {
        val modelBuffer = loadModelFile(context)

        LogCollector.i(TAG, "[VisionAI] GPU Delegate 초기화 시도 (Adreno FP16)")
        Log.i(TAG, "[VisionAI] GPU Delegate 초기화 시도")

        try {
            @Suppress("DEPRECATION")
            val gpuOptions = GpuDelegate.Options().apply {
                isPrecisionLossAllowed = true
                inferencePreference    = GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
            }
            @Suppress("DEPRECATION")
            val delegate = GpuDelegate(gpuOptions)
            val interp = Interpreter(modelBuffer, Interpreter.Options().apply {
                addDelegate(delegate)
                numThreads = 1
            })
            setupOutputShape(interp, "GPU")
            gpuDelegate = delegate
            LogCollector.i(TAG, "[VisionAI] GPU Delegate 활성화 성공 (FP16)")
            Log.i(TAG, "[VisionAI] GPU Delegate 활성화 성공")
            return interp
        } catch (e: Exception) {
            gpuDelegate?.close()
            gpuDelegate = null
            LogCollector.w(TAG,
                "[VisionAI] GPU 실패 [${e.javaClass.simpleName}]: ${e.message} — CPU 폴백")
            Log.w(TAG, "[VisionAI] GPU 실패 — CPU 폴백", e)
        }

        LogCollector.i(TAG, "[VisionAI] CPU XNNPACK ($NUM_THREADS threads) 초기화")
        val cpuInterp = Interpreter(
            modelBuffer,
            Interpreter.Options().apply { numThreads = NUM_THREADS }
        )
        setupOutputShape(cpuInterp, "CPU")
        return cpuInterp
    }

    /**
     * 출력 텐서 정보 기록 + shape/numAnchors/outputBuffer 설정.
     *
     * 지원 shape:
     *   [1, 84, N]  — transposed (ch-major)
     *   [1, N, 84]  — anchor-major
     */
    private fun setupOutputShape(interp: Interpreter, label: String) {
        val numIn  = interp.inputTensorCount
        val numOut = interp.outputTensorCount
        LogCollector.i(TAG, "[$label] $numIn input(s), $numOut output(s)")

        for (i in 0 until numIn) {
            val t = interp.getInputTensor(i)
            LogCollector.i(TAG, "  input[$i]: shape=${t.shape().toList()} dtype=${t.dataType()}")
        }

        // 출력 텐서 스캔 — NUM_CHANNELS(84)를 포함하는 텐서 선택
        var foundIdx = 0
        for (i in 0 until numOut) {
            val t     = interp.getOutputTensor(i)
            val shape = t.shape().toList()
            LogCollector.i(TAG, "  output[$i]: shape=$shape dtype=${t.dataType()}")
            if (shape.contains(NUM_CHANNELS)) foundIdx = i
        }
        outputTensorIndex = foundIdx

        val rawShape = interp.getOutputTensor(outputTensorIndex).shape()
        outputShape = rawShape

        // numAnchors: [1,84,N] → N=shape[2], [1,N,84] → N=shape[1]
        numAnchors = when {
            rawShape.size >= 3 && rawShape[1] == NUM_CHANNELS -> rawShape[2]
            rawShape.size >= 3 && rawShape[2] == NUM_CHANNELS -> rawShape[1]
            else -> rawShape.last()
        }

        // 실제 크기로 outputBuffer 할당
        outputBuffer = ByteBuffer
            .allocateDirect(NUM_CHANNELS * numAnchors * BYTES_PER_FLOAT)
            .apply { order(ByteOrder.nativeOrder()) }

        LogCollector.i(TAG,
            "[$label] outputTensor=$outputTensorIndex shape=${rawShape.toList()} numAnchors=$numAnchors")
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(MODEL_FILENAME)
        return FileInputStream(afd.fileDescriptor).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    val delegateName: String get() = if (gpuDelegate != null) "GPU" else "CPU"
}
