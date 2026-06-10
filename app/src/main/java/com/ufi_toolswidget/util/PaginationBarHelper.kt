package com.ufi_toolswidget.util

import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ufi_toolswidget.R

/**
 * 翻页栏公共组件 — 胶囊形式，固定页面底部，支持首页/上一页/页码/下一页/末页 + 点击跳转。
 *
 * 使用方式：
 * ```kotlin
 * val bar = PaginationBarHelper.create(context) { action -> ... }
 * rootLayout.addView(bar, FrameLayout.LayoutParams(...))
 * PaginationBarHelper.update(bar, currentPage, totalPages)
 * PaginationBarHelper.fadeVisibility(bar, totalPages > 1)
 * ```
 */
object PaginationBarHelper {

    sealed class Action {
        object FIRST : Action()
        object PREV : Action()
        object NEXT : Action()
        object LAST : Action()
        data class Jump(val page: Int) : Action()
    }

    // ── 尺寸常量 ──
    private const val BTN_SIZE_DP      = 28f
    private const val ICON_SIZE_DP     = 16f
    private const val PAGE_TEXT_SP     = 11f
    private const val BAR_HPAD_DP      = 6f       // 缩小水平内边距，首末页按钮更贴近边框
    private const val BAR_VPAD_DP      = 5f
    private const val BTN_MARGIN_DP    = 0.5f
    private const val PAGE_MARGIN_DP   = 6f
    private const val PAGE_HPAD_DP     = 10f
    private const val PAGE_VPAD_DP     = 3f
    private const val BAR_CORNER_DP    = 30f      // 胶囊圆角加强
    private const val PAGE_CORNER_DP   = 7f
    private const val STROKE_WIDTH_DP  = 1.5f

