package com.ufi_toolswidget.util

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.ToastUtil

/**
 * 统一弹窗工具类 — 从 MainActivity 抽离。
 *
 * 提供：
 * - 弹窗创建（透明背景、模糊、动画）
 * - 弹窗窗口设置（背景、模糊、高度适配）
 * - 弹窗主题着色（根布局 + 递归视图树）
 * - 通用弹窗组装与显示
 */
object CommonDialogHelper {

    private const val TAG = "CommonDialogHelper"

    // ── 弹窗创建 ──

    /**
     * 创建带自动退场动画的 Dialog（全局统一）。
     *
     * 重写 [Dialog.dismiss]：
     * - API 31+：先清理模糊标志 → 执行 [AnimationUtil.applyDialogBlurOut] 渐退动画 → 回调 [onDismissed] → super.dismiss()
     * - API <31：清理模糊标志 → 直接 super.dismiss() → 回调 [onDismissed]
     *
     * 调用方无需手动管理动画状态或调用 [AnimationUtil.applyDialogBlurOut]。
     *
     * @param context 上下文
     * @param onDismissed 弹窗完全关闭后的回调（可选），用于重置调用方的引用/状态
     */
    fun createAnimatedDialog(context: Context, onDismissed: () -> Unit = {}): Dialog {
        val dialog = object : Dialog(context, R.style.Theme_UFITOOLSWidget_Transparent) {
            @Volatile private var isAnimatingOut = false

            private fun realDismiss() {
                isAnimatingOut = false
                super.dismiss()
                onDismissed()
            }

            override fun dismiss() {
                if (isAnimatingOut) return  // 防止重入
                val win = window
                if (win == null) {
                    realDismiss()
                    return
                }
                isAnimatingOut = true
                // 全版本统一走 applyDialogBlurOut 退场动画：
                // - API 31+：缩放淡出 + 模糊/遮罩同步消退
                // - API <31：缩放淡出（内部 setWindowAnimations(0) 覆盖 XML 动画）
                AnimationUtil.applyDialogBlurOut(this) { realDismiss() }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    /**
     * 一次性完成弹窗窗口的标准化设置：
     * 透明背景、低遮罩、缩放动画、模糊背景（API 31+）、高度适配。
     */
    fun setupDialogWindow(context: Context, dialog: Dialog, widthRatio: Float = 0.88f) {
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.08f)
            setWindowAnimations(R.style.DialogAnimationTheme)
        }
        applyDialogBlur(context, dialog)
        PopupViewUtil.autoAdjustDialogHeight(context, dialog, widthRatio)
    }

    // ── 主题着色 ──

    /**
     * 为弹窗根布局应用主题背景 + 描边，并递归着色全部子视图。
     */
    fun applyThemeToDialogRoot(context: Context, dialog: Dialog) {
        val root = dialog.findViewById<ViewGroup>(android.R.id.content)
            ?.let { if (it.childCount > 0) it.getChildAt(0) as? ViewGroup else it } ?: return
        val cardBg = ThemeColors.cardBg(context)
        val textPrimary = ThemeColors.textPrimary(context)

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = dp2px(context, 16).toFloat()
            val borderColor = if (ThemeColors.isDark(context))
                (ThemeColors.textSecondary(context) and 0x00FFFFFF) or 0x60000000 else 0x35000000
            setStroke(dp2px(context, 2), borderColor)
        }
        root.elevation = 24f

        // 标题 + 图标着色
        dialog.findViewById<TextView>(R.id.common_dialog_title)?.setTextColor(textPrimary)
        dialog.findViewById<ImageView>(R.id.common_dialog_icon)?.setColorFilter(ThemeColors.iconTint(context))

        // 递归着色视图树
        applyThemeToViewTree(root, context)
    }

