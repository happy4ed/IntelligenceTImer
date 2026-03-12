package com.intellitimer.vision.model

/**
 * COCO 80-class 레이블 테이블.
 * YOLOv10n 은 COCO 데이터셋(80 클래스)으로 학습됨.
 * class_id 0-79 → 영문 이름 매핑.
 */
object CocoLabels {

    private val NAMES = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane",
        "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird",
        "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat",
        "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon",
        "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut",
        "cake", "chair", "couch", "potted plant", "bed",
        "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock",
        "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    /** class_id → 영문 레이블. 범위 초과 시 "cls:$id" 반환. */
    fun get(classId: Int): String =
        if (classId in NAMES.indices) NAMES[classId] else "cls:$classId"
}