    /**
     * 创建翻页栏（返回 LinearLayout，添加到布局即可）。
     */
    fun create(context: Context, onAction: (Action) -> Unit): LinearLayout {
        val d = context.resources.displayMetrics.density
        val accent = ThemeColors.accent(context)
        val textSec = ThemeColors.textSecondary(context)
        val cardBg = ThemeColors.cardBg(context)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                (BAR_HPAD_DP * d).toInt(), (BAR_VPAD_DP * d).toInt(),
                (BAR_HPAD_DP * d).toInt(), (BAR_VPAD_DP * d).toInt()
            )
            tag = "pagination_bar"

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }

            val strokeColor = if (ThemeColors.isDark(context)) 0x30FFFFFF.toInt() else 0x28000000
            background = GradientDrawable().apply {
                setColor(cardBg)
                setStroke((STROKE_WIDTH_DP * d).toInt(), strokeColor)
                cornerRadius = BAR_CORNER_DP * d
            }
        }

        // 首页 (|<)
        bar.addView(iconBtn(context, R.drawable.ic_chevron_left_pipe, textSec) { onAction(Action.FIRST) }.apply { tag = "btn_first" }, btnLp(d))
        // 上一页 (<)
        bar.addView(iconBtn(context, R.drawable.ic_chevron_left, textSec) { onAction(Action.PREV) }.apply { tag = "btn_prev" }, btnLp(d))

        // 页码信息（可点击跳转）
        val tvPage = TextView(context).apply {
            text = "1 / 1"
            textSize = PAGE_TEXT_SP
            setTextColor(ThemeColors.textPrimary(context))
            gravity = Gravity.CENTER
            tag = "tv_page_info"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (PAGE_MARGIN_DP * d).toInt()
                marginEnd = (PAGE_MARGIN_DP * d).toInt()
            }
            setPadding(
                (PAGE_HPAD_DP * d).toInt(), (PAGE_VPAD_DP * d).toInt(),
                (PAGE_HPAD_DP * d).toInt(), (PAGE_VPAD_DP * d).toInt()
            )
            background = GradientDrawable().apply {
                setColor((accent and 0x00FFFFFF) or 0x15000000)
                cornerRadius = PAGE_CORNER_DP * d
            }
            isClickable = true
            isFocusable = true
        }
        applyPressEffect(tvPage, scale = 0.9f) { bg ->
            bg.setColor((accent and 0x00FFFFFF) or 0x25000000)
        }
        tvPage.setOnClickListener { showJumpDialog(context, onAction) }
        bar.addView(tvPage)

        // 下一页 (>)
        bar.addView(iconBtn(context, R.drawable.ic_chevron_right, textSec) { onAction(Action.NEXT) }.apply { tag = "btn_next" }, btnLp(d))
        // 末页 (>|)
        bar.addView(iconBtn(context, R.drawable.ic_chevron_right_pipe, textSec) { onAction(Action.LAST) }.apply { tag = "btn_last" }, btnLp(d))

        return bar
    }

    /**
     * 更新翻页栏状态（页码 + 按钮可用性 + 动画过渡）。
     */
    fun update(bar: LinearLayout, currentPage: Int, totalPages: Int) {
        val tvPage = bar.findViewWithTag<TextView>("tv_page_info")
        tvPage?.text = "$currentPage / $totalPages"

        val canPrev = currentPage > 1
        val canNext = currentPage < totalPages

        bar.findViewWithTag<View>("btn_first")?.isEnabled = canPrev
        bar.findViewWithTag<View>("btn_prev")?.isEnabled = canPrev
        bar.findViewWithTag<View>("btn_next")?.isEnabled = canNext
        bar.findViewWithTag<View>("btn_last")?.isEnabled = canNext

        // 禁用态动画过渡（平滑 alpha 变化）
        listOf("btn_first", "btn_prev").forEach { t ->
            bar.findViewWithTag<View>(t)?.animate()
                ?.alpha(if (canPrev) 1f else 0.25f)
                ?.setDuration(150)?.start()
        }
        listOf("btn_next", "btn_last").forEach { t ->
            bar.findViewWithTag<View>(t)?.animate()
                ?.alpha(if (canNext) 1f else 0.25f)
                ?.setDuration(150)?.start()
        }
    }

    /**
     * 渐入渐出控制翻页栏可见性。
     */
    fun fadeVisibility(bar: View, visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        if (bar.alpha == targetAlpha && (visible == (bar.visibility == View.VISIBLE))) return

        bar.animate().cancel()

        if (visible && bar.visibility != View.VISIBLE) {
            bar.alpha = 0f
            bar.visibility = View.VISIBLE
        }

        bar.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(if (!visible) object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bar.visibility = View.GONE
                }
            } else null)
            .start()
    }

    /**
     * 页码跳转弹窗。
     */
    private fun showJumpDialog(context: Context, onAction: (Action) -> Unit) {
        val d = context.resources.displayMetrics.density
        val cardBg = ThemeColors.cardBg(context)
        val textPrimary = ThemeColors.textPrimary(context)

        CommonDialogHelper.showCommonDialog(
            context = context,
            title = "跳转到页码",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(TextView(context).apply {
                    text = "请输入目标页码"
                    textSize = 13f
                    setTextColor(ThemeColors.textSecondary(context))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * d).toInt() }
                })
                val et = EditText(context).apply {
                    hint = "页码"
                    textSize = 16f
                    setTextColor(textPrimary)
                    setHintTextColor(ThemeColors.textSecondary(context))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.CENTER
                    setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = 10 * d
                        setStroke((1 * d).toInt(), (ThemeColors.textSecondary(context) and 0x00FFFFFF) or 0x40000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()
                    )
                    tag = "jump_input"
                }
                content.addView(et)
            },
            primaryBtnText = "跳转",
            onPrimaryClick = { dialog ->
                val et = dialog.findViewById<EditText>(R.id.common_dialog_content)
                    ?.findViewWithTag<EditText>("jump_input")
                val input = et?.text?.toString()?.toIntOrNull()
                if (input != null && input > 0) {
                    onAction(Action.Jump(input))
                }
                dialog.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ── 内部工具 ──

    /**
     * 创建带按压动画反馈的图标按钮（ImageView + 缩放 + 背景色变化）。
     */
    private fun iconBtn(context: Context, iconRes: Int, color: Int, onClick: () -> Unit): ImageView {
        val d = context.resources.displayMetrics.density
        val size = (BTN_SIZE_DP * d).toInt()
        val iconSize = (ICON_SIZE_DP * d).toInt()

        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color)
            // 图标居中
            setPadding(
                (size - iconSize) / 2, (size - iconSize) / 2,
                (size - iconSize) / 2, (size - iconSize) / 2
            )
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
        }

        val pressColor = (color and 0x00FFFFFF) or 0x18000000

        iv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(50).start()
                    (v.background as? GradientDrawable)?.setColor(pressColor)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    (v.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
                    if (event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height) {
                        v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    (v.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
                }
            }
            true
        }
        return iv
    }

    /**
     * 为 View 添加通用按压效果（缩放 + 自定义背景回调）。
     */
    private fun applyPressEffect(view: View, scale: Float = 0.88f, onDown: ((GradientDrawable) -> Unit)? = null) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scale).scaleY(scale).setDuration(50).start()
                    (v.background as? GradientDrawable)?.let { onDown?.invoke(it) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
    }

    private fun btnLp(d: Float) = LinearLayout.LayoutParams(
        (BTN_SIZE_DP * d).toInt(), (BTN_SIZE_DP * d).toInt()
    ).apply {
        marginStart = (BTN_MARGIN_DP * d).toInt()
        marginEnd = (BTN_MARGIN_DP * d).toInt()
    }
}
