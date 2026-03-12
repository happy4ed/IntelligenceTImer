# IntelliTimer — 코드 아키텍처 UML

> Mermaid 다이어그램. GitHub / VSCode Markdown Preview Enhanced 에서 렌더링 가능.

---

## 1. 전체 패키지 구조 (Package Diagram)

```mermaid
graph TD
    subgraph APP["com.intellitimer.vision"]
        MA[MainActivity]

        subgraph CAM["camera/"]
            FP_IF["«interface»\nFrameProcessor"]
            CM[CameraManager]
        end

        subgraph INF["inference/"]
            YFP[YoloFrameProcessor]
            IP[ImagePreprocessor]
            YIE[YoloInferenceEngine]
            YPP[YoloPostProcessor]
        end

        subgraph TRK["tracking/"]
            BT[ByteTracker]
            TC["«object»\nTrackerConfig"]
        end

        subgraph MDL["model/"]
            DET[Detection]
            TO[TrackedObject]
            LBP[LetterboxParams]
            AS["«object»\nAppSettings"]
            CL["«object»\nCocoLabels"]
        end

        subgraph UI["ui/"]
            OV[OverlayView]
            HV[HudView]
            SBS[SettingsBottomSheet]
            PS[PipelineStats]
            LC["«object»\nLogCollector"]
            LVA[LogViewerActivity]
        end
    end

    MA --> CM
    MA --> YFP
    MA --> OV
    MA --> HV
    MA --> SBS
    MA --> TC

    CM --> FP_IF
    YFP -.->|implements| FP_IF

    YFP --> IP
    YFP --> YIE
    YFP --> YPP
    YFP --> BT
    YFP --> OV
    YFP --> PS

    IP --> TC
    YPP --> TC
    OV --> TC

    BT --> TO
    BT --> DET
    IP --> LBP
    YPP --> DET
    TO --> DET

    SBS --> AS
    SBS --> TC
    SBS --> CL
```

---

## 2. 클래스 상세 (Class Diagram)

