# TFLite / LiteRT 클래스 보존
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-dontwarn com.google.ai.edge.litert.**

# CameraX
-keep class androidx.camera.** { *; }

# 데이터 모델 (직렬화 없음 — 안전하지만 명시적으로 보존)
-keep class com.intellitimer.vision.model.** { *; }
