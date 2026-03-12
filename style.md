## 1. Language & Architecture
- 100% Kotlin 및 Gradle Kotlin DSL (`build.gradle.kts`) 사용.
- Clean Architecture 지향: Camera, Preprocessing, Inference, Tracking, UI 로직을 각각 독립된 클래스로 분리하라.

## 2. Core Data Models
데이터 운반을 위해 아래의 Kotlin `data class` 구조를 필수로 사용 및 확장하라. (Rule.md 1항을 준수하기 위해 내부 속성을 var로 두어 덮어쓰기 가능하게 할 것)
```kotlin
data class Detection(
    var classId: Int,
    var confidence: Float,
    val rect: RectF // 화면 비율로 역산된 좌표. 매 프레임 객체를 새로 만들지 않고 값만 갱신.
)

data class TrackedObject(
    var trackId: Int,
    val detection: Detection,
    var velocityX: Float,
    var velocityY: Float,
    val predictedTrajectory: MutableList<PointF> // 예측된 미래 궤적 점 배열
)