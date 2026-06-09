package com.ufi_toolswidget.util

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.ufi_toolswidget.R

/**
 * 主题色动态应用工具。
 * 将 ThemeColors 的配色方案写入到 Activity 的所有控件上。
 */
object ThemeUtil {

    /** 页面类型枚举，用于统一主题应用入口 */
    enum class PageType {
        MAIN,           // 主页
        SETTINGS_LIST,  // 设置列表页 (SettingsActivity)
        APP_SETTINGS,   // 软件设置页 (AppSettingsActivity)
        WIDGET_SETTINGS,// 小组件设置页
        FORM,           // 表单页 (配置修改、初始化)
        SECONDARY       // 通用二级页 (关于页等)
    }

    /**
     * 同步主题应用（无 post、无动画）。
     * 用于 [AnimationUtil.applyCircleRevealPulse] 的 mutation 内部，
     * 确保主题在截图与揭露之间同步完成，避免时序异常。
     */
    fun applyThemeSync(activity: Activity, type: PageType) {
        BackgroundUtil.initActivity(activity)
        when (type) {
            PageType.MAIN -> applyToMainActivity(activity)
            PageType.SETTINGS_LIST -> applyToSettingsActivity(activity)
            PageType.APP_SETTINGS -> applyToAppSettingsActivity(activity)
            PageType.WIDGET_SETTINGS -> applyToWidgetSettingsPage(activity)
            PageType.FORM -> applyToFormPage(activity)
            PageType.SECONDARY -> applyToSecondaryPage(activity)
        }
    }

    /**
     * 统一的主题应用入口。
     * 自动处理：窗口初始化 + 页面特定样式应用。
     * 注意：必须在 setContentView 之后调用，以便寻找视图。
     */
    fun applyTheme(activity: Activity, type: PageType) {
        try {
            BackgroundUtil.initActivity(activity)
            activity.window.decorView.post {
                try {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    when (type) {
                        PageType.MAIN -> applyToMainActivity(activity)
                        PageType.SETTINGS_LIST -> applyToSettingsActivity(activity)
                        PageType.APP_SETTINGS -> applyToAppSettingsActivity(activity)
                        PageType.WIDGET_SETTINGS -> applyToWidgetSettingsPage(activity)
                        PageType.FORM -> applyToFormPage(activity)
                        PageType.SECONDARY -> applyToSecondaryPage(activity)
                    }
                } catch (e: Exception) {
                    DebugLogger.logExc("ThemeUtil", "异步应用主题失败 [$type]: ${e.message}")
                }
            }
        } catch (e: Exception) {
            DebugLogger.logExc("ThemeUtil", "同步初始化主题失败: ${e.message}")
        }
    }

    /**
     * 对 Activity 的主页布局应用当前主题色。
     */
    private fun applyToMainActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val dataHighlight = ThemeColors.dataHighlight(ctx)
        val btnBg = ThemeColors.btnBg(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 设备名称（标题级，专用高亮色）──
        activity.findViewById<TextView>(R.id.main_tv_model)?.setTextColor(dataHighlight)

        // ── 版本副标题（注释灰字）──
        activity.findViewById<TextView>(R.id.main_tv_subtitle)?.setTextColor(textSecondary)

        // ── 设置入口：图标强调色 + 标签主色 ──
        activity.findViewById<ImageView>(R.id.btn_settings_icon)?.setColorFilter(ThemeColors.iconTint(ctx))
        activity.findViewById<TextView>(R.id.btn_settings_label)?.setTextColor(textPrimary)

        // ── 板块标题 → 主色 ──
        activity.findViewById<TextView>(R.id.tv_section_network)?.setTextColor(textPrimary)

        // ── 加载页文字：与流量标签（"今日已用"/"本月累计"）一致 → 辅色 ──
        activity.findViewById<TextView>(R.id.loading_title)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.loading_subtitle)?.setTextColor(textSecondary)
        activity.findViewById<TextView>(R.id.loading_hint)?.setTextColor(textSecondary)
        // 载入动画指示器取色（跟随主题 accent 色）
        (activity.findViewById<View>(R.id.loading_progress) as? com.ufi_toolswidget.view.LoadingAnimationView)?.applyThemeColors()

