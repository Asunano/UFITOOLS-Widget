package com.ufi_toolswidget.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R

/**
 * 公共设置项组件库
 *
 * 提供与 [layout_common_switch_item]、[layout_common_setting_item] 样式一致的
 * View 工厂方法，避免在各 Activity 中手写重复的 findViewById + 样式代码。
 */
object CommonSettingsItemHelper {

    // ── 尺寸常量（与 XML 布局保持一致）──
    private const val SWITCH_TRACK_W = 42   // dp
    private const val SWITCH_TRACK_H = 24   // dp
    private const val SWITCH_THUMB_W = 18   // dp
    private const val SWITCH_THUMB_H = 18   // dp
    private const val SWITCH_THUMB_MARGIN_START = 3 // dp

    // ═══════════════════════════════════════════
    // 1. 开关项配置（inflate 的 layout_common_switch_item）
    // ═══════════════════════════════════════════

    /**
     * 配置已 inflate 的 [R.layout.layout_common_switch_item]（或 include 该布局的根 View）。
     *
     * 自动设置图标、标题、副标题（可选），并绑定自定义 FrameLayout 开关。
     *
     * 用法示例：
     * ```kotlin
     * CommonSettingsItemHelper.setupSwitchItem(
     *     findViewById(R.id.item_widget_clip_to_outline),
     *     iconRes = R.drawable.ic_rounded_corners,
     *     label = "兼容性小组件圆角",
     *     subtitle = "如果桌面小组件没有圆角效果，可开启此项强制圆角",
     *     initialChecked = SPUtil.getWidgetClipToOutline(this),
     *     onToggle = { checked -> SPUtil.setWidgetClipToOutline(this, checked) }
     * )
     * ```
     */
    fun setupSwitchItem(
        itemView: View,
        iconRes: Int,
        label: String,
        subtitle: String? = null,
        initialChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        itemView.findViewById<ImageView>(R.id.common_item_icon)
            ?.setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.common_switch_label)?.text = label
        itemView.findViewById<TextView>(R.id.common_switch_subtitle)?.apply {
            if (subtitle != null) {
                text = subtitle
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        ThemeUtil.setupSwitch(itemView, initialChecked, onToggle)
    }

    // ═══════════════════════════════════════════
    // 2. 代码构建开关行（弹窗/动态布局用）
    // ═══════════════════════════════════════════

    /**
     * 纯代码构建一个开关行，样式与 [R.layout.layout_common_switch_item]
     * 的开关区域一致（自定义 FrameLayout 滑块开关 + 标题/副标题）。
     *
     * 适用于弹窗 content 区等无法 `<include>` XML 的场景。
     *
     * @param context 上下文
     * @param label   开关标题（对应 common_switch_label）
     * @param subtitle 副标题，传 null 则不显示
     * @return 已绑定开关逻辑的根 View，可直接 addView 到容器中
     */
    fun createSwitchRow(
        context: Context,
        label: String,
        subtitle: String? = null,
        initialChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // ── 根容器 ──
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(4), 0, dp(4))
        }

        // ── 左侧文字区域 ──
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        // 标题（取色使用动态 ThemeColors，确保跟随主题切换）
        val tvLabel = TextView(context).apply {
            id = R.id.common_switch_label
            setTextAppearance(R.style.AppText_Title)
            setTextColor(ThemeColors.textPrimary(context))
            text = label
        }
        textCol.addView(tvLabel)

        // 副标题（可选，取色使用动态 ThemeColors）
        val tvSubtitle = TextView(context).apply {
            id = R.id.common_switch_subtitle
            setTextAppearance(R.style.AppText_Subtitle)
            setTextColor(ThemeColors.textSecondary(context))
            if (subtitle != null) {
                text = subtitle
            } else {
                visibility = View.GONE
            }
        }
        textCol.addView(tvSubtitle)
        root.addView(textCol)

        // ── 右侧自定义开关 ──
        val track = FrameLayout(context).apply {
            id = R.id.common_switch_track
            layoutParams = LinearLayout.LayoutParams(dp(SWITCH_TRACK_W), dp(SWITCH_TRACK_H))
            // 初始 off 背景（ThemeUtil.setupSwitch 会在初始化时根据主题替换）
            setBackgroundResource(R.drawable.bg_common_switch_track_off)
            isClickable = true
            isFocusable = true
        }

        val thumb = View(context).apply {
            id = R.id.common_switch_thumb
            val lp = FrameLayout.LayoutParams(dp(SWITCH_THUMB_W), dp(SWITCH_THUMB_H))
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            lp.marginStart = dp(SWITCH_THUMB_MARGIN_START)
            layoutParams = lp
            setBackgroundResource(R.drawable.bg_widget_mask)
            elevation = 1f
        }
        track.addView(thumb)
        root.addView(track)

