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

    /**
     * 对 Activity 的主页布局应用当前主题色。
     */
    fun applyToMainActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 设备名称（标题级，强调色）──
        activity.findViewById<TextView>(R.id.main_tv_model)?.setTextColor(accent)

        // ── 版本副标题（注释灰字）──
        activity.findViewById<TextView>(R.id.main_tv_subtitle)?.setTextColor(textSecondary)

        // ── 检查更新按钮 ──
        activity.findViewById<TextView>(R.id.btn_check_update)?.setTextColor(textPrimary)

        // ── 设置图标按钮 ──
        val btnSettings = activity.findViewById<MaterialButton>(R.id.btn_settings)
        btnSettings?.iconTint = ColorStateList.valueOf(accent)

        // ── 数据网格标签（信号/温度/CPU/内存） → 注释灰字 ──
        val gridLabels = activity.findViewById<ViewGroup>(R.id.card_network)
        applyTextColorToLabels(gridLabels, textSecondary)

        // ── 数据网格图标 → 强调色（随主题切换）──
        activity.findViewById<ImageView>(R.id.main_iv_antenna)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_temp)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_cpu)?.setColorFilter(accent)
        activity.findViewById<ImageView>(R.id.main_iv_chip)?.setColorFilter(accent)

        // ── 数据网格数值 → 正文 ──
        activity.findViewById<TextView>(R.id.main_tv_net_signal)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_temp)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_cpu)?.setTextColor(textPrimary)
        activity.findViewById<TextView>(R.id.main_tv_mem)?.setTextColor(textPrimary)

        // ── 今日已用 / 本月累计数字 → 强调色 ──
        activity.findViewById<TextView>(R.id.main_tv_daily)?.setTextColor(accent)
        activity.findViewById<TextView>(R.id.main_tv_flow)?.setTextColor(accent)

        // ── 硬件参数区域 ──
        val cardDevice = activity.findViewById<ViewGroup>(R.id.card_device)
        if (cardDevice != null) {
            // 卡片背景
            cardDevice.background = makeCardBg(cardBg)
            // 硬件参数内所有文字
            applyTextColors(cardDevice, textPrimary, textSecondary)
        }

        // ── 数据卡片背景 ──
        activity.findViewById<View>(R.id.card_network)?.background = makeCardBg(cardBg)

        // ── 错误状态覆盖层（与主界面统一主题）──
        applyErrorStateTheme(activity, textPrimary, textSecondary, accent, cardBg)
    }

    /** 对错误状态覆盖层应用当前主题色 */
    private fun applyErrorStateTheme(
        activity: Activity,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        // 错误卡片背景（与主界面卡片一致）
        activity.findViewById<View>(R.id.error_card)?.background = makeCardBg(cardBg)

        // 错误标题 → 主文字色
        activity.findViewById<TextView>(R.id.error_title)?.setTextColor(textPrimary)

        // 错误描述 → 副文字色
        activity.findViewById<TextView>(R.id.error_message)?.setTextColor(textSecondary)

        // 连接目标文字 → 主文字色
        activity.findViewById<TextView>(R.id.error_target)?.setTextColor(textPrimary)

        // 连接目标图标 → 强调色
        activity.findViewById<ImageView>(R.id.error_target_icon)?.setColorFilter(accent)

        // 错误图标 → 强调色
        activity.findViewById<ImageView>(R.id.error_icon)?.setColorFilter(accent)

        // 操作标题 → 主文字色
        activity.findViewById<TextView>(R.id.error_action_title)?.setTextColor(textPrimary)

        // 分隔线 → 分割线色
        val divider = ThemeColors.divider(activity)
        activity.findViewById<View>(R.id.error_divider)?.setBackgroundColor(divider)

        // 重试按钮 → 主文字色 + 强调色图标
        val btnRetry = activity.findViewById<MaterialButton>(R.id.btn_error_retry)
        btnRetry?.setTextColor(textPrimary)
        btnRetry?.iconTint = ColorStateList.valueOf(accent)

        // 配置按钮 → 主文字色 + 副色描边
        val btnConfig = activity.findViewById<MaterialButton>(R.id.btn_error_config)
        btnConfig?.setTextColor(textPrimary)
        btnConfig?.strokeColor = ColorStateList.valueOf(textSecondary)

        // 底部提示 → 副文字色
        activity.findViewById<TextView>(R.id.error_hint)?.setTextColor(textSecondary)
    }

    /**
     * 对 AppSettingsActivity 布局应用当前主题色（文字 + ToggleGroup + CheckBox）。
     */
    fun applyToAppSettingsActivity(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applyTextColorsToContainer(root, textPrimary, textSecondary, accent, cardBg)

        // ToggleGroup 按钮描边跟随主题强调色
        applyToggleGroupTheme(activity.findViewById(R.id.toggle_app_theme), accent, textPrimary)

        // "应用" 按钮背景
        activity.findViewById<MaterialButton>(R.id.btn_apply_custom_color)
            ?.setBackgroundTintList(ColorStateList.valueOf(accent))
    }

    /**
     * 对 SettingsActivity 布局应用当前主题色。
     */
    fun applyToSettingsActivity(activity: Activity) {
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
    fun applyToSecondaryPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)
    }

    /** 对表单类页面（配置修改、初始化设置）应用主题：文字 + TextInputLayout + 保存按钮 */
    fun applyToFormPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)

        // 保存按钮
        activity.findViewById<MaterialButton>(R.id.btn_save)
            ?.setBackgroundTintList(ColorStateList.valueOf(accent))
        // 确认按钮（SetupActivity）
        activity.findViewById<MaterialButton>(R.id.btn_setup_confirm)
            ?.setBackgroundTintList(ColorStateList.valueOf(accent))
        // 跳过按钮
        activity.findViewById<MaterialButton>(R.id.tv_skip)
            ?.setTextColor(textSecondary)

        // TextInputLayout 描边
        applyTextInputTheme(root, accent, textSecondary)
    }

    /** 对小组件设置页应用主题：文字 + ToggleGroup + CheckBox + TextInputLayout + 保存按钮 */
    fun applyToWidgetSettingsPage(activity: Activity) {
        val ctx = activity
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        applySecondaryTextColors(root, textPrimary, textSecondary, accent, cardBg)

        // 保存按钮
        activity.findViewById<MaterialButton>(R.id.btn_save)
            ?.setBackgroundTintList(ColorStateList.valueOf(accent))

        // ToggleGroup
        applyToggleGroupTheme(activity.findViewById(R.id.toggle_widget_theme), accent, textPrimary)

        // CheckBox
        applyCheckBoxesTheme(root, accent, textPrimary)

        // TextInputLayout
        applyTextInputTheme(root, accent, textSecondary)
    }

    // ==================== 控件级辅助 ====================

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
     * 递归遍历容器，根据 textSize 自动区分标题/正文/注释
     */
    private fun applyTextColorsToContainer(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                // 判断是否为卡片容器（有背景）
                val bg = child.background
                if (bg != null) {
                    try { child.background = makeCardBg(cardBg) } catch (_: Exception) {}
                }
                applyTextColorsToContainer(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView) {
                if (child.textSize > 20f) {
                    child.setTextColor(textPrimary)
                } else if (child.textSize <= 12f) {
                    child.setTextColor(textSecondary)
                }
            }
            // 图标 ImageView 着色
            if (child is ImageView) {
                child.setColorFilter(textSecondary)
            }
        }
    }

    /**
     * 对卡片列表布局（SettingsActivity）应用主题色。
     * 特点：卡片容器有 heading + description + 后箭头图标
     */
    private fun applyCardListTheme(
        root: ViewGroup?,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        cardBg: Int
    ) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                val isCard = child.id != android.R.id.content && child.childCount >= 2
                if (isCard) {
                    // 尝试应用卡片背景
                    try {
                        if (child.background != null) child.background = makeCardBg(cardBg)
                    } catch (_: Exception) {}
                }
                applyCardListTheme(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView) {
                when {
                    child.textSize > 20f -> child.setTextColor(textPrimary)
                    child.textSize >= 15f -> child.setTextColor(textPrimary)
                    child.textSize <= 12f -> child.setTextColor(textSecondary)
                }
            }
            if (child is ImageView) {
                // 卡片左边的功能图标用强调色，右边箭头用次要色
                // 简单判断：有 rotation=180 的是箭头
                if (child.rotation == 180f || child.alpha < 0.5f) {
                    child.setColorFilter(textSecondary)
                } else {
                    child.setColorFilter(accent)
                }
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
        cardBg: Int
    ) {
        if (root == null) return
        for (child in root.children) {
            if (child is ViewGroup) {
                // 子卡片容器应用圆角背景
                try {
                    if (child.background != null && child is ViewGroup) {
                        child.background = makeCardBg(cardBg)
                    }
                } catch (_: Exception) {}
                applySecondaryTextColors(child, textPrimary, textSecondary, accent, cardBg)
            }
            if (child is TextView && child.id != android.R.id.text1) {
                // 跳过系统下拉列表项
                when {
                    child.textSize > 20f -> child.setTextColor(textPrimary)
                    child.textSize <= 13f -> child.setTextColor(textSecondary)
                    else -> child.setTextColor(textPrimary)
                }
            }
            if (child is ImageView) {
                child.setColorFilter(textSecondary)
            }
            // 返回按钮图标着色
            if (child is MaterialButton && child.id == R.id.btn_back) {
                child.iconTint = ColorStateList.valueOf(textPrimary)
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
        for (child in parent.children) {
            if (child is TextView && child.textSize <= 13f) {
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
        for (child in root.children) {
            if (child is ViewGroup) {
                applyTextColors(child, textPrimary, textSecondary)
            }
            if (child is TextView) {
                // 小字 → 辅色，大字 → 主色
                if (child.textSize <= 13f) {
                    child.setTextColor(textSecondary)
                } else {
                    child.setTextColor(textPrimary)
                }
            }
        }
    }

    /** 创建圆角卡片背景 */
    private fun makeCardBg(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 16f
        }
    }
}