    /**
     * 递归为弹窗视图树着色：MaterialButton、Button、TextView、ImageView。
     * 跳过 common_dialog_content 动态容器（由调用方自行填充）。
     */
    fun applyThemeToViewTree(view: View?, context: Context) {
        if (view == null) return
        val textPrimary = ThemeColors.textPrimary(context)
        val textSecondary = ThemeColors.textSecondary(context)
        val iconTint = ThemeColors.iconTint(context)
        val btnBg = ThemeColors.btnBg(context)

        if (view is ViewGroup && view.id != R.id.common_dialog_content) {
            for (i in 0 until view.childCount) {
                applyThemeToViewTree(view.getChildAt(i), context)
            }
        }
        when (view) {
            is MaterialButton -> {
                if ((view.strokeWidth ?: 0) > 0) {
                    // 描边按钮（次要操作）
                    view.setTextColor(textPrimary)
                    view.strokeColor = ColorStateList.valueOf(textSecondary)
                    view.iconTint = ColorStateList.valueOf(iconTint)
                } else {
                    // 实色按钮（主要操作）
                    view.backgroundTintList = ColorStateList.valueOf(btnBg)
                    view.setTextColor(0xFFFFFFFF.toInt())
                    view.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }
                view.textSize = 14f
                view.insetTop = 0
                view.insetBottom = 0
            }
            is Button -> {
                view.backgroundTintList = ColorStateList.valueOf(btnBg)
                view.setTextColor(0xFFFFFFFF.toInt())
            }
            is TextView -> {
                if (view.id == R.id.common_dialog_btn_primary) return
                // switch label / 标题类文字统一取主色（粗体由 XML style 或代码设置）
                if (view.id == R.id.common_switch_label || view.id == R.id.common_item_title) {
                    view.setTextColor(textPrimary)
                    return
                }
                if (view.textSize <= 13f) view.setTextColor(textSecondary)
                else view.setTextColor(textPrimary)
            }
            is ImageView -> {
                view.setColorFilter(iconTint)
            }
        }
    }

    // ── 背景模糊 ──

