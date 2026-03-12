"""
YOLOv10n → TFLite 변환 스크립트
================================
실행 전 의존성 설치:
    pip install ultralytics tensorflow onnx onnx2tf

실행:
    python export_yolov10n_tflite.py

출력:
    yolov10n_saved_model/yolov10n_float32.tflite
    → 이 파일을 app/src/main/assets/yolov10n.tflite 로 복사

주의:
    YOLOv10n 출력 텐서 shape 를 반드시 verify_output_shape() 로 확인하세요.
    shape 가 [1, 300, 6] 이 아니면 YoloInferenceEngine 상수를 수정해야 합니다.
"""

import os
import sys
import numpy as np


# ─── Step 1: Ultralytics 경로로 YOLOv10n 다운로드 + TFLite 변환 ─────────────

def export_via_ultralytics():
    """
    Ultralytics YOLO API 를 사용한 변환 (가장 간단한 방법).
    YOLOv10 은 ultralytics >= 8.2 에서 지원.
    """
    try:
        from ultralytics import YOLO
        print("[1/3] YOLOv10n 사전학습 모델 로드 중...")
        model = YOLO("yolov10n.pt")   # 없으면 자동 다운로드 (~7MB)

        print("[2/3] TFLite INT8 양자화 변환 중... (COCO 128 캘리브레이션)")
        # int8 양자화: 모바일 추론 속도 ~4× 향상, 모델 크기 ~4× 감소
        export_path = model.export(
            format   = "tflite",
            imgsz    = 640,
            int8     = True,        # INT8 양자화 (NNAPI 최적화)
            data     = "coco128.yaml",  # 캘리브레이션 데이터셋
            nms      = False,       # YOLOv10 은 NMS-free — 추가 NMS 삽입 금지
        )
        print(f"[3/3] 변환 완료: {export_path}")
        return export_path

    except ImportError:
        print("ultralytics 미설치. pip install ultralytics 실행 후 재시도하세요.")
        return None
    except Exception as e:
        print(f"Ultralytics 변환 실패: {e}")
        print("아래의 수동 변환을 시도하세요.")
        return None


# ─── Step 2 (대안): ONNX → TFLite 수동 변환 ─────────────────────────────────

def export_via_onnx():
    """
    ONNX → onnx2tf → TFLite 변환 (Ultralytics 실패 시 대안).
    pip install onnx onnx2tf
    """
    import subprocess

    # 1. ONNX 다운로드 (GitHub Releases)
    onnx_url = (
        "https://github.com/THU-MIG/yolov10/releases/download/v1.1/yolov10n.onnx"
    )
    onnx_file = "yolov10n.onnx"
    if not os.path.exists(onnx_file):
        print(f"ONNX 모델 다운로드 중: {onnx_url}")
        subprocess.run(["wget", "-q", onnx_url, "-O", onnx_file], check=True)

    # 2. onnx2tf 로 SavedModel → TFLite 변환
    print("onnx2tf 변환 중...")
    subprocess.run([
        "onnx2tf",
        "-i", onnx_file,
        "-oiqt",            # INT8 양자화
        "-o", "yolov10n_saved_model",
    ], check=True)

    tflite_path = "yolov10n_saved_model/yolov10n_float32.tflite"
    print(f"변환 완료: {tflite_path}")
    return tflite_path


# ─── Step 3: 출력 텐서 shape 검증 ────────────────────────────────────────────

