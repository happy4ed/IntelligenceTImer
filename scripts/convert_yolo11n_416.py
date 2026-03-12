"""
YOLO11n → TFLite (FP16) 변환 스크립트 — 입력 416×416
  640×640 대비 연산량 42% → GPU 추론 ~6ms 예상
"""

import os, shutil, glob

BASE   = "/mnt/efs/hikari.hong/IntelliTimer"
ASSETS = f"{BASE}/app/src/main/assets"
IMGSZ  = 416

print("=" * 60)
print(f"[Step 1] ultralytics YOLO11n TFLite FP16 export  imgsz={IMGSZ}")
print("=" * 60)

from ultralytics import YOLO
import tensorflow as tf

model = YOLO("yolo11n.pt")

export_path = model.export(
    format="tflite",
    half=True,
    imgsz=IMGSZ,
    nms=False,
    simplify=True,
)
print(f"  Exported to: {export_path}")

# Find generated tflite
tflite_files = (
    glob.glob(f"{BASE}/yolo11n_saved_model/*.tflite") +
    glob.glob(f"{BASE}/yolo11n*.tflite")
)
print(f"  Found: {tflite_files}")

src = None
for f in tflite_files:
    if "float16" in f or "fp16" in f:
        src = f; break
if src is None and tflite_files:
    src = tflite_files[0]
if src is None and isinstance(export_path, str) and export_path.endswith(".tflite"):
    src = export_path

print(f"  Using: {src}")

print()
print("=" * 60)
print("[Step 2] 출력 tensor shape 확인")
print("=" * 60)

interp = tf.lite.Interpreter(model_path=src)
interp.allocate_tensors()

inp = interp.get_input_details()[0]
print(f"  input:  shape={inp['shape']}  dtype={inp['dtype']}")

outs = interp.get_output_details()
for o in outs:
    print(f"  output: shape={o['shape']}  dtype={o['dtype']}")

# 416 기준 앵커 수: (52*52 + 26*26 + 13*13) = 3549
expected_anchors = (IMGSZ//8)**2 + (IMGSZ//16)**2 + (IMGSZ//32)**2
print(f"\n  예상 anchors for {IMGSZ}x{IMGSZ}: {expected_anchors}")

print()
print("=" * 60)
print("[Step 3] assets/yolo11n.tflite 교체")
print("=" * 60)

dst = f"{ASSETS}/yolo11n.tflite"
shutil.copy2(src, dst)
size_mb = os.path.getsize(dst) / 1024 / 1024
print(f"  {src}")
print(f"  → {dst}  ({size_mb:.1f} MB)")

print()
print("=" * 60)
print(f"[완료] yolo11n.tflite ({IMGSZ}x{IMGSZ} FP16) 배포 성공")
print(f"  Kotlin 업데이트 필요: INPUT_H/W={IMGSZ}, NUM_ANCHORS={expected_anchors}")
print("=" * 60)
