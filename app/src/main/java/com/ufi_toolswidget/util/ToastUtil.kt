package com.ufi_toolswidget.util

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ufi_toolswidget.R

/**
 * 统一 Toast 工具类 — 水滴下落动画效果。
 *
 * 从屏幕中上方自然滴落，带有弹性弹跳和涟漪脉冲动画，
 * 全程使用硬件加速 + translationY 避免布局抖动，
 * 自动跟随当前主题配色（通过 ThemeColors 取色）。
 *
 * 支持三种样式：
 * - [ToastStyle.INFO]：普通提示，灰色图标
 * - [ToastStyle.SUCCESS]：成功提示，✓ 图标
 * - [ToastStyle.WARNING]：警告提示，红色图标 + 淡红底色
 *
 * 用法示例：
 * ```kotlin
 * // 加载中（需手动关闭或由 showDropToast 自动替换）
 * ToastUtil.showLoadingToast(activity, "正在检查更新...")
 *
 * // 成功提示
 * ToastUtil.showDropToast(activity, ToastStyle.SUCCESS, "已是最新版本")
 *
 * // 警告提示
 * ToastUtil.showDropToast(activity, ToastStyle.WARNING, "网络连接失败")
 *
 * // 确认弹窗（需用户点击确认关闭）
 * ToastUtil.showConfirmDialog(context, "流量提醒", "本月流量已用完")
 * ```
 */
/** Toast 样式 */
enum class ToastStyle(val iconRes: Int) {
    INFO(R.drawable.ic_info),
    SUCCESS(R.drawable.ic_check),
    WARNING(android.R.drawable.ic_dialog_alert)
}

/**
 * 统一 Toast 工具类 — 水滴下落动画效果。
 */
object ToastUtil {

    /** 当前显示的装饰视图 Toast（用于自动移除） */
    private var activeToast: View? = null
    /** 待执行的自动移除任务 */
    private var pendingRemoveRunnable: Runnable? = null
    /** 加载中 Toast 的视图（仍使用 decorView 方式） */
    private var activeLoadingView: View? = null

    // ── 警告色（与 CommonDialogHelper 保持一致）──
    private const val WARN_COLOR_LIGHT = 0xFFE53935.toInt()
    private const val WARN_COLOR_DARK = 0xFFEF5350.toInt()

    // ── 常量 ──
    private const val TOP_MARGIN_DP = 48
    private const val MAX_WIDTH_RATIO = 0.82f
    private const val DROP_DURATION = 600L       // 下落时长(ms)，稍长更流畅
    private const val EXIT_DURATION = 250L       // 退出时长(ms)
    private const val RIPPLE_PUSH_DP = 5f        // 涟漪下落幅度(dp)
    private const val RIPPLE_REBOUND_DP = 6f     // 涟漪回弹幅度(dp)

    // ══════════════════════════════════════════════════════════════
    // 1. 加载中 Toast（持久显示，需手动关闭或被 showDropToast 替换）
    // ══════════════════════════════════════════════════════════════

    /**
     * 显示加载中 Toast。
     *
     * 不会自动消失，直到调用 [dismissActiveToast] 或下一个 [showDropToast]。
     *
     * @param activity 当前 Activity
     * @param message  加载提示文字
     */
    @JvmStatic
    fun showLoadingToast(activity: Activity, message: String = "正在检查更新...") {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        dismissActiveToast()

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // 构建视图（无图标，纯文字居中）
        val toast = createLoadingView(activity, message, dp)
        val maxWidth = (activity.resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).toInt()

        // 先测量确定实际宽度
        toast.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val toastWidth = minOf(toast.measuredWidth, maxWidth)
        val toastHeight = toast.measuredHeight

        // 用实测宽度添加到 DecorView，确保不超出屏幕
        decorView.addView(toast, android.widget.FrameLayout.LayoutParams(
            toastWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        })
        activeLoadingView = toast

        // 启用硬件加速图层
        toast.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 初始状态：隐藏在屏幕上方
        val topTarget = dp(TOP_MARGIN_DP).toFloat()
        toast.translationY = -toastHeight - topTarget - dp(40).toFloat()
        toast.alpha = 0f

        // 下落入场（带弹性弹跳）
        toast.animate()
            .translationY(topTarget)
            .alpha(1f)
            .setDuration(DROP_DURATION)
            .setInterpolator(OvershootInterpolator(1.25f))
            .start()
    }

    /** 构建加载中视图（纯文字 + 居中） */
    private fun createLoadingView(
        activity: Activity,
        message: String,
        dp: (Int) -> Int
    ): LinearLayout {
        val cardBg = ThemeColors.cardBg(activity)
        val textPrimary = ThemeColors.textPrimary(activity)
        val isDark = ThemeColors.isDark(activity)
        val density = activity.resources.displayMetrics.density

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = 14f * density
                val borderColor = if (isDark) 0x50FFFFFF.toInt() else 0x28000000
                setStroke(dp(1), borderColor)
            }
            elevation = 16f

