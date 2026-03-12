# IntelliTimer — 개발 히스토리 및 현재 상태

> 다른 Claude 세션에서 이어받기 위한 상세 기술 문서.
> 마지막 업데이트: 2026-03-12

---

## 프로젝트 개요

**IntelliTimer**는 실시간 객체 탐지·추적 Android 앱.
- YOLOv10n / YOLO11n (TFLite, LiteRT) + ByteTracker
- CameraX 카메라 파이프라인
- AI 입력 해상도: 416×416
- 대상 디바이스: Android arm64-v8a (출력 APK 경로: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`)

---

## 아키텍처 핵심 원칙

### Zero-GC 정책
- `onDraw()` 핫 패스에서 `new` 객체 생성 절대 없음
- 모든 `Paint`, `Path`, `RectF`, `BooleanArray` 는 클래스 초기화 시 1회만 할당

### AI-공간 좌표 격리
- `YoloPostProcessor` 출력 = 순수 AI 텐서 공간 (416 기준)
- `OverlayView.onDraw()` 에서만 AI → Display 변환: `displayX = ai416X * renderScale + renderOffsetX`
- Clamp / coerceIn 없음 — Canvas가 자연 클리핑

---

## 주요 구현 목록 (시간순)

### [1] Telegram Bridge 수정
- **파일**: `telegram_bridge.py`
- **문제**: VSCode Extension이 2.1.71→2.1.72로 업데이트되면서 Claude 바이너리 하드코딩 경로 깨짐
- **수정**: `_find_claude()` 함수 — glob 패턴으로 최신 버전 동적 탐색
  ```python
  def _find_claude():
      matches = sorted(glob.glob(
          "/home/happy4ed/.vscode-server/extensions/anthropic.claude-code-*/resources/native-binary/claude"
      ))
      return matches[-1] if matches else None
  ```
- 브릿지 프로세스 중복 실행(PID 2개)으로 "수신완료" 메시지 2회 발생 → 중복 PID kill 로 해결

---

### [2] In-place Rotation & Virtual Gimbal 아키텍처 (4단계)

Activity는 세로 고정(portrait-locked). 기기를 물리적으로 돌려도 화면이 돌아가지 않고, UI만 역방향으로 rotate animation. AI 이미지 및 bbox 좌표는 별도 보정.

#### 지침 1 — OrientationEventListener (MainActivity.kt)
```kotlin
orientationListener = object : OrientationEventListener(this) {
    override fun onOrientationChanged(orientation: Int) {
        if (orientation == ORIENTATION_UNKNOWN) return
        val snapped = ((orientation + 45) / 90 * 90) % 360
        if (snapped == TrackerConfig.deviceRotation) return
        TrackerConfig.deviceRotation = snapped
        // UI 역회전 애니메이션 (-snapped)
        binding.btnDebug.animate().rotation(-snapped.toFloat()).setDuration(300).start()
        binding.btnSettings.animate().rotation(-snapped.toFloat()).setDuration(300).start()
        binding.hudView.animate().rotation(-snapped.toFloat()).setDuration(300).start()
    }
}
// onResume에서 enable(), onPause/onDestroy에서 disable()
```

#### 지침 2 — AI 이미지 직립 보정 (ImagePreprocessor.kt)
letterbox 렌더링 시 outer canvas에 `+deviceRotation` 회전 적용 → AI가 항상 upright 이미지를 받음:
```kotlin
letterboxCanvas.save()
letterboxCanvas.rotate(TrackerConfig.deviceRotation.toFloat(), INPUT_SIZE / 2f, INPUT_SIZE / 2f)
// ... 내부 sensor rotation 포함 drawBitmap ...
letterboxCanvas.restore()  // outer gimbal restore
```

#### 지침 3 — Inverse Bbox 회전 (YoloPostProcessor.kt)
AI 출력 bbox를 `-deviceRotation`으로 역회전 → display 공간에서 올바른 위치:
```kotlin
private val rotMatrix = Matrix()  // pre-alloc, Zero-GC

// NMS 후, 출력 루프 전:
val deviceRot = TrackerConfig.deviceRotation
val cs = coordScale ?: INPUT_SIZE.toFloat()
if (deviceRot != 0) {
    rotMatrix.setRotate(-deviceRot.toFloat(), cs / 2f, cs / 2f)
    // 출력 루프 내:
    if (deviceRot != 0) rotMatrix.mapRect(det.rect)
}
```

#### 지침 4 — 레이블 역회전 (OverlayView.kt)
배경 + 텍스트 전체를 `canvas.rotate(-dr, anchorX, anchorY)` 로 회전하여 user-space에서 그림.

---

### [3] 파라미터 슬라이더 → Settings BottomSheet 이전

**기존**: MainActivity 위에 떠있는 반투명 FloatingPanel (LinearLayout + SeekBar)
**변경**: 기존 설정 버튼 BottomSheet 안으로 통합

이전된 슬라이더 4개 (TrackerConfig):
| 슬라이더 ID | 파라미터 | 범위 | 색상 |
|---|---|---|---|
| `sliderMatchRadius` | `matchRadius` | 20–200, step 10 | `#BB55FF` |
| `sliderMaxCoast` | `maxCoastFrames` | 5–30, step 1 | `#BB55FF` |
| `sliderVelocityEMA` | `velocityEMA` | 0.0–1.0, step 0.05 | `#BB55FF` |
| `sliderDeadZone` | `deadZone` | 0.0–5.0, step 0.5 | `#BB55FF` |

---

### [4] 스마트 4-코너 레이블 배치 (OverlayView.kt)

사용자 시야 기준 TL→TR→BL→BR 우선순위로 레이블 위치 자동 결정.

#### 핵심 수학: screen ↔ user-space 변환

| dr | TL anchor (screen 좌표) | toUax(ax, ay) | toUay(ax, ay) |
|---|---|---|---|
| 0 | (left, top) | ax | ay |
| 90 | (left, bottom) | vh−ay | ax |
| 180 | (right, bottom) | vw−ax | vh−ay |
| 270 | (right, top) | ay | vw−ax |

> dr=90 검증 예시: vw=1080, vh=1920, bbox=(200,500,500,800)
> TL anchor screen = (200, 800) → uax=1120, uay=200
> fitsAbove: 1120+300≤1920 ✓, 200−40≥0 ✓ → 레이블이 bbox 위 ✓

**axList / ayList 공식** (OverlayView.kt:322–333):
```kotlin
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
```

**레이블 렌더링** (canvas.rotate(-dr) 후 local space):
- local +x = user RIGHT, local +y = user DOWN
- "above" = `localBgY = -labelH`, "below" = `localBgY = 0`

---

### [5] 객체 필터 (Object Filter) 탭 — 최신 구현

#### TrackerConfig.kt — 추가된 내용
```kotlin
val classFilter = BooleanArray(80) { true }  // Zero-GC, O(1) 조회
private val DEFAULT_ENABLED = intArrayOf(0, 1, 2, 3, 5, 7)  // person, bicycle, car, motorcycle, bus, truck

fun loadClassFilter(context: Context)  // SharedPreferences → classFilter 복원
fun saveClassFilter(context: Context)  // classFilter → SharedPreferences 저장 (CSV)
```
- SharedPreferences key: `"intellitimer_filter"` / `"classFilter"`
- 저장 형식: 활성 인덱스 콤마 구분 문자열 (e.g. `"0,1,2,3,5,7"`)
- 초기값 없을 경우: DEFAULT_ENABLED 6종만 활성화

#### YoloPostProcessor.kt — Early Reject (line 128)
```kotlin
if (bestScore < threshold) continue
if (!TrackerConfig.classFilter[bestClassId]) continue   // Early reject
if (numCandidates >= MAX_CANDIDATES) break
```
체크 해제 클래스는 RectF·Detection 객체 생성도, NMS도 타지 않음 → 성능 극대화

#### SettingsBottomSheet.xml — 탭 구조
```
Handle bar
Tab bar: [파라미터] [객체 필터]
─────────────────────────────
panelParams (NestedScrollView, 450dp):   ← 탐지 설정 슬라이더 + 트래커 파라미터 슬라이더
panelFilters (LinearLayout, 450dp, GONE by default):
  [전체 선택] [전체 해제]
  NestedScrollView → LinearLayout#filterContainer (80개 체크박스 동적 생성)
─────────────────────────────
[닫기] 버튼 (항상 표시)
```

#### SettingsBottomSheet.kt — 탭 전환 + 체크박스 생성
- `selectTab(isParams: Boolean)` — visibility 전환 + 탭 색상 변경
- `setupFiltersPanel()` — 80개 CheckBox 2열 그리드 동적 생성
  - 형식: `"[0] person"`, `"[2] car"` 등
  - 체크박스 틱 색상: `#00E5FF`
  - 변경 즉시 `TrackerConfig.classFilter[idx]` + `saveClassFilter()` 호출
- `checkBoxes: Array<CheckBox?>` (80개) — Select All / Clear All 시 일괄 갱신용

#### MainActivity.kt — 앱 시작 시 필터 복원
```kotlin
AppSettings.load(this)
TrackerConfig.loadClassFilter(this)   // onCreate에 추가
```

---

## 현재 파일 구조 (핵심 파일)

```
IntelliTimer/
├── app/src/main/
│   ├── java/com/intellitimer/vision/
│   │   ├── MainActivity.kt                 ← OrientationListener, 필터 load
│   │   ├── camera/CameraManager.kt
│   │   ├── inference/
│   │   │   ├── ImagePreprocessor.kt        ← Virtual Gimbal (outer rotate)
│   │   │   ├── YoloFrameProcessor.kt
│   │   │   ├── YoloInferenceEngine.kt
│   │   │   └── YoloPostProcessor.kt        ← Inverse bbox rotate + Early reject
│   │   ├── model/
│   │   │   ├── AppSettings.kt              ← 탐지 설정 SharedPreferences
│   │   │   ├── CocoLabels.kt               ← COCO 80클래스 이름 테이블
│   │   │   ├── Detection.kt
│   │   │   ├── LetterboxParams.kt
│   │   │   └── TrackedObject.kt
│   │   ├── tracking/
│   │   │   ├── ByteTracker.kt
│   │   │   └── TrackerConfig.kt            ← classFilter + load/save 추가됨
│   │   └── ui/
│   │       ├── HudView.kt
│   │       ├── OverlayView.kt              ← 4-코너 레이블, canvas.rotate(-dr)
│   │       ├── SettingsBottomSheet.kt      ← 탭 구조 + 80개 체크박스
│   │       └── ...
│   └── res/layout/
│       ├── activity_main.xml
│       └── bottom_sheet_settings.xml       ← 탭 구조로 재작성
├── telegram_bridge.py                      ← Claude 경로 동적 탐색
└── history.md                              ← 이 파일
```

---

## 빌드 및 배포

### 릴리즈 빌드
```bash
cd /mnt/efs/hikari.hong/IntelliTimer
./gradlew assembleRelease
# 출력: app/build/outputs/apk/release/app-arm64-v8a-release.apk (~19MB)
```

### Telegram 배포
```bash
cd /mnt/efs/hikari.hong/IntelliTimer
bash deploy.sh "배포 메시지"
```

---

## 의존성 (app/build.gradle.kts)

```kotlin
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.appcompat)
implementation(libs.material)               // Material Slider, OutlinedButton, BottomSheet
implementation(libs.androidx.constraintlayout)
implementation(libs.camerax.core/camera2/lifecycle/view)
implementation(libs.litert)                 // LiteRT (TFLite 신규 브랜딩)
implementation(libs.litert.gpu / litert.gpu.api)
implementation(libs.coroutines.android)
```
> RecyclerView는 의존성에 없음. 체크박스는 LinearLayout 2열 그리드로 구현.

---

## 알려진 이슈 / 미완성 작업

### 레이블 위치 (보고된 상태: 수정 완료)
- 세로: 정상 (bbox 위, 좌측 정렬)
- 가로(dr=90): 이전에 우측면 하단에 붙던 문제 → anchor 선택 공식 및 user-space 변환 수정 완료
  - 수정 핵심: dr=90 TL anchor = screen(left, **bottom**), uax = vh−ay, uay = ax

### TrackerConfig 슬라이더 (현재 앱 재시작 시 기본값 복원)
- 현재: TrackerConfig 슬라이더(matchRadius, maxCoast 등)는 SharedPreferences 저장 없음
- AppSettings 슬라이더(confidenceThreshold 등)는 저장됨
- 필요시 TrackerConfig에도 load/save 추가 가능

---

## TrackerConfig 전체 필드 참조

```kotlin
object TrackerConfig {
    @Volatile var matchRadius: Float = 40f        // 20~200 effCam px
    @Volatile var maxCoastFrames: Int = 15        // 5~30 frames
    @Volatile var velocityEMA: Float = 0.8f       // 0.0~1.0
    @Volatile var deadZone: Float = 1.0f          // 0~5 effCam px
    @Volatile var deviceRotation: Int = 0         // 0/90/180/270

    val classFilter = BooleanArray(80) { true }   // Zero-GC, O(1)
    fun loadClassFilter(context: Context)
    fun saveClassFilter(context: Context)
}
```

## AppSettings 전체 필드 참조

```kotlin
object AppSettings {
    @Volatile var confidenceThreshold: Float = 0.40f   // 0.15~0.85
    @Volatile var confirmFrames: Int = 2               // 1~5
    @Volatile var maxDetections: Int = 10              // 1~20
    @Volatile var maxMissing: Int = 5                  // 1~15

    fun load(context: Context)
    fun save(context: Context)
}
```

---

## COCO 클래스 인덱스 참조 (주요 클래스)

```
0: person      1: bicycle     2: car         3: motorcycle
4: airplane    5: bus         6: train       7: truck
8: boat        9: traffic light              10: fire hydrant
```
전체 80개: `CocoLabels.kt` 의 `NAMES` 배열 참조.

---

## Telegram Bridge 설정

- **파일**: `telegram_bridge.py`
- **봇 수신**: Telegram 메시지 → Claude CLI 실행
- **Claude 경로**: glob으로 최신 버전 동적 탐색 (`_find_claude()`)
- **프로세스 관리**: `systemd` 또는 수동 백그라운드 실행
  - 중복 프로세스 주의 → `pgrep -f telegram_bridge` 로 확인 후 중복 kill
- **배포 연동**: `deploy.sh` 실행 후 완료 메시지를 Telegram으로 전송
