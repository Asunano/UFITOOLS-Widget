package com.ufi_toolswidget

import android.content.BroadcastReceiver
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.ToastUtil
import com.ufi_toolswidget.util.ToastStyle
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.widget.BaseWifiWidget

class WidgetDynamicColorActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        setContentView(R.layout.activity_widget_dynamic_color)

        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            AnimationUtil.applyCircleRevealPulse(this@WidgetDynamicColorActivity) {
                ThemeUtil.applyThemeSync(this@WidgetDynamicColorActivity, ThemeUtil.PageType.SETTINGS_LIST)
            }
        }

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initDynamicColorItem()
        initDynamicContrastItem()
        initDynamicAdvancedItem()
        initDynamicColorSourceItem()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        // 每次恢复时重新检查背景图状态
        updateDynamicBackgroundLockState()
        updateDynamicContrastSubtitle()
        updateDynamicAdvancedSubtitle()
        updateDynamicColorSourceSubtitle()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    /** 用户是否已设置并启用了小组件背景图 */
    private fun hasWidgetBgImage(): Boolean {
        return SPUtil.getWidgetBgImageUri(this).isNotBlank()
            && SPUtil.getWidgetBgImageEnabled(this)
    }

    /** 检查背景图状态，没设背景时禁用动态配色并提示 */
    private fun updateDynamicBackgroundLockState() {
        val hasBg = hasWidgetBgImage()
        val item = findViewById<View>(R.id.item_widget_dynamic_color)
        val track = item.findViewById<View>(R.id.common_switch_track)
        val subtitle = item.findViewById<android.widget.TextView>(R.id.common_switch_subtitle)

        if (!hasBg) {
            SPUtil.setWidgetDynamicColor(this, false)
            subtitle?.apply {
                text = "请先返回「小组件设置」中设置背景图片后再启用"
                visibility = View.VISIBLE
            }
            // 如果开关视觉上处于 ON，通过 setChecked 引用同步为 OFF
            @Suppress("UNCHECKED_CAST")
            val setChecked = track?.tag as? ((Boolean) -> Unit)
            setChecked?.invoke(false)
            track?.isEnabled = false
            track?.alpha = 0.4f
            updateDynamicColorVisibility(false)
        } else {
            subtitle?.visibility = View.GONE
            track?.isEnabled = true
            track?.alpha = 1f
            updateDynamicColorVisibility(SPUtil.getWidgetDynamicColor(this))
        }
    }

    // ==================== 1. 动态配色 Material You（开关） ====================
    private fun initDynamicColorItem() {
        val dynamicColorItem = findViewById<View>(R.id.item_widget_dynamic_color)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            dynamicColorItem.visibility = View.GONE
            return
        }

        val hasBg = hasWidgetBgImage()

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = dynamicColorItem,
            iconRes = R.drawable.ic_dynamic_colors,
            label = "动态配色 (Material You)",
            subtitle = if (hasBg) "根据壁纸主色自动适配文字和图标颜色" else "请先返回「小组件设置」中设置背景图片后再启用",
            initialChecked = hasBg && SPUtil.getWidgetDynamicColor(this),
            onToggle = { checked ->
                // 每次回调都重新检查背景状态，防止用户从设置页修改后此处仍用旧值
                if (!hasWidgetBgImage()) {
                    // 背景已被关闭，静默回退开关视觉（不触发回调）
                    ThemeUtil.setSwitchVisualSilently(dynamicColorItem, false)
                    return@setupSwitchItem
                }
                // 检测所有可能的主题冲突：跟随主题 / 手动深浅色 / 手动配色
                val hasThemeConflict = checked && (
                    SPUtil.getWidgetFollowAppTheme(this)
                        || SPUtil.getWidgetTheme(this) != "follow_app"
                        || SPUtil.getWidgetColorThemeIndex(this) != 0
                )
                if (hasThemeConflict) {
                    CommonDialogHelper.showWarningConfirmDialog(
                        context = this,
                        title = "互斥提醒",
                        message = "开启「动态配色」将自动关闭「跟随应用主题」及手动配色设置，由壁纸颜色独立控制配色方案。三种配色模式只能开启一种。",
                        confirmText = "继续开启",
                        cancelText = "取消",
                        onConfirm = {
                            SPUtil.setWidgetDynamicColor(this, true)
                            SPUtil.setWidgetFollowAppTheme(this, false)
                            updateDynamicColorVisibility(true)
                            BaseWifiWidget.renderAllWidgets(this, force = true)
                            // 静默恢复开关视觉为 ON（不触发回调，避免重复弹窗）
                            ThemeUtil.setSwitchVisualSilently(dynamicColorItem, true)
                        }
                    )
                    // 用户尚未确认，静默回退开关视觉（不触发回调）
                    ThemeUtil.setSwitchVisualSilently(dynamicColorItem, false)
                    return@setupSwitchItem
                }
                SPUtil.setWidgetDynamicColor(this, checked)
                updateDynamicColorVisibility(checked)
                BaseWifiWidget.renderAllWidgets(this, force = true)
            }
        )

        if (!hasBg) {
            val track = dynamicColorItem.findViewById<View>(R.id.common_switch_track)
            track?.isEnabled = false
            track?.alpha = 0.4f
        }

        updateDynamicColorVisibility(hasBg && SPUtil.getWidgetDynamicColor(this))
    }

    private fun updateDynamicColorVisibility(enabled: Boolean) {
        val show = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        findViewById<View>(R.id.item_widget_dynamic_contrast).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.item_widget_dynamic_advanced).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.item_widget_dynamic_color_source).visibility = if (show) View.VISIBLE else View.GONE
    }

    // ==================== 2. 动态配色对比度 ====================
    private fun initDynamicContrastItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_contrast),
            iconRes = R.drawable.ic_eye,
            title = "动态配色对比度",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicContrastDialog
        )
        updateDynamicContrastSubtitle()
    }

    private fun updateDynamicContrastSubtitle() {
        val levelName = when (SPUtil.getWidgetDynamicContrast(this)) {
            0 -> "柔和"; 1 -> "标准"; 2 -> "强烈"; else -> "标准"
        }
        try {
            findViewById<View>(R.id.item_widget_dynamic_contrast)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = levelName
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set contrast subtitle failed: ${e.message}") }
    }

    private fun showDynamicContrastDialog() {
        val currentLevel = SPUtil.getWidgetDynamicContrast(this)
        val density = resources.displayMetrics.density
        val cornerRadius = 12f * density

        CommonDialogHelper.showSelectionDialog(
            this,
            title = "动态配色对比度",
            iconRes = R.drawable.ic_eye,
            onFill = { content, dialog ->
                val textPrimary = ThemeColors.textPrimary(this@WidgetDynamicColorActivity)
                val accent = ThemeColors.accent(this@WidgetDynamicColorActivity)
                val selectedBg = CommonDialogHelper.createSelectedBg(accent, cornerRadius)
                val unselectedBg = CommonDialogHelper.createUnselectedBg(this@WidgetDynamicColorActivity, cornerRadius)

                val options = listOf(
                    Triple(0, "柔和", "低对比度，色彩更柔和，适合浅色壁纸"),
                    Triple(1, "标准", "中等对比度，平衡可读性与美观"),
                    Triple(2, "强烈", "高对比度，文字更清晰，色彩更鲜明")
                )
                options.forEach { (level, label, desc) ->
                    val isSelected = level == currentLevel
                    val itemLayout = createOptionItem(label, desc, textPrimary, if (isSelected) selectedBg else unselectedBg, isSelected)
                    itemLayout.setOnClickListener {
                        SPUtil.setWidgetDynamicContrast(this@WidgetDynamicColorActivity, level)
                        updateDynamicContrastSubtitle()
                        BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
                        dialog.dismiss()
                    }
                    content.addView(itemLayout)
                }
            }
        )
    }

    // ==================== 3. 动态配色高级设置 ====================
    private fun initDynamicAdvancedItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_advanced),
            iconRes = R.drawable.ic_settings,
            title = "高级参数调节",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicAdvancedDialog
        )
        updateDynamicAdvancedSubtitle()
    }

    private fun updateDynamicAdvancedSubtitle() {
        val advanced = SPUtil.getWidgetDynamicAdvanced(this)
        val label = if (advanced) {
            val lBg = SPUtil.getDynAdvLightBg(this)
            val lTx = SPUtil.getDynAdvLightTxt(this)
            val dBg = SPUtil.getDynAdvDarkBg(this)
            val dTx = SPUtil.getDynAdvDarkTxt(this)
            val sat = SPUtil.getDynAdvSatBoost(this)
            "浅底$lBg/文$lTx · 深底$dBg/文$dTx · 饱和$sat%"
        } else "关闭"
        try {
            findViewById<View>(R.id.item_widget_dynamic_advanced)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set advanced subtitle failed: ${e.message}") }
    }

    private fun showDynamicAdvancedDialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        dialog.findViewById<android.widget.TextView>(R.id.common_dialog_title).text = "高级参数调节"
        dialog.findViewById<android.widget.ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_settings)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<android.widget.LinearLayout>(R.id.common_dialog_content)
        val scrollContainer = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }
        val innerContent = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContainer.addView(innerContent)
        content.addView(scrollContainer)

        var advancedEnabled = SPUtil.getWidgetDynamicAdvanced(this)
        val slidersContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            alpha = if (advancedEnabled) 1f else 0.4f
            isEnabled = advancedEnabled
        }

        var lightBg = SPUtil.getDynAdvLightBg(this).toFloat()
        var lightTxt = SPUtil.getDynAdvLightTxt(this).toFloat()
        var darkBg = SPUtil.getDynAdvDarkBg(this).toFloat()
        var darkTxt = SPUtil.getDynAdvDarkTxt(this).toFloat()
        var satBoost = SPUtil.getDynAdvSatBoost(this).toFloat()

        fun addSlider(title: String, desc: String, min: Float, max: Float, default: Float, suffix: String = "",
                      tickStep: Float = 0f,
                      onUpdate: (Float) -> Unit): Pair<android.widget.TextView, com.ufi_toolswidget.view.ThemeSlider> {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(14) }
            }
            val label = android.widget.TextView(this).apply {
                text = "$title: ${default.toInt()}$suffix"
                textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ThemeColors.textPrimary(this@WidgetDynamicColorActivity))
            }
            container.addView(label)
            container.addView(android.widget.TextView(this).apply {
                text = desc; textSize = 11f; alpha = 0.7f
                setTextColor(ThemeColors.textSecondary(this@WidgetDynamicColorActivity))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(2) }
            })
            val slider = com.ufi_toolswidget.view.ThemeSlider(this).apply {
                minValue = min; maxValue = max; stepSize = 1f
                currentValue = default
                isEnabled = advancedEnabled
                if (tickStep > 0f) {
                    tickStepSize = tickStep
                    tickLabelFormatter = { v -> "${v.toInt()}$suffix" }
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44)
                )
                onValueChanging = { v ->
                    label.text = "$title: ${v.toInt()}$suffix"
                    onUpdate(v)
                }
            }
            container.addView(slider)
            slidersContainer.addView(container)
            return Pair(label, slider)
        }

        // 先创建所有滑块（存入列表以便开关回调引用）
        val sliders = listOf(
            addSlider("浅色背景亮度", "值越高背景越亮", 85f, 99f, lightBg, tickStep = 2f) { v -> lightBg = v },
            addSlider("浅色文字亮度", "值越低文字越深，对比度越高", 0f, 40f, lightTxt, tickStep = 5f) { v -> lightTxt = v },
            addSlider("深色背景亮度", "值越低背景越暗", 4f, 20f, darkBg, tickStep = 4f) { v -> darkBg = v },
            addSlider("深色文字亮度", "值越高文字越亮，对比度越高", 75f, 98f, darkTxt, tickStep = 5f) { v -> darkTxt = v },
            addSlider("饱和度增强", "100%为原始，>100%增强色彩鲜艳度", 50f, 150f, satBoost, "%", tickStep = 40f) { v -> satBoost = v }
        )

        val defaultSliderValues = listOf(97f, 12f, 8f, 90f, 100f)

        // 再创建开关（回调中引用 sliders 列表，此时已定义）
        val switchWrapper = layoutInflater.inflate(R.layout.layout_common_switch, innerContent, false)
        switchWrapper.findViewById<android.widget.TextView>(R.id.common_switch_label).text = "启用高级调节"
        val switchTrack = switchWrapper.findViewById<View>(R.id.common_switch_track)
        com.ufi_toolswidget.util.ThemeUtil.setupSwitch(switchWrapper, advancedEnabled) { isChecked ->
            advancedEnabled = isChecked
            slidersContainer.alpha = if (isChecked) 1f else 0.4f
            slidersContainer.isEnabled = isChecked
            // 同步每个滑块自身的 enabled 状态（容器 disabled 不阻断自定义 View 触摸）
            sliders.forEach { (_, slider) -> slider.isEnabled = isChecked }
        }
        innerContent.addView(switchWrapper)

        innerContent.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp2px(12); bottomMargin = dp2px(12) }
            setBackgroundColor(ThemeColors.divider(this@WidgetDynamicColorActivity))
            alpha = 0.3f
        })

        innerContent.addView(slidersContainer)

        CommonDialogHelper.applyThemeToViewTree(innerContent, this)

        val btnContainer = dialog.findViewById<android.widget.LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE
        AnimationUtil.applyScaleClickAnimation(
            dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
                text = "确定"
            }
        ) {
            SPUtil.setWidgetDynamicAdvanced(this@WidgetDynamicColorActivity, advancedEnabled)
            if (advancedEnabled) {
                SPUtil.setDynAdvLightBg(this@WidgetDynamicColorActivity, lightBg.toInt())
                SPUtil.setDynAdvLightTxt(this@WidgetDynamicColorActivity, lightTxt.toInt())
                SPUtil.setDynAdvDarkBg(this@WidgetDynamicColorActivity, darkBg.toInt())
                SPUtil.setDynAdvDarkTxt(this@WidgetDynamicColorActivity, darkTxt.toInt())
                SPUtil.setDynAdvSatBoost(this@WidgetDynamicColorActivity, satBoost.toInt())
            }
            updateDynamicAdvancedSubtitle()
            BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
            dialog.dismiss()
        }
        AnimationUtil.applyScaleClickAnimation(
            dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
                visibility = View.VISIBLE; text = "恢复默认"
            }
        ) {
            // 重置开关为关闭状态
            if (advancedEnabled) {
                switchTrack.performClick()
            }
            // 重置滑块数值为出厂默认值
            sliders.forEachIndexed { i, (_, slider) ->
                slider.currentValue = defaultSliderValues[i]
            }
            lightBg = 97f; lightTxt = 12f; darkBg = 8f; darkTxt = 90f; satBoost = 100f
            // 不保存 SPUtil、不刷新小组件、不关闭弹窗
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
    }

    // ==================== 4. 动态配色色源选择 ====================
    private fun initDynamicColorSourceItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_color_source),
            iconRes = R.drawable.ic_palette,
            title = "动态配色色源",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicColorSourceDialog
        )
        updateDynamicColorSourceSubtitle()
    }

    private fun updateDynamicColorSourceSubtitle() {
        val names = listOf("Primary (主色)", "Secondary (次色)", "Tertiary (第三色)", "Neutral (中性色)", "Neutral Variant (中性变体)")
        val source = SPUtil.getWidgetDynamicColorSource(this)
        val name = names.getOrElse(source) { "Primary (主色)" }
        try {
            findViewById<View>(R.id.item_widget_dynamic_color_source)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = name
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set color source subtitle failed: ${e.message}") }
    }

    private fun showDynamicColorSourceDialog() {
        val currentSource = SPUtil.getWidgetDynamicColorSource(this)
        val density = resources.displayMetrics.density
        val cornerRadius = 12f * density

        // 先检查能否从小组件背景图提取颜色
        val availableColors = ThemeColors.getAvailableWallpaperColors(this@WidgetDynamicColorActivity)
        val hasValidColor = availableColors.any { (_, color) -> color != null }
        if (!hasValidColor) {
            DebugLogger.w("WidgetDynamicColorActivity", "无法从小组件背景图提取色源颜色")
            ToastUtil.showDropToast(this@WidgetDynamicColorActivity, ToastStyle.WARNING, "无法提取背景图颜色，请检查小组件背景图片设置")
            return
        }

        CommonDialogHelper.showSelectionDialog(
            this,
            title = "动态配色色源",
            iconRes = R.drawable.ic_palette,
            onFill = { content, dialog ->
                val textPrimary = ThemeColors.textPrimary(this@WidgetDynamicColorActivity)
                val accent = ThemeColors.accent(this@WidgetDynamicColorActivity)
                val selectedBg = CommonDialogHelper.createSelectedBg(accent, cornerRadius)
                val unselectedBg = CommonDialogHelper.createUnselectedBg(this@WidgetDynamicColorActivity, cornerRadius)

                availableColors.forEachIndexed { index, (name, color) ->
                    val isSelected = index == currentSource
                    val itemLayout = createColorSourceOptionItem(name, color, textPrimary, if (isSelected) selectedBg else unselectedBg, isSelected)
                    itemLayout.setOnClickListener {
                        SPUtil.setWidgetDynamicColorSource(this@WidgetDynamicColorActivity, index)
                        updateDynamicColorSourceSubtitle()
                        BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
                        dialog.dismiss()
                    }
                    content.addView(itemLayout)
                }
            }
        )
    }

    private fun createColorSourceOptionItem(
        label: String, color: Int?, textPrimary: Int, bg: android.graphics.drawable.GradientDrawable, isSelected: Boolean
    ): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(6) }
            background = bg
            isClickable = true
            isFocusable = true

            addView(android.view.View(this@WidgetDynamicColorActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp2px(14), dp2px(14))
                val dotColor = color ?: 0xFF888888.toInt()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                    if (isSelected) setStroke(dp2px(1), 0xFFFFFFFF.toInt())
                }
            })

            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = label
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp2px(10) }
            })
        }
    }

    // ==================== 工具方法 ====================

    private fun createOptionItem(
        label: String, desc: String, textPrimary: Int, bg: android.graphics.drawable.GradientDrawable, isSelected: Boolean
    ): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(12), dp2px(14), dp2px(12), dp2px(14))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
            background = bg; isClickable = true; isFocusable = true
            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = label; textSize = 15f; gravity = android.view.Gravity.CENTER
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            })
            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = desc; textSize = 11f; gravity = android.view.Gravity.CENTER
                alpha = if (isSelected) 0.85f else 0.55f
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(4) }
            })
        }
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
