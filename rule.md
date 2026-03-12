## 1. Performance & Memory Strict Constraints (Zero-GC Policy)
- **[CRITICAL] NO Object Allocation in loops:** 안드로이드 가비지 컬렉터(GC) 스파이크와 프레임 드랍을 막기 위해, 매 프레임 실행되는 `ImageAnalysis.Analyzer.analyze()` 콜백이나 커스텀 뷰의 `onDraw()` 내부에서 절대 `new` 키워드나 새로운 객체(`FloatArray()`, `RectF()`, `Paint()`, `Path()`, `ByteBuffer.allocate()` 등)를 생성하지 마라.
- **Buffer Reuse:** AI 입력용 `ByteBuffer`, 출력 파싱용 `FloatArray`, UI 렌더링용 객체 등은 클래스 초기화 시점에 **단 한 번만 메모리에 할당(Pre-allocation)**하고, 매 프레임마다 데이터만 덮어쓰기(Overwrite/Reuse) 방식으로 사용하라.

## 2. CameraX Strict Setup
- **Backpressure:** 프레임 밀림 현상을 막기 위해 반드시 `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`를 사용하라.
- **Format:** YUV -> RGB 변환 시 발생하는 CPU 오버헤드를 없애기 위해 반드시 `OUTPUT_IMAGE_FORMAT_RGBA_8888`을 설정하라.
- **Memory Leak Prevention:** 프레임 처리가 끝나거나 에러가 발생해도 반드시 `finally` 블록에서 `imageProxy.close()`를 호출하라.

## 3. Threading Model
- Main Thread: 오직 UI 렌더링(Canvas Draw)만 담당한다.
- Background Thread: CameraX 프레임 캡처, `ByteBuffer` 전처리, TFLite 추론, Tracking 및 Prediction 연산은 모두 별도의 스레드(Coroutines `Dispatchers.Default`)에서 처리한다.