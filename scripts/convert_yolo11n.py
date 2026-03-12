"""
YOLO11n → TFLite (FP16) 변환 스크립트
  ultralytics export 를 사용해 직접 TFLite FP16 생성
  출력 텐서: [1, 84, 8400]  (4 box + 80 classes, 8400 anchors)
"""

import os, shutil

BASE   = "/mnt/efs/hikari.hong/IntelliTimer"
ASSETS = f"{BASE}/app/src/main/assets"
OUT_TFLITE = f"{BASE}/yolo11n_fp16.tflite"

# ─── Step 1: ultralytics 로 YOLO11n export ────────────────────────────────────
print("=" * 60)
print("[Step 1] ultralytics YOLO11n TFLite FP16 export")
print("=" * 60)

from ultralytics import YOLO
import tensorflow as tf

model = YOLO("yolo11n.pt")

# Export to TFLite with FP16
export_path = model.export(
    format="tflite",
    half=True,          # FP16
    imgsz=640,
    nms=False,          # No built-in NMS — handled in Kotlin (Zero-GC)
    simplify=True,
)
print(f"  Exported to: {export_path}")

# ultralytics exports to yolo11n_saved_model/yolo11n_float16.tflite
# Find the generated tflite file
import glob
tflite_files = glob.glob(f"{BASE}/yolo11n_saved_model/*.tflite") + \
               glob.glob(f"{BASE}/yolo11n*.tflite")
print(f"  Found tflite files: {tflite_files}")

# Pick the float16 one
src = None
for f in tflite_files:
    if "float16" in f or "fp16" in f:
        src = f
        break
if src is None and tflite_files:
    src = tflite_files[0]

if src is None:
    # Try export_path directly
    if isinstance(export_path, str) and export_path.endswith(".tflite"):
        src = export_path

print(f"  Using: {src}")

# ─── Step 2: 출력 shape 검증 ──────────────────────────────────────────────────
print()
print("=" * 60)
print("[Step 2] 출력 tensor shape 검증")
print("=" * 60)

interp = tf.lite.Interpreter(model_path=src)
interp.allocate_tensors()
outs = interp.get_output_details()
for o in outs:
    print(f"  output: shape={o['shape']}  dtype={o['dtype']}")

# YOLO11n: [1, 84, 8400] or [84, 8400]
ok = any(
    (list(o['shape']) == [1, 84, 8400]) or (list(o['shape']) == [84, 8400])
    for o in outs
)
if ok:
    print("  [OK] 예상 shape 확인")
else:
    print("  [WARN] 예상 shape [1,84,8400] 아님 — 확인 필요")

# ─── Step 3: assets/ 에 복사 ──────────────────────────────────────────────────
print()
print("=" * 60)
print("[Step 3] assets/yolo11n.tflite 에 배포")
print("=" * 60)

dst = f"{ASSETS}/yolo11n.tflite"
shutil.copy2(src, dst)
print(f"  {src}")
print(f"  → {dst}  ({os.path.getsize(dst)/1024/1024:.1f} MB)")

print()
print("=" * 60)
print("[완료] yolo11n.tflite assets 배포 성공")
print("=" * 60)