    /**
     * 应用背景模糊：API 31+ 原生模糊，API 26-30 bitmap 缩放模糊。
     */
    fun applyDialogBlur(context: Context, dialog: Dialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurIn(dialog)
        } else {
            applyLegacyBlur(context, dialog)
        }
    }

    /** API 26-30：截屏 + 多级缩放模拟毛玻璃效果（异步版本，避免主线程阻塞） */
    private fun applyLegacyBlur(context: Context, dialog: Dialog) {
        try {
            val decorView = dialog.window?.decorView?.rootView ?: return
            val vw = decorView.width
            val vh = decorView.height
            if (vw <= 0 || vh <= 0) return

            // 步骤 1-2（必须主线程）：截取 decorView 当前画面
            val capture = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(capture))

            val smallW = (vw * 0.06f).toInt().coerceAtLeast(4)
            val smallH = (vh * 0.06f).toInt().coerceAtLeast(4)
            val windowRef = java.lang.ref.WeakReference(dialog.window)
            val res = context.resources

            // 步骤 3-5（CPU 密集）：缩放模糊在后台线程完成，结果回主线程设置
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val small = Bitmap.createScaledBitmap(capture, smallW, smallH, true)
                    capture.recycle()
                    val blurred = Bitmap.createScaledBitmap(small, vw, vh, true)
                    small.recycle()
                    withContext(Dispatchers.Main) {
                        windowRef.get()?.setBackgroundDrawable(BitmapDrawable(res, blurred))
                    }
                } catch (e: Exception) {
                    capture.recycle()
                    Log.w(TAG, "Legacy blur async failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy blur failed: ${e.message}")
            DebugLogger.w(TAG, "Legacy blur failed: ${e.message}")
        }
    }

    // ── 通用弹窗组装 ──

    /**
     * 配置并显示通用弹窗（使用已创建的 Dialog）。
     * 适合调用方自行管理 Dialog 生命周期（防抖/复用）的场景。
     */
    fun configureAndShow(
        context: Context,
        dialog: Dialog,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout) -> Unit,
        primaryBtnText: String = "关闭",
        onPrimaryClick: ((Dialog) -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: ((Dialog) -> Unit)? = null,
        widthRatio: Float = 0.88f
    ) {
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.removeAllViews()
        onFill(content)

        val btnPrimary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = primaryBtnText
        AnimationUtil.applyScaleClickAnimation(btnPrimary) {
            if (onPrimaryClick != null) {
                onPrimaryClick(dialog)
            } else {
                dialog.dismiss()
            }
        }

        val btnSecondary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary)
        if (secondaryBtnText != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryBtnText
            AnimationUtil.applyScaleClickAnimation(btnSecondary) {
                onSecondaryClick?.invoke(dialog) ?: dialog.dismiss()
            }
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart =
                dp2px(context, 12)
        } else {
            btnSecondary.visibility = View.GONE
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
        }

        setupDialogWindow(context, dialog, widthRatio)
        dialog.show()
    }

    // ── 通用弹窗一步到位 ──

    /**
     * 一步到位：创建弹窗（带动画退场）→ 主题着色 → 填充内容 → 配置按钮 → 显示。
     *
     * 适用于不需要持有 Dialog 引用的场景。如需自己管理生命周期，用 createDialog + configureAndShow。
     *
     * @return 已显示的 [Dialog] 实例
     */
    fun showCommonDialog(
        context: Context,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout) -> Unit,
        primaryBtnText: String = "关闭",
        onPrimaryClick: ((Dialog) -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: ((Dialog) -> Unit)? = null,
        widthRatio: Float = 0.88f
    ): Dialog {
        // 防御性检查：Activity 已销毁或正在销毁时不显示弹窗，避免 BadTokenException
        val activity = context as? android.app.Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            return createAnimatedDialog(context) // 返回一个不会 show 的空 Dialog
        }
        val dialog = createAnimatedDialog(context)
        dialog.setContentView(R.layout.layout_common_dialog)
        applyThemeToDialogRoot(context, dialog)
        configureAndShow(context, dialog, title, iconRes, onFill,
            primaryBtnText, onPrimaryClick, secondaryBtnText, onSecondaryClick, widthRatio)
        return dialog
    }

    // ── 选择列表弹窗（无按钮，点击选项即触发） ──

    /**
     * 显示选择列表弹窗（无按钮，点击选项即触发回调并关闭）。
     *
     * 适用于"从列表中选择一项"的场景，如对比度选择、色源选择。
     * 自动创建 AnimatedDialog、设置通用布局、应用主题、配置窗口并显示。
     *
     * @param context  上下文
     * @param title    弹窗标题
     * @param iconRes  图标资源
     * @param onFill   填充内容到 content LinearLayout，接收 (content, dialog) 便于添加点击关闭事件
     * @param widthRatio 弹窗宽度比，默认 0.88
     * @return 已显示的 [Dialog] 实例
     */
    fun showSelectionDialog(
        context: Context,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout, Dialog) -> Unit,
        widthRatio: Float = 0.88f
    ): Dialog {
        val dialog = createAnimatedDialog(context)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title)?.text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon)?.setImageResource(iconRes)
        dialog.findViewById<View>(R.id.common_dialog_button_container)?.visibility = View.GONE

        applyThemeToDialogRoot(context, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        onFill(content, dialog)

        setupDialogWindow(context, dialog, widthRatio)
        dialog.show()
        return dialog
    }

    // ── 背景 Drawable 工厂（选项列表项用） ──

    /**
     * 创建选中态选项背景（实色填充 + 圆角）。
     * @param accentColor 强调色（选中态背景色）
     * @param cornerRadius 圆角半径（px）
     */
    fun createSelectedBg(accentColor: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accentColor)
            this.cornerRadius = cornerRadius
        }
    }

    /**
     * 创建未选中态选项背景（cardBg 底色 + 描边 + 圆角）。
     * @param context 上下文
     * @param cornerRadius 圆角半径（px）
     */
    fun createUnselectedBg(context: Context, cornerRadius: Float): GradientDrawable {
        val cardBg = ThemeColors.cardBg(context)
        val isDark = ThemeColors.isDark(context)
        val borderColor = if (isDark) 0x30FFFFFF.toInt() else 0x20000000
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            this.cornerRadius = cornerRadius
            setStroke(dp2px(context, 1), borderColor)
        }
    }

    // ── 红色警告弹窗 ──

    private const val WARN_COLOR_LIGHT = 0xFFE53935.toInt()  // 浅色模式红
    private const val WARN_COLOR_DARK  = 0xFFEF5350.toInt()  // 深色模式红（稍亮，提高对比度）

    /**
     * 显示红色警告确认弹窗 —— 专属样式，醒目警示用户。
     *
     * 视觉特征：
     * - 基于 cardBg 的淡红底色 + 红色半透描边
     * - 标题/图标/分隔线均为红色（自动适配明暗模式）
     * - 主按钮红色实色 + 白字白图标，次按钮红色描边
     * - Dim 遮罩 0.15f（比普通弹窗更暗）
     *
     * @return 已显示的 [Dialog] 实例，调用方可持有以控制生命周期
     */
    fun showWarningConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        onConfirm: () -> Unit
    ): Dialog {
        val dialog = createAnimatedDialog(context)
        dialog.setContentView(R.layout.layout_common_dialog)

        val isDark = ThemeColors.isDark(context)
        val warnColor = if (isDark) WARN_COLOR_DARK else WARN_COLOR_LIGHT
        val textPrimary = ThemeColors.textPrimary(context)
        val cardBg = ThemeColors.cardBg(context)
        val density = context.resources.displayMetrics.density

        // 淡红底色：cardBg 上覆盖 8%(浅色) / 12%(深色) 不透明度的红色
        val warnBgAlpha = if (isDark) 0x1F else 0x14  // 深色 12% / 浅色 8%
        val warnBg = blendColorOn(cardBg, warnColor, warnBgAlpha)
        // 描边：红色 35%(浅色) / 50%(深色) 不透明度
        val warnBorderAlpha = if (isDark) 0x80 else 0x59  // 深色 50% / 浅色 35%
        val warnBorder = (warnColor and 0x00FFFFFF) or (warnBorderAlpha shl 24)
        // 分隔线：红色 18%(浅色) / 25%(深色) 不透明度
        val warnDividerAlpha = if (isDark) 0x40 else 0x2E
        val warnDivider = (warnColor and 0x00FFFFFF) or (warnDividerAlpha shl 24)

        // ── 1. 根布局：红色描边 + 淡红底色 ──
        val root = dialog.findViewById<ViewGroup>(android.R.id.content)
            ?.let { if (it.childCount > 0) it.getChildAt(0) as? ViewGroup else it }
        root?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(warnBg)
                cornerRadius = 16f * density
                setStroke((2 * density).toInt(), warnBorder)
            }
            elevation = 24f
        }

        // ── 2. 图标 + 标题：红色 ──
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(warnColor)
        }
        dialog.findViewById<TextView>(R.id.common_dialog_title).apply {
            text = title
            setTextColor(warnColor)
        }

        // ── 3. 警告消息 ──
        dialog.findViewById<LinearLayout>(R.id.common_dialog_content).apply {
            addView(TextView(context).apply {
                text = message
                textSize = 15f
                setTextColor(textPrimary)
                setLineSpacing(0f, 1.3f)
                gravity = Gravity.CENTER
            })
        }

        // ── 4. 底部红色分隔线 ──
        dialog.findViewById<View>(R.id.common_dialog_button_divider)?.apply {
            setBackgroundColor(warnDivider)
        }

        // ── 5+6. 按钮（dismiss 由 createAnimatedDialog 自动处理模糊清理+退场动画）──
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = confirmText
            backgroundTintList = ColorStateList.valueOf(warnColor)
            setTextColor(Color.WHITE)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = cancelText
            setTextColor(warnColor)
            strokeColor = ColorStateList.valueOf(warnColor)
            strokeWidth = (1 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
            }
        }

        // ── 7. 窗口设置 ──
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.15f)
            setWindowAnimations(R.style.DialogAnimationTheme)
        }
        applyDialogBlur(context, dialog)
        PopupViewUtil.autoAdjustDialogHeight(context, dialog, 0.88f)

        dialog.show()
        return dialog
    }

    /**
     * 在 [base] 颜色上以 [alpha] 不透明度叠加 [overlay] 颜色。
     * alpha 范围 0x00~0xFF。
     */
    private fun blendColorOn(base: Int, overlay: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val invA = 255 - a
        val r = (((base shr 16) and 0xFF) * invA + ((overlay shr 16) and 0xFF) * a) / 255
        val g = (((base shr 8) and 0xFF) * invA + ((overlay shr 8) and 0xFF) * a) / 255
        val b = ((base and 0xFF) * invA + (overlay and 0xFF) * a) / 255
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    // ── 公共输入面板 ──

    /**
     * 创建带主题配色的输入面板（EditText + 确定按钮），适用于自定义输入场景。
     * @param context 上下文
     * @param hint 输入框 hint 文本
     * @param validate 验证回调，返回 null=合法，返回 String=错误提示
     * @param onConfirm 确认回调
     */
    fun createInputPanel(
        context: Context,
        hint: String = "输入数值",
        validate: (String) -> String? = { null },
        onConfirm: (String) -> Unit
    ): LinearLayout {
        val accent = ThemeColors.accent(context)
        val cardBg = ThemeColors.cardBg(context)
        val textPrimary = ThemeColors.textPrimary(context)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
            alpha = 0f
            tag = "custom_input_panel"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val density = context.resources.displayMetrics.density
        val et = EditText(context).apply {
            tag = "custom_input_field"
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = 8f * density
                setStroke(1, if (ThemeColors.isDark(context))
                    0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(context))
            textSize = 13f
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        panel.addView(et)

        val btnConfirm = MaterialButton(context).apply {
            text = "确定"
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeColors.btnBg(context))
            setTextColor(0xFFFFFFFF.toInt())
            setCornerRadius((12f * density).toInt())
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (48 * density).toInt()
            ).apply { marginStart = (8 * density).toInt() }
        }
        AnimationUtil.applyScaleClickAnimation(btnConfirm) {
            val text = et.text.toString()
            val error = validate(text)
            if (error != null) {
                ToastUtil.showDropToast(context, ToastStyle.WARNING, error)
            } else {
                onConfirm(text)
            }
        }
        panel.addView(btnConfirm)

        return panel
    }

    /**
     * 面板渐入/渐出动画
     * @param panel 目标面板
     * @param show true=显示, false=隐藏
     * @param onEnd 动画结束回调
     */
    fun animatePanelVisibility(panel: View, show: Boolean, onEnd: () -> Unit = {}) {
        panel.animate().cancel()
        if (show) {
            panel.visibility = android.view.View.VISIBLE
            panel.alpha = 0f
            panel.translationY = 10f
            panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction(onEnd)
                .start()
        } else {
            panel.animate()
                .alpha(0f)
                .translationY(10f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    panel.visibility = android.view.View.GONE
                    panel.translationY = 0f
                    onEnd()
                }
                .start()
        }
    }

    /**
     * 预设芯片动画与高亮更新
     */
    private fun updateChipHighlight(chip: View, active: Boolean, accent: Int, textSecondary: Int) {
        val tv = chip as? TextView ?: return
        tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else textSecondary)
        val chipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (active) {
                setColor(accent)
            } else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp2px(chip.context, 1), (textSecondary and 0x00FFFFFF) or 0x60000000)
            }
            cornerRadius = 12f * dp2px(chip.context, 1)
        }
        tv.background = chipBg
    }

    /**
     * 创建字符串标签预设快捷按钮行，自动高亮当前值。
     * 适用于筛选类型、状态等文本选项。
     *
     * @param context 上下文
     * @param options 选项列表（id, label）
     * @param currentValue 当前选中 id
     * @param onSelect 选中回调
     * @return Pair(行View, 更新函数)
     */
    fun createStringPresetRow(
        context: Context,
        options: List<Pair<String, String>>,
        currentValue: String,
        onSelect: (String) -> Unit
    ): Pair<LinearLayout, (String) -> Unit> {
        val accent = ThemeColors.accent(context)
        val textSecondary = ThemeColors.textSecondary(context)
        val chips = mutableListOf<TextView>()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        options.forEach { (id, label) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp2px(context, 10), dp2px(context, 4), dp2px(context, 10), dp2px(context, 4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(id) }
            }
            chips.add(chip)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp2px(context, 6) }
            row.addView(chip, params)
        }

        val update: (String) -> Unit = { selected ->
            chips.forEachIndexed { i, chip ->
                updateChipHighlight(chip, options[i].first == selected, accent, textSecondary)
            }
        }
        update(currentValue)

        return row to update
    }

    /**
     * 创建双栏网格字符串选项行（适用于较多选项，如筛选类型）。
     */
    fun createStringPresetGrid(
        context: Context,
        options: List<Pair<String, String>>,
        currentValue: String,
        columns: Int = 2,
        onSelect: (String) -> Unit
    ): Pair<android.widget.GridLayout, (String) -> Unit> {
        val accent = ThemeColors.accent(context)
        val textSecondary = ThemeColors.textSecondary(context)
        val chips = mutableListOf<TextView>()

        val grid = android.widget.GridLayout(context).apply {
            columnCount = columns
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        options.forEach { (id, label) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp2px(context, 10), dp2px(context, 6), dp2px(context, 10), dp2px(context, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(id) }
            }
            chips.add(chip)
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp2px(context, 3), dp2px(context, 3), dp2px(context, 3), dp2px(context, 3))
            }
            grid.addView(chip, params)
        }

        val update: (String) -> Unit = { selected ->
            chips.forEachIndexed { i, chip ->
                updateChipHighlight(chip, options[i].first == selected, accent, textSecondary)
            }
        }
        update(currentValue)

        return grid to update
    }

    /**
     * 创建常用值预设快捷按钮行，自动高亮当前值
     * @param context 上下文
     * @param values 预设值列表
     * @param formatLabel 格式化标签 (value) -> String
     * @param currentValue 当前选中值
     * @param onSelect 选中回调
     * @return Pair(行View, 更新函数) — 调用 update(value) 刷新高亮
     */
    fun createPresetRow(
        context: Context,
        values: List<Int>,
        formatLabel: (Int) -> String,
        currentValue: Int,
        onSelect: (Int) -> Unit
    ): Pair<LinearLayout, (Int) -> Unit> {
        val accent = ThemeColors.accent(context)
        val textSecondary = ThemeColors.textSecondary(context)
        val chips = mutableListOf<TextView>()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(context, 12) }
        }

        values.forEach { value ->
            val chip = TextView(context).apply {
                text = formatLabel(value)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp2px(context, 10), dp2px(context, 4), dp2px(context, 10), dp2px(context, 4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(value) }
            }
            chips.add(chip)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp2px(context, 6) }
            row.addView(chip, params)
        }

        val update: (Int) -> Unit = { selected ->
            chips.forEachIndexed { i, chip ->
                updateChipHighlight(chip, values[i] == selected, accent, textSecondary)
            }
        }
        update(currentValue)

        return row to update
    }

    private fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}