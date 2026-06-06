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
    var pendingTransitionBitmap: Bitmap? = null
    
    @Volatile
    var transitionInitiator: String? = null

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
                    unblurAnim.start()
                }
            })
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
                duration = 350
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
     * 为 Dialog 执行背景模糊退场步进动画 (Android 12+)，
     * 同时通过动画缩放和淡出 Dialog 内容，最后调用 onComplete。
     */
    fun applyDialogBlurOut(dialog: Dialog, onComplete: () -> Unit) {
        val window = dialog.window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || window == null) {
            onComplete()
            return
        }

        val contentView = window.findViewById<View>(android.R.id.content)
        val attrs = window.attributes
        val startBlur = attrs.blurBehindRadius
        val startDim = window.attributes.dimAmount

        // 统一动画：退场动画节奏优化
        // 缩短时长至 260ms，解决关闭过慢的问题，节奏更加轻快
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260
            interpolator = AccelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val ratio = anim.animatedValue as Float
                window.attributes = window.attributes?.apply {
                    blurBehindRadius = (startBlur * ratio).toInt()
                }
                window.setDimAmount(startDim * ratio)

                contentView?.alpha = ratio
                contentView?.scaleX = 0.96f + 0.04f * ratio
                contentView?.scaleY = 0.96f + 0.04f * ratio
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    window.setWindowAnimations(0)
                    onComplete()
                }
            })
            start()
        }
    }

    // ========== 传统 Crossfade (供 MainActivity 等通配使用) ==========

    fun applyCrossfadeExitForRecreate(activity: Activity, onExit: () -> Unit) {
        pendingTransitionBitmap = captureActivity(activity)
        onExit()
    }

    fun applyCrossfadeEnterFromRecreate(activity: Activity, duration: Long = 600) {
        val bitmap = pendingTransitionBitmap ?: return
        pendingTransitionBitmap = null
        val rootRoot = activity.window.decorView as? ViewGroup ?: return
        val overlay = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            elevation = 999999f
        }
        rootRoot.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay.animate().alpha(0f).setDuration(duration).withEndAction {
            rootRoot.removeView(overlay)
            bitmap.recycle()
        }.start()
    }

    // ========== 主题切换专用：中心扩散圆形揭露 ==========

    fun applyCircleRevealExit(activity: Activity, onExit: () -> Unit) {
        pendingTransitionBitmap = captureActivity(activity)
        transitionInitiator = activity::class.java.name
        onExit()
    }

    /**
     * 深浅色模式切换进入动画（Activity 重建后调用）。
     *
     * 策略：旧截图 backdrop 置顶遮住新 content → decorView.post 等待布局完成 →
     * 合成全屏 overlay（新主题 content + pageBg）→ CircularReveal overlay 全屏扩散。
     */
    fun applyCircleRevealEnter(activity: Activity, duration: Long = 800) {
        if (transitionInitiator != activity::class.java.name) return
        val oldBitmap = pendingTransitionBitmap ?: return
        pendingTransitionBitmap = null
        transitionInitiator = null

        val decorView = activity.window.decorView as? ViewGroup ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        if (decorView.width <= 0 || decorView.height <= 0) return

        // backdrop 加到最上层，遮住新 content
        val backdrop = ImageView(activity).apply {
            setImageBitmap(oldBitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        decorView.addView(backdrop,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // content 已渲染新主题（在 backdrop 下层），布局完成后捕获并做 reveal
        performReveal(activity, decorView, content, backdrop, oldBitmap, duration, pulse = false)
    }

    /**
     * 配色原位切换圆形揭露动画。
     *
     * 策略：旧截图 backdrop 置顶遮住一切 → onMutation 静默改主题（content 在下层更新）→
     * decorView.post 等待布局 → 合成全屏 overlay → CircularReveal overlay 全屏扩散。
     *
     * 与 [applyCircleRevealEnter] 不同：Pulse 中旧/新界面结构完全一致（仅颜色不同），
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
        performReveal(activity, decorView, content, backdrop, oldBitmap, duration, pulse = true)
    }

    /**
     * 统一引擎：等待 content 布局完成 → 捕获 content（新主题）→ 合成全屏 overlay →
     * CircularReveal overlay → 旧截图模糊淡出 → 清理。
     *
     * @param pulse true=配色切换模式（全程零延迟强模糊），false=深浅切换模式（延迟叠加模糊）
     */
    private fun performReveal(
        activity: Activity, decorView: ViewGroup, content: ViewGroup,
        backdrop: ImageView, oldBitmap: Bitmap, duration: Long,
        pulse: Boolean = false
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

                // Pulse：模糊从第 0ms 开始持续全程
                // Enter：延迟启动避免过早遮挡 reveal
                val fadeDelay = if (pulse) 0L else (duration * 0.08).toLong()
                val fadeDuration = if (pulse) duration else (duration * 0.82).toLong()
                animateBackdropBlurOut(backdrop, fadeDelay, fadeDuration)

                anim.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        decorView.removeView(overlay)
                        decorView.removeView(backdrop)
                        if (!oldBitmap.isRecycled) oldBitmap.recycle()
                    }
                })
                anim.start()
            } catch (_: Exception) {
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
        } catch (_: Exception) { return null }

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
