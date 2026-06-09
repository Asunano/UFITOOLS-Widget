package com.ufi_toolswidget.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.ufi_toolswidget.R

/**
 * 全局动画工具类，提供统一的模糊起伏切换和弹窗渐变效果。
 */
object AnimationUtil {

    @Volatile
    var pendingTransitionBitmap: java.lang.ref.WeakReference<Bitmap>? = null

    // ========== 基础动画 ==========

    /**
     * 柔和更新文本内容：平滑模糊起伏渐变（无边框，带步进）。
     * Android 12+ 使用 RenderEffect + DECAL 模式。
     * 低版本使用 Alpha + Scale 回退。
     */
    fun smoothUpdateText(tv: TextView, newText: String) {
        if (tv.text == newText) {
            tv.alpha = 1f
            return
        }

        // 如果上一个动画还在播放，直接跳到最终状态，避免堆积
        (tv.tag as? Animator)?.let { if (it.isRunning) it.end() }
        tv.tag = null

        tv.animate().cancel()
        tv.alpha = 1f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 降低半径以减小边缘压力，增加步进提升丝滑度
            val targetBlur = 10f
            val blurAnim = ValueAnimator.ofFloat(0f, targetBlur)
            blurAnim.duration = 260
            blurAnim.addUpdateListener { anim ->
                val radius = anim.animatedValue as Float
                if (radius > 0.1f) {
                    tv.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
                } else {
                    tv.setRenderEffect(null)
                }
            }
            blurAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tv.text = newText
                    val unblurAnim = ValueAnimator.ofFloat(targetBlur, 0f)
                    unblurAnim.duration = 320
                    unblurAnim.addUpdateListener { anim ->
                        val radius = anim.animatedValue as Float
                        if (radius > 0.1f) {
                            tv.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
                        } else {
                            tv.setRenderEffect(null)
                        }
                    }
                    unblurAnim.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            tv.tag = null
                        }
                    })
                    unblurAnim.start()
                }
            })
            tv.tag = blurAnim  // 保存引用，支持中断
            blurAnim.start()
        } else {
            tv.animate()
                .alpha(0.3f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(260)
                .withEndAction {
                    tv.text = newText
                    tv.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(320)
                        .start()
                }
                .start()
        }
    }

    // ========== Dialog 背景模糊 ==========

    /**
     * 为 Dialog 应用背景模糊入场步进动画 (Android 12+)
     */
    fun applyDialogBlurIn(dialog: Dialog, targetBlur: Int = 110, targetDim: Float = 0.08f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            setDimAmount(0f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480
                addUpdateListener { anim ->
                    val ratio = anim.animatedValue as Float
                    attributes = attributes?.apply {
                        blurBehindRadius = (targetBlur * ratio).toInt()
                    }
                    setDimAmount(targetDim * ratio)
                }
                start()
            }
        }
    }

    /**
     * 为 Dialog 执行统一退场动画。
     *
     * - API 31+：350ms AccelerateInterpolator(1.8f)，缩放 0.88f + 淡出 + 模糊/遮罩同步消退
     * - API <31：280ms AccelerateInterpolator(1.8f)，缩放 0.88f + 淡出
     *
     * 调用方无需关心 window 动画抑制，函数内部已处理。
     */
    fun applyDialogBlurOut(dialog: Dialog, onComplete: () -> Unit) {
        val window = dialog.window ?: run { onComplete(); return }
        window.setWindowAnimations(0)  // 立即抑制 XML 退出动画

        val contentView = window.findViewById<View>(android.R.id.content)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ── Android 12+：350ms 内容缩放淡出 + 模糊/遮罩同步消退 ──
            val startBlur = window.attributes.blurBehindRadius
            val startDim = window.attributes.dimAmount

            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 350
                interpolator = AccelerateInterpolator(1.8f)
                addUpdateListener { anim ->
                    val ratio = anim.animatedValue as Float
                    // 内容缩放 + 淡出
                    contentView?.apply {
                        alpha = ratio
                        val scale = 0.88f + 0.12f * ratio  // 1.0f → 0.88f
                        scaleX = scale
                        scaleY = scale
                    }
                    // 模糊半径 + 遮罩同步消退
                    window.attributes = window.attributes?.apply {
                        blurBehindRadius = (startBlur * ratio).toInt()
                    }
                    window.setDimAmount(startDim * ratio)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        window.attributes = window.attributes?.apply {
                            flags = flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                            blurBehindRadius = 0
                            dimAmount = 0f
                        }
                        onComplete()
                    }
                })
                start()
            }
        } else {
            // ── 低版本：280ms 缩放 + 淡出 ──
            contentView?.animate()
                ?.alpha(0f)
                ?.scaleX(0.88f)
                ?.scaleY(0.88f)
                ?.setDuration(280)
                ?.setInterpolator(AccelerateInterpolator(1.8f))
                ?.withEndAction(onComplete)
                ?.start()
                ?: onComplete()
        }
    }

    // ========== 传统 Crossfade (供 MainActivity 等通配使用) ==========

    fun applyCrossfadeEnterFromRecreate(activity: Activity, duration: Long = 600) {
        val bitmap = pendingTransitionBitmap?.get() ?: return
        pendingTransitionBitmap = null
        val rootRoot = activity.window.decorView as? ViewGroup
        if (rootRoot == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val overlay = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            elevation = 999999f
        }
        rootRoot.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay.animate().alpha(0f).setDuration(duration)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rootRoot.removeView(overlay)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                override fun onAnimationCancel(animation: Animator) {
                    rootRoot.removeView(overlay)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            })
            .start()
    }

    // ========== 主题切换专用：中心扩散圆形揭露 ==========

    /**
     * 配色原位切换圆形揭露动画。
     *
     * 策略：旧截图 backdrop 置顶遮住一切 → onMutation 静默改主题（content 在下层更新）→
     * decorView.post 等待布局 → 合成全屏 overlay → CircularReveal overlay 全屏扩散。
     *
     * Pulse 模式中旧/新界面结构完全一致（仅颜色不同），
     * 圆圈边界难以察觉，因此全程零延迟强模糊强化切换感知。
     */
    fun applyCircleRevealPulse(activity: Activity, duration: Long = 1000, onMutation: () -> Unit) {
        val oldBitmap = captureActivity(activity) ?: run { onMutation(); return }
        val decorView = activity.window.decorView as? ViewGroup ?: run { onMutation(); return }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: run { onMutation(); return }
        if (decorView.width <= 0 || decorView.height <= 0) { onMutation(); return }

        // backdrop 加到最上层，遮住 content（用户看到旧截图）
        val backdrop = ImageView(activity).apply {
            setImageBitmap(oldBitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        decorView.addView(backdrop,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 静默改主题（content 在下层更新，用户看不到）
        onMutation()

        // 等待布局完成，再捕获、合成、reveal（pulse 模式：全程强模糊）
        performReveal(activity, decorView, content, backdrop, oldBitmap, duration)
    }

    /**
     * 统一引擎：等待 content 布局完成 → 捕获 content（新主题）→ 合成全屏 overlay →
     * CircularReveal overlay → 旧截图模糊淡出 → 清理。
     */
    private fun performReveal(
        activity: Activity, decorView: ViewGroup, content: ViewGroup,
        backdrop: ImageView, oldBitmap: Bitmap, duration: Long
    ) {
        decorView.post {
            if (!decorView.isAttachedToWindow || decorView.width <= 0 || decorView.height <= 0) {
                decorView.removeView(backdrop)
                if (!oldBitmap.isRecycled) oldBitmap.recycle()
                return@post
            }

            val pageBg = ThemeColors.pageBg(activity)
            val overlay = buildRevealOverlay(activity, decorView, content, pageBg)
            if (overlay == null) {
                decorView.removeView(backdrop)
                if (!oldBitmap.isRecycled) oldBitmap.recycle()
                return@post
            }

            // overlay 放在最上层（backdrop 之上）
            decorView.addView(overlay,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val cx = decorView.width / 2
            val cy = decorView.height / 2
            val finalRadius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

            try {
                val anim = android.view.ViewAnimationUtils.createCircularReveal(
                    overlay, cx, cy, 0f, finalRadius)
                anim.duration = duration
                anim.interpolator = AccelerateDecelerateInterpolator()

                // 全程零延迟强模糊，强化切换感知
                animateBackdropBlurOut(backdrop, 0L, duration)

                anim.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        decorView.removeView(overlay)
                        decorView.removeView(backdrop)
                        if (!oldBitmap.isRecycled) oldBitmap.recycle()
                    }
                })
                anim.start()
            } catch (e: Exception) {
                DebugLogger.w("AnimationUtil", "Reveal animation failed: ${e.message}")
                decorView.removeView(overlay)
                decorView.removeView(backdrop)
                if (!oldBitmap.isRecycled) oldBitmap.recycle()
            }
        }
    }

    /**
     * 合成全屏过渡 overlay：在 decorView 大小的 bitmap 上用 pageBg 铺底，
     * 再把已按新主题渲染的 content 绘制到对应坐标上。
     */
    private fun buildRevealOverlay(activity: Activity, decorView: ViewGroup, content: ViewGroup, pageBg: Int): ImageView? {
        val w = decorView.width
        val h = decorView.height
        if (w <= 0 || h <= 0) return null

        val bitmap = try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(pageBg)
            if (content.width > 0 && content.height > 0) {
                val loc = IntArray(2)
                content.getLocationInWindow(loc)
                canvas.save()
                canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                content.draw(canvas)
                canvas.restore()
            }
            bmp
        } catch (e: Exception) { DebugLogger.w("AnimationUtil", "buildRevealOverlay failed: ${e.message}"); return null }

        return ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
    }

    /**
     * 对旧截图层执行渐进淡出。
     * 与 alpha 淡出叠加，强化切换感知。
     */
    private fun animateBackdropBlurOut(backdrop: ImageView, delayMs: Long, durationMs: Long) {
        backdrop.animate()
            .alpha(0f)
            .setStartDelay(delayMs)
            .setDuration(durationMs)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .start()
    }

    private fun captureActivity(activity: Activity): Bitmap? {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) { null }
    }

    fun applyScaleClickAnimation(view: View, onClick: () -> Unit) {
        view.stateListAnimator = null
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    if (event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height) {
                        v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onClick()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            true
        }
    }
}
