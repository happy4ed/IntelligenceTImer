package com.intellitimer.vision.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.intellitimer.vision.R
import com.intellitimer.vision.model.AppSettings
import com.intellitimer.vision.model.CocoLabels
import com.intellitimer.vision.tracking.TrackerConfig

/**
 * 탐지 설정 BottomSheet.
 *
 * [지침 2] 탭 구조: 파라미터(슬라이더) ↔ 객체 필터(체크박스 80개).
 * [지침 3] 80개 COCO 클래스 체크박스 동적 생성 (2열 그리드).
 * [지침 4] 전체 선택 / 전체 해제 버튼.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    // [지침 3] 80개 체크박스 참조 보관 → Select All / Clear All 시 일괄 갱신
    private val checkBoxes = arrayOfNulls<CheckBox>(80)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── [지침 2] 탭 전환 ──────────────────────────────────────────────────
        val tabParams    = view.findViewById<TextView>(R.id.tabParams)
        val tabFilters   = view.findViewById<TextView>(R.id.tabFilters)
        val panelParams  = view.findViewById<View>(R.id.panelParams)
        val panelFilters = view.findViewById<View>(R.id.panelFilters)

        fun selectTab(isParams: Boolean) {
            panelParams.visibility  = if (isParams) View.VISIBLE else View.GONE
            panelFilters.visibility = if (isParams) View.GONE    else View.VISIBLE
            // 활성 탭: 밝은 강조, 비활성 탭: 흐리게
            tabParams.setTextColor(if (isParams) Color.parseColor("#00E5FF") else Color.parseColor("#88FFFFFF"))
            tabParams.setBackgroundColor(if (isParams) Color.parseColor("#2200E5FF") else Color.parseColor("#11FFFFFF"))
            tabFilters.setTextColor(if (isParams) Color.parseColor("#88FFFFFF") else Color.parseColor("#39FF73"))
            tabFilters.setBackgroundColor(if (isParams) Color.parseColor("#11FFFFFF") else Color.parseColor("#2239FF73"))
        }

        tabParams.setOnClickListener  { selectTab(true)  }
        tabFilters.setOnClickListener { selectTab(false) }
        selectTab(true)   // 기본: 파라미터 탭

        // ── 파라미터 탭: 슬라이더 ────────────────────────────────────────────
        setupParamsPanel(view)

        // ── [지침 3,4] 필터 탭: 체크박스 + 전체 선택/해제 ───────────────────
        setupFiltersPanel(view)

        view.findViewById<Button>(R.id.btnSettingsClose).setOnClickListener { dismiss() }
    }

    // ── 파라미터 슬라이더 (기존 로직 완전 보존) ──────────────────────────────

    private fun setupParamsPanel(view: View) {
        val sliderConf    = view.findViewById<Slider>(R.id.sliderConf)
        val sliderConfirm = view.findViewById<Slider>(R.id.sliderConfirm)
        val sliderMaxDet  = view.findViewById<Slider>(R.id.sliderMaxDet)
        val sliderMissing = view.findViewById<Slider>(R.id.sliderMissing)

        val tvConf    = view.findViewById<TextView>(R.id.tvConfValue)
        val tvConfirm = view.findViewById<TextView>(R.id.tvConfirmValue)
        val tvMaxDet  = view.findViewById<TextView>(R.id.tvMaxDetValue)
        val tvMissing = view.findViewById<TextView>(R.id.tvMissingValue)

        val sliderMatchRadius = view.findViewById<Slider>(R.id.sliderMatchRadius)
        val sliderMaxCoast    = view.findViewById<Slider>(R.id.sliderMaxCoast)
        val sliderVelocityEMA = view.findViewById<Slider>(R.id.sliderVelocityEMA)
        val sliderDeadZone    = view.findViewById<Slider>(R.id.sliderDeadZone)

        val tvMatchRadius = view.findViewById<TextView>(R.id.tvMatchRadiusValue)
        val tvMaxCoast    = view.findViewById<TextView>(R.id.tvMaxCoastValue)
        val tvVelocityEMA = view.findViewById<TextView>(R.id.tvVelocityEMAValue)
        val tvDeadZone    = view.findViewById<TextView>(R.id.tvDeadZoneValue)

        // 초기화
        sliderConf.value    = AppSettings.confidenceThreshold
        sliderConfirm.value = AppSettings.confirmFrames.toFloat()
        sliderMaxDet.value  = AppSettings.maxDetections.toFloat()
        sliderMissing.value = AppSettings.maxMissing.toFloat()

        tvConf.text    = "${(AppSettings.confidenceThreshold * 100).toInt()}%"
        tvConfirm.text = "${AppSettings.confirmFrames}프레임"
        tvMaxDet.text  = "${AppSettings.maxDetections}개"
        tvMissing.text = "${AppSettings.maxMissing}프레임"

        sliderMatchRadius.value = TrackerConfig.matchRadius.coerceIn(20f, 200f)
        sliderMaxCoast.value    = TrackerConfig.maxCoastFrames.toFloat().coerceIn(5f, 30f)
        sliderVelocityEMA.value = TrackerConfig.velocityEMA.coerceIn(0f, 1f)
        sliderDeadZone.value    = TrackerConfig.deadZone.coerceIn(0f, 5f)

        tvMatchRadius.text = "${TrackerConfig.matchRadius.toInt()}px"
        tvMaxCoast.text    = "${TrackerConfig.maxCoastFrames}프레임"
        tvVelocityEMA.text = "${"%.2f".format(TrackerConfig.velocityEMA)}"
        tvDeadZone.text    = "${"%.1f".format(TrackerConfig.deadZone)}px"

        // 변경 리스너
        sliderConf.addOnChangeListener { _, value, _ ->
            AppSettings.confidenceThreshold = value
            tvConf.text = "${(value * 100).toInt()}%"
            AppSettings.save(requireContext())
        }
        sliderConfirm.addOnChangeListener { _, value, _ ->
            AppSettings.confirmFrames = value.toInt()
            tvConfirm.text = "${value.toInt()}프레임"
            AppSettings.save(requireContext())
        }
        sliderMaxDet.addOnChangeListener { _, value, _ ->
            AppSettings.maxDetections = value.toInt()
            tvMaxDet.text = "${value.toInt()}개"
            AppSettings.save(requireContext())
        }
        sliderMissing.addOnChangeListener { _, value, _ ->
            AppSettings.maxMissing = value.toInt()
            tvMissing.text = "${value.toInt()}프레임"
            AppSettings.save(requireContext())
        }
        sliderMatchRadius.addOnChangeListener { _, value, _ ->
            TrackerConfig.matchRadius = value
            tvMatchRadius.text = "${value.toInt()}px"
        }
        sliderMaxCoast.addOnChangeListener { _, value, _ ->
            TrackerConfig.maxCoastFrames = value.toInt()
            tvMaxCoast.text = "${value.toInt()}프레임"
        }
        sliderVelocityEMA.addOnChangeListener { _, value, _ ->
            TrackerConfig.velocityEMA = value
            tvVelocityEMA.text = "${"%.2f".format(value)}"
        }
        sliderDeadZone.addOnChangeListener { _, value, _ ->
            TrackerConfig.deadZone = value
            tvDeadZone.text = "${"%.1f".format(value)}px"
        }
    }

    // ── [지침 3,4] 필터 탭: 80개 체크박스 동적 생성 + 전체 선택/해제 ─────────

    private fun setupFiltersPanel(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.filterContainer)
        val ctx = requireContext()

        // [지침 3] COCO 80개 체크박스 — 2열 그리드 (LinearLayout rows)
        for (i in 0 until 80 step 2) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (j in i until minOf(i + 2, 80)) {
                val cb = CheckBox(ctx).apply {
                    text = "[$j] ${CocoLabels.get(j)}"
                    isChecked = TrackerConfig.classFilter[j]
                    setTextColor(Color.argb(220, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(6, 4, 6, 4)
                    // 체크박스 틱 색상: 청록색
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#00E5FF")
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val idx = j
                    setOnCheckedChangeListener { _, checked ->
                        TrackerConfig.classFilter[idx] = checked
                        TrackerConfig.saveClassFilter(requireContext())
                    }
                }
                checkBoxes[j] = cb
                row.addView(cb)
            }
            container.addView(row)
        }

        // [지침 4] 전체 선택 / 전체 해제
        view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            TrackerConfig.classFilter.fill(true)
            checkBoxes.forEach { it?.isChecked = true }
            TrackerConfig.saveClassFilter(requireContext())
        }
        view.findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            TrackerConfig.classFilter.fill(false)
            checkBoxes.forEach { it?.isChecked = false }
            TrackerConfig.saveClassFilter(requireContext())
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
