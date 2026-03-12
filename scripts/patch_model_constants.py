"""
모델 출력 shape 가 [1, 300, 6] 과 다를 경우 Android 소스를 자동 패치.

실행:
    python patch_model_constants.py --tflite ../app/src/main/assets/yolov10n.tflite

예시 (shape 가 [1, 200, 4] 인 커스텀 모델):
    python patch_model_constants.py --tflite my_model.tflite
"""

import argparse
import re
import os

def get_output_shape(tflite_path: str):
    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    out = interp.get_output_details()[0]
    return tuple(out['shape'])

def patch_inference_engine(num_detections: int, values_per_box: int, src_root: str):
    path = os.path.join(
        src_root,
        "app/src/main/java/com/intellitimer/vision/inference/YoloInferenceEngine.kt"
    )
    with open(path, "r") as f:
        code = f.read()

    code = re.sub(
        r"(const val NUM_DETECTIONS\s*=\s*)\d+",
        f"\\g<1>{num_detections}",
        code
    )
    code = re.sub(
        r"(const val VALUES_PER_BOX\s*=\s*)\d+",
        f"\\g<1>{values_per_box}",
        code
    )
    with open(path, "w") as f:
        f.write(code)
    print(f"✅ YoloInferenceEngine.kt  NUM_DETECTIONS={num_detections}, VALUES_PER_BOX={values_per_box}")

def patch_byte_tracker(max_tracks_hint: int, src_root: str):
    """ByteTracker.MAX_TRACKS 를 NUM_DETECTIONS 이상으로 보장."""
    path = os.path.join(
        src_root,
        "app/src/main/java/com/intellitimer/vision/tracking/ByteTracker.kt"
    )
    with open(path, "r") as f:
        code = f.read()

    # 기존 MAX_TRACKS 값 읽기
    m = re.search(r"const val MAX_TRACKS\s*=\s*(\d+)", code)
    current = int(m.group(1)) if m else 50
    new_val = max(current, max_tracks_hint)

    code = re.sub(
        r"(const val MAX_TRACKS\s*=\s*)\d+",
        f"\\g<1>{new_val}",
        code
    )
    with open(path, "w") as f:
        f.write(code)
    print(f"✅ ByteTracker.kt          MAX_TRACKS={new_val}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tflite", required=True, help="검증할 .tflite 파일 경로")
    args = parser.parse_args()

    shape = get_output_shape(args.tflite)
    print(f"모델 출력 shape: {shape}")   # 예: (1, 300, 6)

    if len(shape) != 3:
        print(f"⚠ 예상치 못한 출력 차원: {len(shape)}D. 수동 확인 필요.")
        exit(1)

    _, num_det, val_per_box = shape
    script_dir = os.path.dirname(os.path.abspath(__file__))
    src_root   = os.path.join(script_dir, "..")

    patch_inference_engine(int(num_det), int(val_per_box), src_root)
    patch_byte_tracker(int(num_det), src_root)

    print("\n패치 완료. Android Studio 에서 Sync Project 후 빌드하세요.")
