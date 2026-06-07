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
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R

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
     * 创建一个已设置透明主题的 Dialog。
     * 调用方需要自行 setContentView、管理生命周期。
     */
    fun createDialog(context: Context): Dialog {
        val dialog = Dialog(context, R.style.Theme_UFITOOLSWidget_Transparent)
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
        val borderColor = if (SPUtil.getNightMode(context) == AppCompatDelegate.MODE_NIGHT_YES)
            0x4DFFFFFF.toInt() else 0x35000000

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = dp2px(context, 16).toFloat()
            setStroke(2, borderColor)
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

    /** API 26-30：截屏 + 多级缩放模拟毛玻璃效果 */
    private fun applyLegacyBlur(context: Context, dialog: Dialog) {
        try {
            val decorView = dialog.window?.decorView?.rootView ?: return
            val vw = decorView.width
            val vh = decorView.height
            if (vw <= 0 || vh <= 0) return

            val capture = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(capture))

            val smallW = (vw * 0.06f).toInt().coerceAtLeast(4)
            val smallH = (vh * 0.06f).toInt().coerceAtLeast(4)
            val small = Bitmap.createScaledBitmap(capture, smallW, smallH, true)
            capture.recycle()

            val blurred = Bitmap.createScaledBitmap(small, vw, vh, true)
            small.recycle()

            dialog.window?.setBackgroundDrawable(BitmapDrawable(context.resources, blurred))
        } catch (e: Exception) {
            Log.w(TAG, "Legacy blur failed: ${e.message}")
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
            if (onPrimaryClick != null) onPrimaryClick(dialog) else dialog.dismiss()
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
     * 一步到位：创建弹窗 → 主题着色 → 填充内容 → 配置按钮 → 显示。
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
        val dialog = createDialog(context)
        dialog.setContentView(R.layout.layout_common_dialog)
        applyThemeToDialogRoot(context, dialog)
        configureAndShow(context, dialog, title, iconRes, onFill,
            primaryBtnText, onPrimaryClick, secondaryBtnText, onSecondaryClick, widthRatio)
        return dialog
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
        val dialog = Dialog(context, R.style.Theme_UFITOOLSWidget_Transparent)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
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

        // ── 5. 主按钮：红色实色，白字 ──
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = confirmText
            backgroundTintList = ColorStateList.valueOf(warnColor)
            setTextColor(Color.WHITE)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        // ── 6. 次按钮：红色描边 ──
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = cancelText
            setTextColor(warnColor)
            strokeColor = ColorStateList.valueOf(warnColor)
            strokeWidth = (1 * density).toInt()
            setOnClickListener { dialog.dismiss() }
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

    private fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
