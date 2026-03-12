"""
YOLOv10n → INT8 양자화 TFLite (NNAPI/Hexagon NPU 최적화)
  - 기존 SavedModel 재사용 (onnx2tf 재실행 불필요)
  - Full INT8 quantization + Float I/O (Android 코드 변경 없음)
  - NNAPI Hexagon DSP 에서 INT8 을 네이티브 처리 → FP16 대비 2~4배 빠름
"""

import os, shutil
import numpy as np
import tensorflow as tf

BASE        = "/mnt/efs/hikari.hong/IntelliTimer"
TF_DIR      = f"{BASE}/yolov10n_saved_model_gpu"   # 기존 SavedModel 재사용
TFLITE_INT8 = f"{BASE}/yolov10n_int8.tflite"
ASSETS      = f"{BASE}/app/src/main/assets/yolov10n.tflite"

print("=" * 60)
print("[INT8 양자화] SavedModel → INT8 TFLite (NNAPI 최적화)")
print("=" * 60)

if not os.path.exists(TF_DIR):
    print(f"  [ERROR] SavedModel 없음: {TF_DIR}")
    print("  먼저 scripts/convert_gpu.py 를 실행하세요.")
    exit(1)

print(f"  SavedModel: {TF_DIR}")

# ─── 대표 데이터셋 (INT8 캘리브레이션용) ─────────────────────────────────────
# SavedModel 입력 shape 을 자동 감지해 캘리브레이션 샘플 생성
_tmp_converter = tf.lite.TFLiteConverter.from_saved_model(TF_DIR)
_tmp_tflite = _tmp_converter.convert()
_tmp_interp  = tf.lite.Interpreter(model_content=_tmp_tflite)
_tmp_interp.allocate_tensors()
_INPUT_SHAPE = _tmp_interp.get_input_details()[0]['shape']  # e.g. [1, 416, 416, 3]
print(f"  캘리브레이션 입력 shape: {list(_INPUT_SHAPE)}")

def representative_data_gen():
    np.random.seed(42)
    for _ in range(100):
        sample = np.random.uniform(0.0, 1.0, _INPUT_SHAPE).astype(np.float32)
        yield [sample]

# ─── INT8 Full Quantization ──────────────────────────────────────────────────
converter = tf.lite.TFLiteConverter.from_saved_model(TF_DIR)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.representative_dataset = representative_data_gen

# INT8 연산 우선, 지원 안 되는 op 는 FP32 폴백
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
    tf.lite.OpsSet.TFLITE_BUILTINS,
]

# Float I/O 유지 — Android 전처리/후처리 코드 변경 불필요
converter.inference_input_type  = tf.float32
converter.inference_output_type = tf.float32

print("  캘리브레이션 중... (100 샘플)")
tflite_int8 = converter.convert()
with open(TFLITE_INT8, "wb") as f:
    f.write(tflite_int8)
print(f"  INT8 TFLite → {TFLITE_INT8}  ({len(tflite_int8)/1024/1024:.1f} MB)")

# ─── 검증 ────────────────────────────────────────────────────────────────────
interp = tf.lite.Interpreter(model_content=tflite_int8)
interp.allocate_tensors()
inp = interp.get_input_details()[0]
out = interp.get_output_details()[0]
print(f"  입력: shape={inp['shape']} dtype={inp['dtype']}")
print(f"  출력: shape={out['shape']} dtype={out['dtype']}")

if list(out['shape']) == [1, 300, 6]:
    print("  [OK] shape [1, 300, 6] — 앱 호환")
else:
    print(f"  [WARN] 예상 shape [1, 300, 6] 과 다름: {list(out['shape'])}")

# ─── assets/ 배포 ────────────────────────────────────────────────────────────
shutil.copy2(TFLITE_INT8, ASSETS)
print()
print(f"  {TFLITE_INT8}")
print(f"  → {ASSETS}  ({os.path.getsize(ASSETS)/1024/1024:.1f} MB)")
print()
print("=" * 60)
print("[완료] INT8 TFLite 모델 생성 및 배포 완료")
print("=" * 60)