```mermaid
classDiagram
    %% ── Interfaces ────────────────────────────────────────────────────────────
    class FrameProcessor {
        <<interface>>
        +copyFrame(imageProxy: ImageProxy) Boolean
        +runPipeline() List~TrackedObject~
    }

    %% ── Activity / Entry Point ────────────────────────────────────────────────
    class MainActivity {
        -binding: ActivityMainBinding
        -cameraManager: CameraManager
        -frameProcessor: YoloFrameProcessor
        -mainScope: CoroutineScope
        -orientationListener: OrientationEventListener
        +onCreate()
        +onResume()
        +onPause()
        +onDestroy()
        -startVisionPipeline()
    }

    %% ── Camera Layer ──────────────────────────────────────────────────────────
    class CameraManager {
        -context: Context
        -lifecycleOwner: LifecycleOwner
        -previewView: PreviewView
        -frameProcessor: FrameProcessor
        -onResults: (List~TrackedObject~) → Unit
        -analysisExecutor: ExecutorService
        +start()
        +stop()
    }

    %% ── Inference Pipeline ────────────────────────────────────────────────────
    class YoloFrameProcessor {
        -preprocessor: ImagePreprocessor
        -inferenceEngine: YoloInferenceEngine (lazy)
        -postProcessor: YoloPostProcessor
        -tracker: ByteTracker
        -gpuThread: CoroutineDispatcher
        -overlayView: View?
        +var onStats: (PipelineStats) → Unit?
        +copyFrame(imageProxy) Boolean
        +runPipeline() List~TrackedObject~
        +release()
    }

    class ImagePreprocessor {
        +outputBuffer: ByteBuffer
        +letterboxParams: LetterboxParams
        -letterboxBitmap: Bitmap
        -letterboxCanvas: Canvas
        +copyFromProxy(imageProxy) Boolean
        +processFromBitmap() Boolean
        +release()
    }

    class YoloInferenceEngine {
        +delegateName: String
        -interpreter: Interpreter
        +run(inputBuffer: ByteBuffer) Boolean
        +close()
    }

    class YoloPostProcessor {
        +lastRawCount: Int
        +lastNmsCount: Int
        -rotMatrix: Matrix
        -candXmin/Xmax/Ymin/Ymax: FloatArray
        -candConf/ClassId: FloatArray/IntArray
        +decode(engine: YoloInferenceEngine) List~Detection~
    }

    %% ── Tracking ──────────────────────────────────────────────────────────────
    class ByteTracker {
        -trackPool: Array~TrackedObject~
        -slotActive: BooleanArray
        -nextId: Int
        +update(detections: List~Detection~) List~TrackedObject~
    }

    class TrackerConfig {
        <<object>>
        +matchRadius: Float
        +maxCoastFrames: Int
        +velocityEMA: Float
        +deadZone: Float
        +deviceRotation: Int
        +classFilter: BooleanArray[80]
        +loadClassFilter(context)
        +saveClassFilter(context)
    }

    %% ── Model / Data ──────────────────────────────────────────────────────────
    class Detection {
        +var classId: Int
        +var confidence: Float
        +val rect: RectF
    }

    class TrackedObject {
        +var trackId: Int
        +val detection: Detection
        +var velocityX: Float
        +var velocityY: Float
        +val predictedTrajectory: MutableList~PointF~
        +var invisibleCount: Int
        +var trueVx: Float
        +var trueVy: Float
        +var isMoving: Boolean
        +val futurePoints: Array~PointF~[20]
    }

    class LetterboxParams {
        +var scale: Float
        +var padLeft: Float
        +var padTop: Float
        +var effCamW: Int
        +var effCamH: Int
        +update(...)
    }

    class AppSettings {
        <<object>>
        +confidenceThreshold: Float
        +confirmFrames: Int
        +maxDetections: Int
        +maxMissing: Int
        +load(context)
        +save(context)
    }

    class CocoLabels {
        <<object>>
        -NAMES: Array~String~[80]
        +get(classId: Int) String
    }

    class PipelineStats {
        +delegateName: String
        +fps: Float
        +preprocessMs: Long
        +inferenceMs: Long
        +nmsMs: Long
        +detectionCount: Int
    }

    %% ── UI ───────────────────────────────────────────────────────────────────
    class OverlayView {
        -renderScale: Float
        -renderOffsetX: Float
        -renderOffsetY: Float
        -displayBuffer: ArrayList~TrackedObject~
        +setRenderParams(scale, offsetX, offsetY)
        +updateResults(objects)
        +setDebugInfo(raw, nms)
        -drawFutureTrajectory(canvas, obj)
        -drawTrajectory(canvas, obj, color)
        -drawBoundingBox(canvas, obj, color)
        -drawLabel(canvas, obj, color)
    }

    class HudView {
        -stats: PipelineStats
        +updateStats(stats: PipelineStats)
    }

    class SettingsBottomSheet {
        -checkBoxes: Array~CheckBox?~[80]
        +onViewCreated(view, savedInstanceState)
        -setupParamsPanel(view)
        -setupFiltersPanel(view)
        -selectTab(isParams: Boolean)
    }

    %% ── Relationships ────────────────────────────────────────────────────────
    MainActivity --> CameraManager
    MainActivity --> YoloFrameProcessor
    MainActivity --> OverlayView
    MainActivity --> HudView
    MainActivity --> SettingsBottomSheet
    MainActivity ..> TrackerConfig

    CameraManager --> FrameProcessor
    YoloFrameProcessor ..|> FrameProcessor

    YoloFrameProcessor --> ImagePreprocessor
    YoloFrameProcessor --> YoloInferenceEngine
    YoloFrameProcessor --> YoloPostProcessor
    YoloFrameProcessor --> ByteTracker
    YoloFrameProcessor ..> OverlayView
    YoloFrameProcessor ..> PipelineStats

    ImagePreprocessor --> LetterboxParams
    ImagePreprocessor ..> TrackerConfig

    YoloPostProcessor ..> TrackerConfig
    YoloPostProcessor ..> Detection

    ByteTracker --> TrackedObject
    ByteTracker ..> Detection
    ByteTracker ..> AppSettings
    ByteTracker ..> TrackerConfig

    TrackedObject *-- Detection
    TrackedObject *-- "20" PointF

    OverlayView ..> TrackerConfig
    OverlayView ..> TrackedObject

    SettingsBottomSheet ..> AppSettings
    SettingsBottomSheet ..> TrackerConfig
    SettingsBottomSheet ..> CocoLabels
```

---

## 3. 비전 파이프라인 시퀀스 (Sequence Diagram)

