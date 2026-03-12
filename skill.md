## 1. Domain: Tesla-style Edge AI Vision Pipeline
- AI Model: YOLOv10n TFLite
- Features: **NMS-free architecture** (모델 내부에 NMS가 있으므로, 안드로이드 코드 단에서 Non-Maximum Suppression 연산을 중복 구현하지 마라).

## 2. Strict Data I/O Contract (파이프라인 단계별 입출력)
**[Phase 1: Input]**
- Source: CameraX `ImageProxy` (RGBA_8888).

**[Phase 2: Pre-processing (Zero-copy goal)]**
- Task: 화면 비율이 찌그러지지 않도록 **Letterbox** 기법을 적용하여 원본 이미지를 640x640으로 리사이징 (남는 공간은 검은색 여백 처리). Alpha 채널을 버리고 RGB 값을 0.0~1.0(Float32)으로 정규화하여 버퍼에 삽입.
- Output: Pre-allocated `ByteBuffer` (Shape: `[1, 640, 640, 3]`)

**[Phase 3: Inference]**
- Task: YOLOv10n TFLite (NNAPI 또는 GPU Delegate 적용) 추론 실행.
- Output: Pre-allocated `FloatArray` (Shape: `[1, 300, 6]`). 
- Format: `[xmin, ymin, xmax, ymax, confidence, class_id]` (640x640 이미지 기준 좌표)

**[Phase 4: Post-processing & Inverse Scaling]**
- Task: Confidence threshold(예: 0.25) 이상인 객체 필터링. 
- **[중요]** 640x640 기준의 AI 좌표를 Letterbox 패딩을 역산(Inverse Scaling)하여, 실제 스마트폰 디스플레이 화면 비율(예: 1080x2400)에 맞게 매핑하라.

**[Phase 5: Tracking & Prediction]**
- Tracking: ByteTrack 개념을 차용하여 이전 프레임 객체들과 IoU(교차율)를 비교해 고유 `trackId` 부여. 
- Prediction: 과거 객체 중심점 좌표 히스토리를 기반으로 속도 벡터 `(vx, vy)` 도출 및 미래 1~2초간의 궤적 포인트(`PointF`) 계산.