            addView(TextView(activity).apply {
                text = message
                textSize = 14f
                setTextColor(textPrimary)
            })
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 2. 标准 Toast（带图标 + 水滴动画 + 自动消失）
    // ══════════════════════════════════════════════════════════════

    /**
     * 显示水滴下落 Toast（Activity 版本）。
     *
     * 将 Toast 添加到 DecorView 并执行下落动画，
     * 在 [duration] 毫秒后自动消失。
     *
     * @param activity 当前 Activity
     * @param style    [ToastStyle] 样式（INFO / SUCCESS / WARNING）
     * @param title    标题（必填）
     * @param message  消息正文（可选）
     * @param duration 停留时长（ms），默认 2200
     */
    @JvmStatic
    fun showDropToast(
        activity: Activity,
        style: ToastStyle = ToastStyle.INFO,
        title: String,
        message: String = "",
        duration: Long = 2200L
    ) {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        dismissActiveToast()

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * MAX_WIDTH_RATIO).toInt()
        val isDark = ThemeColors.isDark(activity)
        val warnColor = if (isDark) WARN_COLOR_DARK else WARN_COLOR_LIGHT
        val isWarning = style == ToastStyle.WARNING

        // 构建视图
        val toastView = createToastView(activity, style.iconRes, title, message, dp, isWarning, warnColor)

        // 测量并限定宽度
        toastView.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val toastWidth = minOf(toastView.measuredWidth, maxWidth)
        val toastHeight = toastView.measuredHeight

        // 添加到 DecorView
        decorView.addView(toastView, android.widget.FrameLayout.LayoutParams(
            toastWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        })
        activeToast = toastView

        // 硬件加速
        toastView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 初始状态：隐藏在屏幕上方
        val topTarget = dp(TOP_MARGIN_DP).toFloat()
        toastView.translationY = -toastHeight - topTarget - dp(40).toFloat()
        toastView.alpha = 0f

        // 下落入场（带弹性弹跳）
        toastView.animate()
            .translationY(topTarget)
            .alpha(1f)
            .setDuration(DROP_DURATION)
            .setInterpolator(OvershootInterpolator(1.25f))
            .withEndAction {
                // 落地后涟漪脉冲
                startRipplePulse(toastView)
            }
            .start()