```mermaid
sequenceDiagram
    participant CAM  as CameraX
    participant AE   as analysisExecutor<br/>(단일 스레드)
    participant DEF  as Dispatchers.Default<br/>(코루틴)
    participant GPU  as gpuThread<br/>(TFLite 고정 스레드)
    participant UI   as Main Thread

    CAM->>AE: onImageAnalyzed(imageProxy)
    AE->>AE: YoloFrameProcessor.copyFrame()<br/>픽셀 복사 ~1-5ms
    AE->>CAM: imageProxy.close()

    AE->>DEF: launch { runPipeline() }

    DEF->>DEF: ImagePreprocessor.processFromBitmap()<br/>Letterbox 416×416 + Virtual Gimbal rotate ~10ms
    DEF->>GPU: withContext(gpuThread)
    GPU->>GPU: YoloInferenceEngine.run()<br/>YOLO11n 추론 GPU ~15-30ms
    GPU-->>DEF: return inferenceOk

    DEF->>DEF: YoloPostProcessor.decode()<br/>threshold → Early Reject → NMS → Inverse bbox rotate
    DEF->>DEF: ByteTracker.update()<br/>Predict → Match → Ego-Motion → futurePoints

    DEF->>UI: onResults(trackedObjects)
    UI->>UI: OverlayView.updateResults()<br/>postInvalidate()
    UI->>UI: HudView.updateStats(pipelineStats)
    UI->>UI: OverlayView.onDraw()<br/>AI(416) → Display 변환<br/>궤적 · bbox · label 렌더링
```

---

## 4. 데이터 흐름 (Data Flow Diagram)

```mermaid
flowchart LR
    CAM["📷 CameraX\nRGBA_8888\n~1920×1080"]

    subgraph Phase2["Phase 2 — 전처리"]
        IP1["copyFromProxy()\nanalysisExecutor"]
        IP2["processFromBitmap()\nDispatchers.Default"]
        GIMBAL["Virtual Gimbal\ncanvas.rotate(+dr, 208, 208)"]
        LB["Letterbox\n416×416"]
        BUF["ByteBuffer\nFloat32\n1×416×416×3"]
    end

    subgraph Phase3["Phase 3 — 추론"]
        TFL["YOLO11n TFLite\nGPU Delegate\ngpuThread"]
        OUT["rawOutput[]\n[1×84×8400]\nor transposed"]
    end

    subgraph Phase4["Phase 4 — 후처리"]
        THR["Confidence\nThreshold\n> 0.30f"]
        ER["Early Reject\nclassFilter[classId]"]
        NMS["NMS\nIoU > 0.45"]
        ROT["Inverse Bbox\nrotMatrix.mapRect()\n-deviceRotation"]
        DETS["List~Detection~\nAI(416) 공간"]
    end

    subgraph Phase5["Phase 5 — 트래킹"]
        BT["ByteTracker\nPredict → Match → Update"]
        EGO["Ego-Motion 보정\ntrueVx/trueVy"]
        FUTURE["futurePoints[20]\n미래 20프레임 예측"]
        TRACKS["List~TrackedObject~\nAI(416) 공간"]
    end

    subgraph Render["렌더링 (Main Thread)"]
        TRANS["AI(416) → Display\ndisplayX = ai416X × renderScale + offsetX"]
        LABEL["레이블\ncanvas.rotate(-dr)\n4-코너 배치"]
        DRAW["OverlayView.onDraw()\n궤적·bbox·레이블"]
    end

    CAM --> IP1 --> IP2 --> GIMBAL --> LB --> BUF
    BUF --> TFL --> OUT
    OUT --> THR --> ER --> NMS --> ROT --> DETS
    DETS --> BT --> EGO --> FUTURE --> TRACKS
    TRACKS --> TRANS --> LABEL --> DRAW
```

---

## 5. 설정 저장 구조 (State / Persistence Diagram)

```mermaid
flowchart TD
    subgraph SP1["SharedPreferences\n'intellitimer_settings'"]
        K1["confidenceThreshold (Float)"]
        K2["confirmFrames (Int)"]
        K3["maxDetections (Int)"]
        K4["maxMissing (Int)"]
    end

    subgraph SP2["SharedPreferences\n'intellitimer_filter'"]
        K5["classFilter (String)\n예: '0,1,2,3,5,7'"]
    end

    subgraph RAM["런타임 싱글톤"]
        AS["AppSettings\n@Volatile 필드"]
        TC2["TrackerConfig\n@Volatile 필드\n+ classFilter: BooleanArray[80]"]
    end

    subgraph DEFAULT["초기값 (저장값 없을 때)"]
        D1["confidenceThreshold = 0.40f"]
        D2["classFilter 활성: person(0) bicycle(1)\ncar(2) motorcycle(3) bus(5) truck(7)"]
    end

    SP1 -->|"AppSettings.load()"| AS
    SP2 -->|"TrackerConfig.loadClassFilter()"| TC2
    AS  -->|"AppSettings.save()"| SP1
    TC2 -->|"TrackerConfig.saveClassFilter()"| SP2

    DEFAULT -.->|"초기값"| AS
    DEFAULT -.->|"초기값"| TC2

    AS  <-->|"슬라이더 변경 즉시"| SBS["SettingsBottomSheet\n파라미터 탭"]
    TC2 <-->|"체크박스 변경 즉시"| SBS2["SettingsBottomSheet\n객체 필터 탭"]
```

