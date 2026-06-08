package com.ufi_toolswidget.util

import android.content.Context
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.ufi_toolswidget.view.ThemeSlider

object ThemedSliderUtil {

    data class SliderConfig(
        val minValue: Float = 5f,
        val maxValue: Float = 120f,
        val stepSize: Float = 1f,
        val defaultValue: Float = 5f,
        val valueSuffix: String = " 秒"
    )

    fun createSlider(
        context: Context,
        config: SliderConfig = SliderConfig(),
        onValueChange: (Float) -> Unit = {}
    ): Pair<Slider, TextView> {
        val accent = ThemeColors.accent(context)
        val textPrimary = ThemeColors.textPrimary(context)
        val textSecondary = ThemeColors.textSecondary(context)

        val label = TextView(context).apply {
            text = "${config.defaultValue.toInt()}${config.valueSuffix}"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(accent)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val slider = Slider(context).apply {
            valueFrom = config.minValue
            valueTo = config.maxValue
            stepSize = config.stepSize
            value = config.defaultValue.coerceIn(config.minValue, config.maxValue)

            setLabelBehavior(0)

            val trackActiveColor = accent
            val trackInactiveColor = (accent and 0x00FFFFFF) or 0x26000000
            val thumbColor = accent

            trackActiveTintList = ColorStateList.valueOf(trackActiveColor)
            trackInactiveTintList = ColorStateList.valueOf(trackInactiveColor)
            thumbTintList = ColorStateList.valueOf(thumbColor)
            haloTintList = ColorStateList.valueOf(accent)

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(context, 8) }

            addOnChangeListener { _, value, _ ->
                label.text = "${value.toInt()}${config.valueSuffix}"
                onValueChange(value)
            }
        }

        return slider to label
    }

    private fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    // ═══════════════════════════════════════════
    // 2. 自定义 ThemeSlider 刻度辅助
    // ═══════════════════════════════════════════

    /**
     * 为 [ThemeSlider] 配置刻度显示，自动调整高度以容纳刻度标签。
     *
     * @param slider      目标 ThemeSlider
     * @param tickStep    刻度步长（如 15 表示每隔 15 画一个刻度点）
     * @param formatter   刻度标签格式化，传 null 则不显示标签
     */
    fun setupSliderTickMarks(
        slider: ThemeSlider,
        tickStep: Float,
        formatter: ((Float) -> String)? = null
    ) {
        slider.tickStepSize = tickStep
        slider.tickLabelFormatter = formatter

        // 调整高度以容纳刻度标签
        val extraHeight = if (formatter != null) 36 else 14
        val newHeight = (44 + extraHeight) * slider.resources.displayMetrics.density
        slider.layoutParams.height = newHeight.toInt()
    }
}