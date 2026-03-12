# IntelligenceTimer

실시간 객체 탐지 및 추적 Android 앱 — YOLO11n + ByteTracker

---

## 주요 기능

### 실시간 객체 탐지
- **YOLO11n / YOLOv10n** TFLite 모델 (GPU Delegate 가속)
- COCO 80클래스 지원 — 사람, 자동차, 자전거 등
- 신뢰도 임계값, 최대 탐지 수 실시간 조정 가능

### 다중 객체 추적 (ByteTracker)
- 프레임 간 객체 ID 유지 (트랙 ID 표시)
- 속도·궤적 예측 및 미래 경로 시각화
- Ego-Motion 보정으로 카메라 흔들림 제거

### Virtual Gimbal (화면 회전 보정)
- Activity 세로 고정 상태에서 기기를 가로로 들어도 AI 탐지 정상 동작
- UI 아이콘·레이블이 항상 사용자 시야 기준으로 upright 표시

### 객체 필터
- 80개 COCO 클래스를 체크박스로 개별 ON/OFF
- 설정은 앱 재시작 후에도 유지
- 기본 활성: 자율주행 관련 6종 (사람·자전거·자동차·오토바이·버스·트럭)

### Tesla FSD 스타일 오버레이
- 바운딩 박스 + 코너 강조
- 속도 벡터 방향 화살표
- 궤적 히스토리 (Bezier 곡선, alpha 감쇠)
- 미래 20프레임 예측 경로

---

## 화면 구성

| 요소 | 설명 |
|---|---|
| 카메라 프리뷰 | 전체화면 실시간 영상 |
| OverlayView | bbox·궤적·레이블 렌더링 |
| HudView | FPS·추론 딜레이·탐지 수 표시 |
| 설정 버튼 | BottomSheet — 파라미터 / 객체 필터 탭 |

---

## 기술 스택

- **언어**: Kotlin
- **AI 추론**: LiteRT (TFLite) + GPU Delegate
- **카메라**: CameraX (RGBA_8888)
- **UI**: Android View (Zero-GC Canvas 렌더링)
- **비동기**: Kotlin Coroutines

---

## 빌드

```bash
./gradlew assembleRelease
# 출력: app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Android Studio에서 열거나 Gradle로 직접 빌드 가능.
`app/src/main/assets/` 에 모델 파일(`.tflite`)이 포함되어 있어 별도 다운로드 불필요.