def verify_output_shape(tflite_path: str):
    """
    변환된 모델의 입출력 shape 를 출력하고,
    YoloInferenceEngine 상수(NUM_DETECTIONS, VALUES_PER_BOX)와 일치하는지 확인.
    """
    import tensorflow as tf

    print(f"\n{'='*60}")
    print(f"모델 검증: {tflite_path}")
    print('='*60)

    interp = tf.lite.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()

    in_details  = interp.get_input_details()
    out_details = interp.get_output_details()

    print("\n▶ 입력 텐서:")
    for d in in_details:
        print(f"   index={d['index']}  name={d['name']}")
        print(f"   shape={d['shape']}  dtype={d['dtype'].__name__}")

    print("\n▶ 출력 텐서:")
    for d in out_details:
        print(f"   index={d['index']}  name={d['name']}")
        print(f"   shape={d['shape']}  dtype={d['dtype'].__name__}")

    # ── YoloInferenceEngine 와 호환성 검사 ───────────────────────────────────
    EXPECTED_INPUT  = (1, 640, 640, 3)
    EXPECTED_OUTPUT = (1, 300, 6)   # [1, NUM_DETECTIONS, VALUES_PER_BOX]

    ok = True

    actual_in = tuple(in_details[0]['shape'])
    if actual_in != EXPECTED_INPUT:
        print(f"\n⚠ 입력 shape 불일치!")
        print(f"   예상: {EXPECTED_INPUT}")
        print(f"   실제: {actual_in}")
        print("   → ImagePreprocessor.INPUT_SIZE 를 수정하세요.")
        ok = False
    else:
        print(f"\n✅ 입력 shape OK: {actual_in}")

    actual_out = tuple(out_details[0]['shape'])
    if actual_out != EXPECTED_OUTPUT:
        print(f"\n⚠ 출력 shape 불일치!")
        print(f"   예상: {EXPECTED_OUTPUT}")
        print(f"   실제: {actual_out}")
        print("   → YoloInferenceEngine.NUM_DETECTIONS / VALUES_PER_BOX 를 수정하세요.")
        print(f"      NUM_DETECTIONS = {actual_out[1]}")
        print(f"      VALUES_PER_BOX = {actual_out[2]}")
        ok = False
    else:
        print(f"✅ 출력 shape OK: {actual_out}")

    # ── 더미 추론으로 실제 동작 확인 ─────────────────────────────────────────
    print("\n▶ 더미 추론 (랜덤 입력)...")
    dummy = np.random.rand(*EXPECTED_INPUT).astype(np.float32)
    interp.set_tensor(in_details[0]['index'], dummy)
    interp.invoke()
    output = interp.get_tensor(out_details[0]['index'])
    print(f"   출력 샘플 (첫 탐지): {output[0][0]}")
    print(f"   → [xmin={output[0][0][0]:.1f}, ymin={output[0][0][1]:.1f}, "
          f"xmax={output[0][0][2]:.1f}, ymax={output[0][0][3]:.1f}, "
          f"conf={output[0][0][4]:.3f}, cls={output[0][0][5]:.0f}]")

    print(f"\n{'='*60}")
    if ok:
        print("✅ 모든 shape 검증 통과 — assets/ 에 복사 가능")
    else:
        print("⚠ YoloInferenceEngine / YoloPostProcessor 상수 수정 필요 (위 안내 참고)")
    print('='*60)

    return ok


# ─── Step 4: assets/ 폴더 복사 ───────────────────────────────────────────────

def copy_to_assets(tflite_path: str):
    import shutil
    # 스크립트가 IntelliTimer/scripts/ 에 있다고 가정
    script_dir   = os.path.dirname(os.path.abspath(__file__))
    assets_dir   = os.path.join(script_dir, "..", "app", "src", "main", "assets")
    os.makedirs(assets_dir, exist_ok=True)

    dest = os.path.join(assets_dir, "yolov10n.tflite")
    shutil.copy2(tflite_path, dest)
    size_mb = os.path.getsize(dest) / (1024 * 1024)
    print(f"\n✅ 복사 완료: {dest} ({size_mb:.1f} MB)")


# ─── Main ─────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    tflite_path = None

    # 방법 1: Ultralytics
    tflite_path = export_via_ultralytics()

    # 방법 1 실패 시 방법 2: ONNX
    if not tflite_path or not os.path.exists(tflite_path):
        tflite_path = export_via_onnx()

    if not tflite_path or not os.path.exists(tflite_path):
        print("\n❌ 변환 실패. 수동으로 아래 명령을 실행하세요:")
        print("   pip install ultralytics")
        print("   python -c \"from ultralytics import YOLO; YOLO('yolov10n.pt').export(format='tflite', imgsz=640)\"")
        sys.exit(1)

    # Shape 검증
    verify_output_shape(tflite_path)

    # assets/ 복사
    copy_to_assets(tflite_path)