        // ── 委托 ThemeUtil.setupSwitch 绑定动画 + 交互逻辑 ──
        ThemeUtil.setupSwitch(root, initialChecked, onToggle)

        return root
    }

    // ═══════════════════════════════════════════
    // 3. 分隔线
    // ═══════════════════════════════════════════

    /**
     * 创建一条主题色分隔线（1dp 高，宽度填满父容器）。
     */
    fun createDivider(context: Context): View {
        return View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(ThemeColors.divider(context))
        }
    }

    // ═══════════════════════════════════════════
    // 4. 设置项配置（inflate 的 layout_common_setting_item）
    // ═══════════════════════════════════════════

    /**
     * 配置已 inflate 的 [R.layout.layout_common_setting_item]（或 include 该布局的根 View）。
     *
     * 自动设置图标、标题、副标题（可选），并绑定点击事件。
     *
     * 用法示例：
     * ```kotlin
     * CommonSettingsItemHelper.setupSettingItem(
     *     findViewById(R.id.item_basic_config),
     *     iconRes = R.drawable.ic_router,
     *     title = "基础连接",
     *     showSubtitle = false,
     *     onClick = ::showBasicConfigDialog
     * )
     * ```
     */
    fun setupSettingItem(
        itemView: View,
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        showSubtitle: Boolean = true,
        onClick: () -> Unit
    ) {
        itemView.findViewById<ImageView>(R.id.common_item_icon)
            ?.setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.common_item_title)?.text = title
        itemView.findViewById<TextView>(R.id.common_item_subtitle)?.apply {
            if (showSubtitle && subtitle != null) {
                text = subtitle
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        itemView.setOnClickListener { onClick() }
    }

    // ═══════════════════════════════════════════
    // 5. 主题 EditText（弹窗内表单输入）
    // ═══════════════════════════════════════════

    /**
     * 创建带主题样式的单行 [EditText]（卡片背景 + 圆角 + 描边）。
     *
     * 适用于弹窗 content 区的多字段表单。
     *
     * @param context   上下文
     * @param hint      占位提示
     * @param text      当前值，默认空字符串
     * @param inputType 输入类型，默认 [InputType.TYPE_CLASS_TEXT]
     */
    fun createThemedEditText(
        context: Context,
        hint: String,
        text: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val cardBg = ThemeColors.cardBg(context)
        val textPrimary = ThemeColors.textPrimary(context)
        val textSecondary = ThemeColors.textSecondary(context)
        return EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setHint(hint)
            setText(text)
            this.inputType = inputType
            maxLines = 1
            textSize = 14f
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = 10f * density
                setStroke(1, if (ThemeColors.isDark(context)) 0x30FFFFFF.toInt() else 0x20000000)
            }
        }
    }

    // ═══════════════════════════════════════════
    // 6. EditText 转下拉选择器
    // ═══════════════════════════════════════════

    /**
     * 将 [EditText] 改造为"点击弹出下拉菜单"的选择器（禁止手动输入）。
     *
     * 下拉菜单样式由 [PopupViewUtil.showDropDownMenu] 提供。
     *
     * @param editText     目标 EditText
     * @param options      展示选项数组（如 "auto (自动探测)"）
     * @param values       选中后写入 EditText 的实际值数组（如 "auto"）
     * @param currentValue 当前值，用于定位默认选中项
     */
    fun setupDropdownOnEditText(
        editText: EditText,
        options: Array<String>,
        values: Array<String>,
        currentValue: String
    ) {
        editText.apply {
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setOnClickListener {
                val currentIdx = values.indexOf(currentValue.lowercase()).coerceAtLeast(0)
                PopupViewUtil.showDropDownMenu(
                    it,
                    options = options,
                    currentIndex = currentIdx,
                    onSelect = { which -> setText(values[which]) }
                )
            }
        }
    }

    // ═══════════════════════════════════════════
    // 7. "恢复默认"按钮（弹窗底部）
    // ═══════════════════════════════════════════

    /**
     * 创建"恢复默认" [MaterialButton]（Outlined 样式），用于弹窗内一键重置表单字段。
     *
     * @param context            上下文
     * @param onRestoreDefaults  点击回调
     */
    fun createRestoreDefaultsButton(
        context: Context,
        onRestoreDefaults: () -> Unit
    ): MaterialButton {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val secondaryColor = ThemeColors.textSecondary(context)
        val textPrimary = ThemeColors.textPrimary(context)

        return MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(10)
            }
            text = "恢复默认"
            textSize = 14f
            insetTop = 0
            insetBottom = 0
            setTextColor(textPrimary)
            strokeColor = ColorStateList.valueOf(secondaryColor)
            strokeWidth = dp(1)
            @Suppress("DEPRECATION")
            setCornerRadius(dp(12))
            setOnClickListener { onRestoreDefaults() }
        }
    }
}