        // 自动消失
        pendingRemoveRunnable = Runnable {
            exitAndRemove(toastView, decorView)
            if (activeToast == toastView) activeToast = null
        }
        toastView.postDelayed(pendingRemoveRunnable, duration)
    }

    /**
     * 显示标准 Toast（Context 版本）。
     *
     * 尝试将 [context] 转为 Activity，成功则使用装饰视图动画 Toast；
     * 非 Activity 上下文（如后台 Worker）不会显示 Toast，
     * 后台通知应使用系统通知栏。
     */
    @JvmStatic
    fun showDropToast(
        context: Context,
        style: ToastStyle = ToastStyle.INFO,
        title: String,
        message: String = "",
        duration: Long = 2200L
    ) {
        val activity = context as? Activity
        if (activity != null) {
            showDropToast(activity, style, title, message, duration)
        }
        // 非 Activity 上下文：静默忽略（后台 Worker 使用系统通知栏）
    }

    /** 构建带图标的 Toast 视图 */
    private fun createToastView(
        activity: Activity,
        iconRes: Int,
        title: String,
        message: String,
        dp: (Int) -> Int,
        isWarning: Boolean,
        warnColor: Int
    ): LinearLayout {
        val cardBg = ThemeColors.cardBg(activity)
        val textPrimary = ThemeColors.textPrimary(activity)
        val iconTint = ThemeColors.iconTint(activity)
        val isDark = ThemeColors.isDark(activity)
        val density = activity.resources.displayMetrics.density

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))

            // ── Card 背景 ──
            val bgColor = if (isWarning) {
                // 警告：在 cardBg 上叠加 8%~12% 红色
                val warnAlpha = if (isDark) 0x1F else 0x14
                blendColorOn(cardBg, warnColor, warnAlpha)
            } else {
                cardBg
            }
            val borderColor = if (isWarning) {
                (warnColor and 0x00FFFFFF) or 0x50000000.toInt()
            } else if (isDark) {
                0x50FFFFFF.toInt()
            } else {
                0x28000000
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                cornerRadius = 14f * density
                setStroke(dp(1), borderColor)
            }
            elevation = 16f

            // ── 图标 ──
            addView(ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(iconRes)
                setColorFilter(if (isWarning) warnColor else iconTint)
            })

            // ── 文字区域 ──
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }

                addView(TextView(activity).apply {
                    text = title
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(textPrimary)
                })

                if (message.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = message
                        textSize = 13f
                        setTextColor(textPrimary)
                        alpha = 0.55f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                    })
                }
            })
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 3. 确认弹窗（需用户点击确认关闭）
    // ══════════════════════════════════════════════════════════════

    /**
     * 显示确认通知弹窗。
     *
     * 适用于需要用户确认的重要通知（如流量限制提醒），
     * 不会自动关闭，需用户点击确认按钮。
     * 内部委托给 [CommonDialogHelper.showCommonDialog] 确保样式统一。
     *
     * @param context     上下文
     * @param title       标题
     * @param message     消息正文
     * @param iconRes     图标资源，默认 ic_info
     * @param confirmText 确认按钮文字，默认"我知道了"
     * @param onConfirm   确认回调
     */
    @JvmStatic
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        iconRes: Int = R.drawable.ic_info,
        confirmText: String = "我知道了",
        onConfirm: () -> Unit = {}
    ) {
        CommonDialogHelper.showCommonDialog(
            context,
            title = title,
            iconRes = iconRes,
            onFill = { content ->
                content.addView(TextView(context).apply {
                    text = message
                    textSize = 15f
                    setTextColor(ThemeColors.textPrimary(context))
                    setLineSpacing(0f, 1.3f)
                })
            },
            primaryBtnText = confirmText,
            onPrimaryClick = { dialog ->
                dialog.dismiss()
                onConfirm()
            }
        )
    }

    /**
     * 显示警告确认弹窗（醒目红色样式）。
     * 适用于需要用户特别注意的确认场景。
     * 委托给 [CommonDialogHelper.showWarningConfirmDialog]。
     */
    @JvmStatic
    fun showWarningConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        onConfirm: () -> Unit
    ) {
        CommonDialogHelper.showWarningConfirmDialog(
            context,
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = onConfirm
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 4. 内部工具方法
    // ══════════════════════════════════════════════════════════════

    /** 涟漪脉冲动画：水滴落下后单次水波扩散，弹动次数减半，仅保留主波 */
    private fun startRipplePulse(toast: View) {
        if (toast.parent == null) return
        val density = toast.resources.displayMetrics.density
        val pushPx = RIPPLE_PUSH_DP * density
        val reboundPx = RIPPLE_REBOUND_DP * density

        // 单波涟漪：下落冲击 → 回弹 → 稳定
        toast.animate()
            .translationYBy(pushPx)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                if (toast.parent == null) return@withEndAction
                toast.animate()
                    .translationYBy(-(pushPx + reboundPx))
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                    .withEndAction {
                        if (toast.parent == null) return@withEndAction
                        toast.animate()
                            .translationYBy(reboundPx * 0.4f)
                            .setDuration(180)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun exitAndRemove(toast: View, parent: ViewGroup) {
        if (toast.parent == null) return
        toast.animate().cancel()
        toast.animate()
            .alpha(0f)
            .translationYBy(-16f)
            .setDuration(EXIT_DURATION)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                if (toast.parent != null) parent.removeView(toast)
                if (activeLoadingView == toast) activeLoadingView = null
                if (activeToast == toast) activeToast = null
            }
            .start()
    }

    /**
     * 立即移除当前活跃 Toast。
     * 在 [showDropToast] 和 [showLoadingToast] 中会自动调用。
     */
    @JvmStatic
    fun dismissActiveToast() {
        // 取消待执行的自动移除任务
        pendingRemoveRunnable?.let { runnable ->
            activeToast?.removeCallbacks(runnable)
        }
        pendingRemoveRunnable = null

        // 移除装饰视图 Toast
        activeToast?.let { toast ->
            toast.animate().cancel()
            (toast.parent as? ViewGroup)?.removeView(toast)
            activeToast = null
        }

        // 清除 Activity 内嵌的加载中 Toast
        activeLoadingView?.let { toast ->
            toast.animate().cancel()
            (toast.parent as? ViewGroup)?.removeView(toast)
            activeLoadingView = null
        }
    }

    /** 在 [base] 上叠加 [overlay] 颜色（指定 alpha 不透明度 0x00~0xFF） */
    private fun blendColorOn(base: Int, overlay: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val invA = 255 - a
        val r = (((base shr 16) and 0xFF) * invA + ((overlay shr 16) and 0xFF) * a) / 255
        val g = (((base shr 8) and 0xFF) * invA + ((overlay shr 8) and 0xFF) * a) / 255
        val b = ((base and 0xFF) * invA + (overlay and 0xFF) * a) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