---

## 6. 스레드 모델 (Thread Model)

```mermaid
flowchart LR
    subgraph MainThread["🔵 Main Thread (UI)"]
        MT1["Activity 생명주기"]
        MT2["OverlayView.onDraw()"]
        MT3["HudView 갱신"]
        MT4["OrientationListener 콜백\n→ TrackerConfig.deviceRotation"]
    end

    subgraph AEThread["🟡 analysisExecutor\n(단일 백그라운드 스레드)"]
        AE1["ImageAnalysis 콜백 수신"]
        AE2["copyFrame()\n픽셀 복사 ~1-5ms"]
        AE3["imageProxy.close()"]
    end

    subgraph DefaultThread["🟢 Dispatchers.Default\n(코루틴 풀)"]
        DEF1["processFromBitmap()\n전처리 ~10ms"]
        DEF2["YoloPostProcessor.decode()\nNMS ~3ms"]
        DEF3["ByteTracker.update()\n트래킹 ~2ms"]
    end

    subgraph GPUThread["🔴 gpuThread\n(TFLite 고정 스레드)"]
        GPU1["YoloInferenceEngine.run()\nGPU 추론 ~15-30ms"]
    end

    AEThread -->|"launch coroutine"| DefaultThread
    DefaultThread -->|"withContext(gpuThread)"| GPUThread
    GPUThread -->|"resume"| DefaultThread
    DefaultThread -->|"postInvalidate()\nonResults()"| MainThread

    style MainThread fill:#1a237e,color:#fff
    style AEThread fill:#e65100,color:#fff
    style DefaultThread fill:#1b5e20,color:#fff
    style GPUThread fill:#b71c1c,color:#fff
```

---

## 7. 좌표계 변환 흐름 (Coordinate Transform)

```mermaid
flowchart TD
    CS0["📷 Camera Raw\n(1920 × 1080)\nRGBA_8888"]

    CS1["🔄 Effective Camera\nsensor rotation 보정 후\n(effCamW × effCamH)"]

    CS2["🤖 AI Input (Letterbox)\n416 × 416\nVirtual Gimbal rotate +dr 적용"]

    CS3["📦 YOLO Output\n텐서 공간\n(cx, cy, w, h) normalized or 416-scale"]

    CS4["📦 YoloPostProcessor\nAI(416) 공간 bbox\nInverse Gimbal: rotMatrix.mapRect(-dr)"]

    CS5["🖥️ Display Space\nOverlayView (portrait-locked)\ndisplayX = ai416X × renderScale + offsetX\nrenderScale = dispScale / letterboxScale"]

    CS6["👁️ 사용자 시야\n(User-Space)\ncanvas.rotate(-dr) 적용\n레이블 · UI 아이콘은 항상 upright"]

    CS0 -->|"copyFromProxy()\n@analysisExecutor"| CS1
    CS1 -->|"processFromBitmap()\nletterbox + Virtual Gimbal"| CS2
    CS2 -->|"TFLite inference\n@gpuThread"| CS3
    CS3 -->|"decode() + NMS\nEarly Reject"| CS4
    CS4 -->|"YoloFrameProcessor\nsetRenderParams()"| CS5
    CS5 -->|"OverlayView.drawLabel()\ncanvas.rotate(-dr)"| CS6

    note1["renderScale = dispScale / params.scale\nrenderOffsetX = -padLeft × renderScale + startX"]
    note2["dr별 TL anchor:\ndr=0: (left,top)\ndr=90: (left,bottom)\ndr=180: (right,bottom)\ndr=270: (right,top)"]

    CS5 -.-> note1
    CS6 -.-> note2
```

---

## 요약: 핵심 설계 결정

| 결정 | 내용 | 이유 |
|---|---|---|
| **Zero-GC** | onDraw 핫 패스 내 new 객체 없음 | GC pause → 프레임 드롭 방지 |
| **AI 공간 격리** | PostProcessor 출력 = 순수 416 좌표 | 단일 책임, 테스트 용이 |
| **Virtual Gimbal** | canvas.rotate(+dr) on letterbox | AI가 항상 upright 이미지 수신 |
| **2-Stage Frame** | copyFrame / runPipeline 분리 | imageProxy.close() 즉시 → 30fps 유지 |
| **GPU 고정 스레드** | gpuThread = 단일 스레드 | TFLite GPU Delegate 스레드 친화성 |
| **BooleanArray 필터** | classFilter[80] | O(1) 조회, GC 없음, Early Reject |
| **Portrait Lock** | Activity 세로 고정 | 좌표계 단순화, OrientationListener로 보정 |
