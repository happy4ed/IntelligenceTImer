"""
YOLOv10n → TFLite GPU 호환 변환 스크립트
  Step 1: onnx-simplifier 로 그래프 단순화 (simplify=True)
  Step 2: onnx2tf 로 SavedModel 변환
  Step 3: TFLite Converter 로 FP16 TFLite 생성 (half=True)
  Step 4: assets/ 에 복사

TFLite GPU Delegate 호환성을 높이는 핵심:
  - onnxsim: 불필요한 연산·상수 폴딩 제거 → GPU가 인식 못하는 복잡한 op 패턴 제거
  - FP16: GPU는 FP16을 네이티브 처리 → 전체 그래프를 GPU에 올릴 수 있는 가능성 증가
  - SavedModel 경유: onnx2tf 가 ONNX op → TF op 을 정확히 매핑
"""

import os, shutil, sys
import numpy as np

BASE  = "/mnt/efs/hikari.hong/IntelliTimer"
ONNX_IN   = f"{BASE}/yolov10n.onnx"
ONNX_SIM  = f"{BASE}/yolov10n_simplified.onnx"
TF_DIR    = f"{BASE}/yolov10n_saved_model_gpu"
TFLITE_FP16 = f"{BASE}/yolov10n_fp16.tflite"
TFLITE_FP32 = f"{BASE}/yolov10n_fp32.tflite"
ASSETS    = f"{BASE}/app/src/main/assets/yolov10n.tflite"

# ─── Step 1: ONNX simplify ────────────────────────────────────────────────────
print("=" * 60)
print("[Step 1] onnx-simplifier 로 그래프 단순화 (simplify=True)")
print("=" * 60)
import onnx
from onnxsim import simplify as onnx_simplify

model = onnx.load(ONNX_IN)
print(f"  Input : {ONNX_IN}  ({os.path.getsize(ONNX_IN)/1024/1024:.1f} MB)")
print(f"  Opset : {model.opset_import[0].version}")

simplified, ok = onnx_simplify(model)
if not ok:
    print("  [WARN] simplification 실패 — 원본 모델로 계속 진행")
    simplified = model
else:
    print("  [OK] simplification 성공")

onnx.save(simplified, ONNX_SIM)
print(f"  Output: {ONNX_SIM}  ({os.path.getsize(ONNX_SIM)/1024/1024:.1f} MB)")

# 출력 shape 확인
for o in simplified.graph.output:
    dims = [d.dim_value if d.dim_value > 0 else '?' for d in o.type.tensor_type.shape.dim]
    print(f"  Output tensor: {o.name} {dims}")

# ─── Step 2: ONNX → SavedModel (onnx2tf) ─────────────────────────────────────
print()
print("=" * 60)
print("[Step 2] onnx2tf: ONNX → TensorFlow SavedModel")
print("=" * 60)
import onnx2tf

if os.path.exists(TF_DIR):
    shutil.rmtree(TF_DIR)

onnx2tf.convert(
    input_onnx_file_path=ONNX_SIM,
    output_folder_path=TF_DIR,
    not_use_onnxsim=True,       # 이미 Step1 에서 simplify 완료
    output_signaturedefs=True,  # op 이름 패턴 오류 해결
    verbosity="error",
)
print(f"  SavedModel → {TF_DIR}")

# ─── Step 3-A: FP16 TFLite 변환 (half=True) ──────────────────────────────────
print()
print("=" * 60)
print("[Step 3-A] TFLite FP16 변환 (half=True — GPU 최적화)")
print("=" * 60)
import tensorflow as tf

converter = tf.lite.TFLiteConverter.from_saved_model(TF_DIR)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
# FP16: GPU Delegate 가 FP16 을 네이티브 처리 → 전체 그래프를 GPU에 위임 가능
converter.target_spec.supported_types = [tf.float16]
# GPU delegate 호환: 지원하지 않는 op 는 CPU 로 폴백하지 않고 에러로 알림
# (강제 GPU 연산만 허용하려면 아래 줄 활성화, 폴백 허용 시 주석 유지)
# converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]

tflite_fp16 = converter.convert()
with open(TFLITE_FP16, "wb") as f:
    f.write(tflite_fp16)
print(f"  FP16 TFLite → {TFLITE_FP16}  ({len(tflite_fp16)/1024/1024:.1f} MB)")

# 출력 shape 검증
interp = tf.lite.Interpreter(model_content=tflite_fp16)
interp.allocate_tensors()
out = interp.get_output_details()
print(f"  출력 tensor shape: {out[0]['shape']}  dtype: {out[0]['dtype']}")
expected = [1, 300, 6]
actual   = list(out[0]['shape'])
if actual != expected:
    print(f"  [WARN] 예상 shape {expected} 과 다름 — 앱 PostProcessor 수정 필요")
else:
    print(f"  [OK] shape {actual} — 앱 호환")

# ─── Step 3-B: FP32 TFLite (폴백용) ─────────────────────────────────────────
print()
print("=" * 60)
print("[Step 3-B] TFLite FP32 변환 (폴백 참고용)")
print("=" * 60)

converter_fp32 = tf.lite.TFLiteConverter.from_saved_model(TF_DIR)
tflite_fp32 = converter_fp32.convert()
with open(TFLITE_FP32, "wb") as f:
    f.write(tflite_fp32)
print(f"  FP32 TFLite → {TFLITE_FP32}  ({len(tflite_fp32)/1024/1024:.1f} MB)")

# ─── Step 4: assets/ 에 FP16 모델 배포 ───────────────────────────────────────
print()
print("=" * 60)
print("[Step 4] FP16 모델을 앱 assets/ 에 복사")
print("=" * 60)

shutil.copy2(TFLITE_FP16, ASSETS)
print(f"  {TFLITE_FP16}")
print(f"  → {ASSETS}  ({os.path.getsize(ASSETS)/1024/1024:.1f} MB)")

print()
print("=" * 60)
print("[완료] GPU 호환 TFLite 모델 생성 성공")
print(f"  FP16: {TFLITE_FP16}")
print(f"  FP32: {TFLITE_FP32} (필요시 assets 교체)")
print("=" * 60)
