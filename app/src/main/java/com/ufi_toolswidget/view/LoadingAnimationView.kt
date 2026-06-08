package com.ufi_toolswidget.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.ufi_toolswidget.util.ThemeColors

/**
 * 自定义载入动画指示器。
 * 绘制一个旋转的圆弧（accent 色），取代原生 ProgressBar。
 * 提供 updateColors() 供 ThemeUtil 在主题切换时调用。
 */
class LoadingAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private val strokeWidth = 3f * density
    private val arcRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@LoadingAnimationView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@LoadingAnimationView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private var rotation = 0f
    private var sweepAngle = 300f
    private var animator: ValueAnimator? = null

    init {
        applyThemeColors()
    }

    /** 跟随主题切换更新颜色 */
    fun applyThemeColors() {
        val accent = ThemeColors.accent(context)
        arcPaint.color = accent
        trackPaint.color = (accent and 0x00FFFFFF) or 0x26000000
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                // 呼吸效果：sweep 在旋转过程中在 270°~330° 之间振荡（1 周期/转 ≈ 1Hz）
                sweepAngle = 300f + 30f * kotlin.math.sin(
                    rotation * kotlin.math.PI.toFloat() / 180f
                )
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfStroke = strokeWidth / 2f
        arcRect.set(halfStroke, halfStroke, width - halfStroke, height - halfStroke)

        // 背景轨道（半透明圆环）
        canvas.drawCircle(width / 2f, height / 2f, (width - strokeWidth) / 2f, trackPaint)

        // 旋转的圆弧（留 60° 缺口，形成旋转扫光效果）
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(arcRect, -90f, sweepAngle, false, arcPaint)
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (40 * density).toInt()
        val size = resolveSize(defaultSize, widthMeasureSpec)
        setMeasuredDimension(size, size)
    }
}