        // ── 数据网格标签（信号/温度/CPU/内存） → 注释灰字 ──
        val cardNetwork = activity.findViewById<ViewGroup>(R.id.card_network)
        if (cardNetwork != null) {
            // 手动指定标签着色，避免递归逻辑误伤
            val labelIds = listOf(R.id.main_item_network, R.id.main_item_temp, R.id.main_item_cpu, R.id.main_item_mem)
            labelIds.forEach { itemId ->
                activity.findViewById<ViewGroup>(itemId)?.let { item ->
                    // 每个 item 里的第一个 TextView 是标签 (小字)
                    for (i in 0 until item.childCount) {
                        val v = item.getChildAt(i)
                        if (v is ViewGroup) {
                            for (j in 0 until v.childCount) {
                                val subV = v.getChildAt(j)
                                if (subV is TextView && subV.textSize <= 11f * ctx.resources.displayMetrics.density) {
                                    subV.setTextColor(textSecondary)
                                }
                            }
                        }
                    }
                }
            }
            
            // 流量统计区域的标签 (今日已用 / 本月累计)
            val parentRow = activity.findViewById<TextView>(R.id.main_tv_daily)?.parent?.parent as? ViewGroup
            parentRow?.let { row ->
                for (i in 0 until row.childCount) {
                    val col = row.getChildAt(i) as? ViewGroup ?: continue
                    for (j in 0 until col.childCount) {
                        val v = col.getChildAt(j)
                        if (v is TextView && v.id != R.id.main_tv_daily && v.id != R.id.main_tv_flow) {
                            v.setTextColor(textSecondary)
                        }
                    }
                }
            }
        }

        // ── 数据网格图标 → 图标着色（随主题切换）──
        activity.findViewById<ImageView>(R.id.main_iv_antenna)?.setColorFilter(ThemeColors.iconTint(ctx))
        activity.findViewById<ImageView>(R.id.main_iv_temp)?.setColorFilter(ThemeColors.iconTint(ctx))
        activity.findViewById<ImageView>(R.id.main_iv_cpu)?.setColorFilter(ThemeColors.iconTint(ctx))
        activity.findViewById<ImageView>(R.id.main_iv_chip)?.setColorFilter(ThemeColors.iconTint(ctx))

        // ── 数据网格数值 → 正文 ──
        activity.findViewById<TextView>(R.id.main_tv_net_signal)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_temp)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_cpu)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_mem)?.setTextColor(textPrimary)

        // ── 今日已用 / 本月累计数字 → 专用高亮色 ──
        activity.findViewById<TextView>(R.id.main_tv_daily)?.setTextColor(dataHighlight)
        activity.findViewById<TextView>(R.id.main_tv_flow)?.setTextColor(dataHighlight)

        // ── 硬件参数区域 ──
        val cardDevice = activity.findViewById<ViewGroup>(R.id.card_device)
        if (cardDevice != null) {
            // 卡片背景
            cardDevice.background = makeCardBg(cardBg)
            // 硬件参数内所有文字：调深一点，使用 Primary 色
            applyTextColors(cardDevice, textPrimary, textPrimary) 
        }

        // ── 检查更新按钮（重构后的容器） ──
        activity.findViewById<View>(R.id.btn_check_update)?.let { v ->
            v.findViewById<View>(R.id.common_btn_text)?.let { text ->
                text.background = makeCardBg(btnBg, 12f)
                if (text is TextView) {
                    text.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }

        // ── 数据卡片背景 ──
        activity.findViewById<View>(R.id.card_network)?.background = makeCardBg(cardBg)
    }

    /**
     * 对 AppSettingsActivity 布局应用当前主题色（文字 + 分段控件由 Activity 自行管理）。
     */
    fun applyToAppSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val iconTint = ThemeColors.iconTint(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyTextColorsToContainer(root, textPrimary, textSecondary, accent, iconTint, cardBg)

        // 自定义颜色"应用"按钮已迁移至弹窗内动态创建，无需在此处理
    }

    /**
     * 对 SettingsActivity 布局应用当前主题色。
     */
    private fun applyToSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyCardListTheme(root, textPrimary, textSecondary, accent, cardBg)
    }

    /**
     * 对二级页面通用主题应用：文字色、返回按钮图标。
     * 各子 Activity 可额外调用控件级方法来处理 ToggleGroup/CheckBox/TextInputLayout 等。
     */
    private fun applyToSecondaryPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val iconTint = ThemeColors.iconTint(ctx)
        val btnBg = ThemeColors.btnBg(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, iconTint, cardBg, btnBg)

        // 统一处理检查更新按钮（公共组件库风格：btnBg + 白色文字）
        activity.findViewById<MaterialButton>(R.id.btn_check_update)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(btnBg)
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.strokeWidth = 0
            btn.strokeColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }

            
    }

    /** 对表单类页面（配置修改、初始化设置）应用主题：文字 + TextInputLayout + 保存按钮 */
    private fun applyToFormPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val iconTint = ThemeColors.iconTint(ctx)
        val btnBg = ThemeColors.btnBg(ctx)
        val cardBg = ThemeColors.cardBg(ctx)
        val dividerColor = ThemeColors.divider(ctx)
        val isDark = ThemeColors.isDark(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, iconTint, cardBg, btnBg)

        // 统一处理主要提交按钮（初始化设置）
        activity.findViewById<View>(R.id.btn_setup_confirm)?.let { v ->
            v.findViewById<View>(R.id.common_btn_text)?.let { text ->
                text.background = makeCardBg(btnBg, 12f)
                if (text is TextView) {
                    text.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }

        // 跳过按钮
        activity.findViewById<View>(R.id.tv_skip)?.let { v ->
            if (v is TextView) v.setTextColor(textSecondary)
        }

        // TextInputLayout 描边 (兼容旧版或其他页面)
        applyTextInputTheme(root, accent, textSecondary)

        // 新式输入框 (FrameLayout + EditText) 背景着色
        val inputBgColor = if (isDark) 0xFF333333.toInt() else 0xFFF2F2F2.toInt()
        
        // 查找所有 common_input_container 并着色
        findViewsWithId(root, R.id.common_input_container).forEach {
            it.backgroundTintList = ColorStateList.valueOf(inputBgColor)
        }

        // EditText Hint 着色
        val hintColor = ColorStateList.valueOf(textSecondary)
        findViewsWithId(root, R.id.common_input_edit_text).forEach {
            (it as? android.widget.EditText)?.setHintTextColor(hintColor)
        }
            
        // 查找并处理分隔线
        findAndThemeDividers(root, dividerColor)
    }

    /** 递归查找具有特定 ID 的所有视图 */
    private fun findViewsWithId(root: ViewGroup?, id: Int): List<View> {
        val result = mutableListOf<View>()
        if (root == null) return result
        for (child in root.children) {
            if (child.id == id) result.add(child)
            if (child is ViewGroup) result.addAll(findViewsWithId(child, id))
        }
        return result
    }

    /** 递归查找并处理分隔线（基于 alpha 0.1/0.12 且高度 1dp 的 View） */
    private fun findAndThemeDividers(root: ViewGroup?, color: Int) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                findAndThemeDividers(child, color)
            } else if (child.id == View.NO_ID && child.alpha < 0.2f && child.height <= 2 * density) {
                // 可能是分隔线
                child.setBackgroundColor(color)
            }
        }
    }

    /** 对小组件设置页应用主题：文字 + CheckBox + TextInputLayout */
    private fun applyToWidgetSettingsPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val iconTint = ThemeColors.iconTint(ctx)
        val btnBg = ThemeColors.btnBg(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, iconTint, cardBg, btnBg)
    }

    // ==================== 控件级辅助 ====================

    /**
     * 设置通用设置项卡片 (layout_common_setting_item)
     */
    fun setupSettingItem(view: View?, iconRes: Int, title: String, subtitle: String) {
        if (view == null) return
        view.findViewById<ImageView>(R.id.common_item_icon)?.setImageResource(iconRes)
        view.findViewById<TextView>(R.id.common_item_title)?.text = title
        view.findViewById<TextView>(R.id.common_item_subtitle)?.text = subtitle
    }

    /**
     * 设置通用输入框 (layout_common_input_field)
     */
    fun setupInputField(view: View?, title: String, subtitle: String, hint: String, inputType: Int) {
        if (view == null) return
        view.findViewById<TextView>(R.id.common_input_title)?.text = title
        view.findViewById<TextView>(R.id.common_input_subtitle)?.text = subtitle
        view.findViewById<android.widget.EditText>(R.id.common_input_edit_text)?.apply {
            setHint(hint)
            this.inputType = inputType
        }
    }

    /**
     * 设置通用开关 (layout_common_switch)
     */
    fun setupSwitch(view: View?, initialValue: Boolean, onCheckedChange: (Boolean) -> Unit) {
        if (view == null) return
        val track = view.findViewById<View>(R.id.common_switch_track)
        val thumb = view.findViewById<View>(R.id.common_switch_thumb)
        val ctx = view.context
        
        var isChecked = initialValue
        
        fun updateVisuals(animate: Boolean) {
            val accent = ThemeColors.accent(ctx)
            val offColor = ThemeColors.accentSecondary(ctx)
            
            val targetX = if (isChecked) (track.width - thumb.width - (thumb.layoutParams as ViewGroup.MarginLayoutParams).marginStart * 2).toFloat() else 0f
            val targetBg = if (isChecked) accent else offColor
            
            if (animate) {
                thumb.animate().translationX(targetX).setDuration(200).start()
                // 背景颜色渐变
                val anim = android.animation.ValueAnimator.ofArgb(
                    (track.background as? GradientDrawable)?.color?.defaultColor ?: offColor, 
                    targetBg
                )
                anim.duration = 200
                anim.addUpdateListener { track.background = makeCardBg(it.animatedValue as Int, 100f) }
                anim.start()
            } else {
                track.post {
                    val margin = (thumb.layoutParams as ViewGroup.MarginLayoutParams).marginStart
                    thumb.translationX = if (isChecked) (track.width - thumb.width - margin * 2).toFloat() else 0f
                    track.background = makeCardBg(targetBg, 100f)
                }
            }
        }
        
        updateVisuals(false)
        
        track.setOnClickListener {
            isChecked = !isChecked
            updateVisuals(true)
            onCheckedChange(isChecked)
        }

        // 暴露 setChecked 引用，供外部（如 onResume 状态同步）可靠地设置开关状态
        track.tag = { value: Boolean ->
            isChecked = value
            updateVisuals(false)
        }
    }

    /**
     * 静默设置开关视觉状态和内部 isChecked，不触发 onCheckedChange 回调。
     * 用于互斥警告弹窗中回退/恢复开关状态，避免因 performClick 再次触发回调导致弹窗循环。
     * 通过 track.tag 中存储的 setChecked lambda 同步闭包内的 isChecked 变量。
     */
    fun setSwitchVisualSilently(view: View?, checked: Boolean) {
        if (view == null) return
        val track = view.findViewById<View>(R.id.common_switch_track) ?: return
        // 先通过 track.tag 同步闭包内的 isChecked（不触发 onCheckedChange）
        @Suppress("UNCHECKED_CAST")
        val setChecked = track.tag as? ((Boolean) -> Unit)
        setChecked?.invoke(checked)
        // setChecked 内部已调用 updateVisuals(false) 设置视觉，
        // 但如果 updateVisuals 走了 track.post 非动画路径，这里补充一次动画过渡
        val thumb = view.findViewById<View>(R.id.common_switch_thumb) ?: return
        val ctx = view.context
        val accent = ThemeColors.accent(ctx)
        val offColor = ThemeColors.accentSecondary(ctx)
        val targetColor = if (checked) accent else offColor
        val margin = (thumb.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        val targetX = if (checked) (track.width - thumb.width - margin * 2).toFloat() else 0f
        thumb.animate().translationX(targetX).setDuration(200).start()
        val anim = android.animation.ValueAnimator.ofArgb(
            (track.background as? GradientDrawable)?.color?.defaultColor ?: offColor,
            targetColor
        )
        anim.duration = 200
        anim.addUpdateListener {
            val c = it.animatedValue as Int
            track.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(c)
                cornerRadius = 100f
            }
        }
        anim.start()
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int = (dp * ctx.resources.displayMetrics.density).toInt()

    /** 给 ToggleGroup 中的每个子按钮设置描边色和文字色 */
    private fun applyToggleGroupTheme(toggleGroup: MaterialButtonToggleGroup?, accent: Int, textColor: Int) {
        if (toggleGroup == null) return
        for (i in 0 until toggleGroup.childCount) {
            val child = toggleGroup.getChildAt(i)
            if (child is MaterialButton) {
                child.strokeColor = ColorStateList.valueOf(accent)
                child.setTextColor(textColor)
            }
        }
    }

    /** 遍历容器中所有 MaterialCheckBox，设置勾选框和文字主题色 */
    private fun applyCheckBoxesTheme(root: ViewGroup?, accent: Int, textColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is CheckBox) {
                child.buttonTintList = ColorStateList.valueOf(accent)
                child.setTextColor(textColor)
            } else if (child is ViewGroup) {
                applyCheckBoxesTheme(child, accent, textColor)
            }
        }
    }

    /** 遍历容器中所有 TextInputLayout，设置描边色和提示文字色 */
    private fun applyTextInputTheme(root: ViewGroup?, accent: Int, hintColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is TextInputLayout) {
                child.setBoxStrokeColorStateList(ColorStateList.valueOf(accent))
                child.hintTextColor = ColorStateList.valueOf(hintColor)
                child.defaultHintTextColor = ColorStateList.valueOf(hintColor)
            } else if (child is ViewGroup) {
                applyTextInputTheme(child, accent, hintColor)
            }
        }
    }

    // ==================== 内部辅助 ====================

    /**
     * 递归遍历容器，根据 textSize 自动区分标题/正文/注释。
     */
    private fun applyTextColorsToContainer(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        iconTint: Int,
        cardBg: Int
    ) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                // 判断是否为卡片容器（有背景）
                // 跳过开关轨道（由 setupSwitch 管理背景色）
                val bg = child.background
                if (bg != null && child.id != R.id.common_switch_track) {
                    try { child.background = makeCardBg(cardBg) } catch (e: Exception) { DebugLogger.w("ThemeUtil", "applyTextColors cardBg failed: ${e.message}") }
                }
                applyTextColorsToContainer(child, textPrimary, textSecondary, accent, iconTint, cardBg)
            }
            if (child is TextView) {
                // 统一阈值逻辑：标题(>20sp) → 主色，正文(14-20sp) → 主色，注释(≤13.5sp) → 副色
                when {
                    child.textSize > 20f * density -> child.setTextColor(textPrimary)
                    child.textSize <= 13.6f * density -> child.setTextColor(textSecondary)
                    else -> child.setTextColor(textPrimary)
                }
            }
            // 图标 ImageView 着色
            if (child is ImageView) {
                // 跳过大图标（如关于页的应用图标，通常 > 48dp）
                val isLargeIcon = child.width > 150 || child.height > 150 || child.id == R.id.iv_app_icon
                if (!isLargeIcon) {
                    // 箭头用次要色，设置项主图标用品牌/强调色
                    if (child.rotation == 180f || child.alpha < 0.5f) {
                        child.setColorFilter(textSecondary)
                    } else {
                        child.setColorFilter(iconTint)
                    }
                } else {
                    child.clearColorFilter()
                }
            }
            // 返回按钮图标着色
            if (child is android.widget.Button && child.id == R.id.btn_back) {
                (child as? MaterialButton)?.iconTint = ColorStateList.valueOf(iconTint)
            }
        }
    }

    /**
     * 对卡片列表布局（SettingsActivity）应用主题色。
     * 特点：卡片容器有 heading + description + 后箭头图标。
     * 与 applySecondaryTextColors 对齐：图标用 iconTint、文字全覆盖。
     */
    private fun applyCardListTheme(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        val iconTint = ThemeColors.iconTint(root.context)
        for (child in root.children) {
            if (child is ViewGroup) {
                val isCard = child.id != android.R.id.content && child.childCount >= 2
                if (isCard) {
                    // 尝试应用卡片背景
                    try {
                        if (child.background != null) child.background = makeCardBg(cardBg)
                    } catch (e: Exception) { DebugLogger.w("ThemeUtil", "applyCardListTheme cardBg failed: ${e.message}") }
                }
                applyCardListTheme(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView) {
                when {
                    child.textSize > 20f * density -> child.setTextColor(textPrimary)
                    child.textSize > 13.6f * density -> child.setTextColor(textPrimary)
                    else -> child.setTextColor(textSecondary)
                }
            }
            if (child is ImageView) {
                // 卡片左边的功能图标用 iconTint，右边箭头用次要色
                if (child.rotation == 180f || child.alpha < 0.5f) {
                    child.setColorFilter(textSecondary)
                } else {
                    child.setColorFilter(iconTint)
                }
            }
            // 返回按钮图标着色
            if (child is android.widget.Button && child.id == R.id.btn_back) {
                (child as? MaterialButton)?.iconTint = ColorStateList.valueOf(iconTint)
            }
        }
    }

    /**
     * 对二级页面递归着色文字和图标（通用）。
     * 规则：标题（>20sp）→ 主色，内容（14-20sp）→ 主色，注释（≤13sp）→ 副色
     */
    private fun applySecondaryTextColors(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        iconTint: Int,
        cardBg: Int,
        btnBg: Int = textPrimary
    ) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                // 子卡片容器应用圆角背景
                // 跳过开关轨道（由 setupSwitch 管理背景色），避免主题刷新时覆盖开关状态
                try {
                    if (child.background != null
                        && child.id != R.id.common_switch_track) {
                        child.background = makeCardBg(cardBg)
                    }
                } catch (e: Exception) { DebugLogger.w("ThemeUtil", "applySecondaryTextColors cardBg failed: ${e.message}") }
                applySecondaryTextColors(child, textPrimary, textSecondary, accent, iconTint, cardBg, btnBg)
            }
            if (child is TextView && child !is android.widget.Button && child.id != android.R.id.text1 && child.id != R.id.common_btn_text) {
                // 跳过系统下拉列表项和通用动作按钮文字（这些由具体页面或 applyTheme 逻辑单独处理）
                when {
                    child.textSize > 20f * density -> child.setTextColor(textPrimary)
                    child.textSize <= 13.6f * density -> child.setTextColor(textSecondary)
                    else -> child.setTextColor(textPrimary)
                }
            }
            if (child is ImageView) {
                // 跳过大图标（如关于页的应用图标，通常 > 48dp）
                val isLargeIcon = child.width > 150 || child.height > 150 || child.id == R.id.iv_app_icon
                if (!isLargeIcon) {
                    // 箭头用次要色，设置项图标用强调色（与 SettingsActivity/AppSettingsActivity 一致）
                    if (child.rotation == 180f || child.alpha < 0.5f) {
                        child.setColorFilter(textSecondary)
                    } else {
                        child.setColorFilter(iconTint)
                    }
                } else {
                    child.clearColorFilter()
                }
            }
            // 按钮主题应用
            if (child is android.widget.Button) {
                val mb = child as? MaterialButton
                when {
                    child.id == R.id.btn_back -> {
                        mb?.iconTint = ColorStateList.valueOf(iconTint)
                    }
                    (mb?.strokeWidth ?: 0) > 0 -> {
                        // 描边按钮 (OutlinedButton)
                        child.setTextColor(textPrimary)
                        mb?.strokeColor = ColorStateList.valueOf(textSecondary)
                        mb?.iconTint = ColorStateList.valueOf(iconTint)
                    }
                    mb != null -> {
                        // 填充/色调按钮 (FilledButton/TonalButton)：背景色 + 白色文字
                        mb.backgroundTintList = ColorStateList.valueOf(btnBg)
                        mb.setTextColor(0xFFFFFFFF.toInt())
                        mb.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    }
                }
            }
        }
    }

    /**
     * 在指定容器中递归查找所有标签级 TextView（小字），着色为 subText 色。
     * 规则：每个叶子分支的第一个小字(≤13sp) TextView 视为标签。
     */
    private fun applyTextColorToLabels(root: ViewGroup?, labelColor: Int) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                colorFirstLabelInBranch(child, labelColor)
            }
        }
    }

    /** 在 ViewGroup 子树中找到第一个小字 TextView 并着色，找到后返回 true */
    private fun colorFirstLabelInBranch(parent: ViewGroup, color: Int): Boolean {
        val density = parent.resources.displayMetrics.density
        for (child in parent.children) {
            if (child is TextView && child.textSize <= 14f * density) {
                child.setTextColor(color)
                return true
            }
            if (child is ViewGroup) {
                if (colorFirstLabelInBranch(child, color)) return true
            }
        }
        return false
    }

    /**
     * 遍历容器中所有 TextView，根据 textSize 自动区分主/辅色。
     */
    private fun applyTextColors(root: ViewGroup?, textPrimary: Int, textSecondary: Int) {
        if (root == null) return
        val density = root.resources.displayMetrics.density
        for (child in root.children) {
            if (child is ViewGroup) {
                applyTextColors(child, textPrimary, textSecondary)
            }
            if (child is TextView) {
                // 小字 → 辅色，大字 → 主色
                if (child.textSize <= 14f * density) {
                    child.setTextColor(textSecondary)
                } else {
                    child.setTextColor(textPrimary)
                }
            }
        }
    }

    /** 创建圆角卡片背景 */
    private fun makeCardBg(color: Int, radius: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }
}
