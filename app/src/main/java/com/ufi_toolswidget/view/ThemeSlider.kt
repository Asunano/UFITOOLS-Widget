package com.ufi_toolswidget.view

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.ufi_toolswidget.util.ThemeColors

class ThemeSlider @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var stepSize: Float = 1f

    var currentValue: Float = 5f
        set(value) {
            val clamped = value.coerceIn(minValue, maxValue)
            val stepped = if (stepSize > 0) Math.round(clamped / stepSize) * stepSize else clamped
            val snapped = stepped.coerceIn(minValue, maxValue)
            field = snapped
            // updateColors() 已从此处移除 — 颜色只需在 init 和 onThemeChange 时设置
            // 实时回调（不受抑制，用于 valueLabel 实时更新）
            onValueChanging?.invoke(snapped)
            // 拖动时抑制 onValueChange，释放时才通知（避免快捷按钮闪烁）
            if (!suppressValueChange) {
                onValueChange?.invoke(snapped)
            }
            invalidate()
        }

    /** 刻度步长，<= 0 时不显示刻度 */
    var tickStepSize: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidateTickCache()
            }
        }
    /** 刻度标签格式化器，null 时不显示标签文字 */
    var tickLabelFormatter: ((Float) -> String)? = null
        set(value) {
            field = value
            invalidateTickCache()
        }

    var minValue: Float = 5f
        set(value) {
            if (field != value) {
                field = value
                invalidateTickCache()
            }
        }
    var maxValue: Float = 120f
        set(value) {
            if (field != value) {
                field = value
                invalidateTickCache()
            }
        }

    var onValueChange: ((Float) -> Unit)? = null
    /** 实时值变化回调（拖动过程中无条件触发，不受 suppressValueChange 抑制） */
    var onValueChanging: ((Float) -> Unit)? = null

    private var accentColor = 0
    private var trackBgColor = 0
    private var textColor = 0
    // 缓存刻度值，避免每帧重复计算
    private var cachedTickValues: List<Float>? = null
    private var cachedTickMinVal = 0f
    private var cachedTickMaxVal = 0f
    private var cachedTickStepSize = 0f
    // 缓存轨道padding，避免每帧重复测量文字
    private var cachedPadding: Float? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private var thumbRadius = 0f
    private var trackHeight = 0f
    private var isDragging = false
    /** 拖动时抑制快捷按钮通知 */
    private var suppressValueChange = false
    /** 标签渐入渐出 + 模糊动画进度 0..1 */
    private var labelsAlpha: Float = 0f
    /** 刻度高亮凸起动画进度 0..1 */
    private var highlightProgress: Float = 0f
    /** 复用 BlurMaskFilter 对象，避免每帧创建 */
    private var blurMaskFilter: BlurMaskFilter? = null
    private var lastBlurRadius = 0f

    private val density = context.resources.displayMetrics.density

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    init {
        trackHeight = 6f * density
        thumbRadius = 8f * density

        trackPaint.style = Paint.Style.FILL
        trackPaint.isAntiAlias = true

        activeTrackPaint.style = Paint.Style.FILL
        activeTrackPaint.isAntiAlias = true

        thumbPaint.style = Paint.Style.FILL
        thumbPaint.isAntiAlias = true

        thumbStrokePaint.style = Paint.Style.STROKE
        thumbStrokePaint.isAntiAlias = true
        thumbStrokePaint.strokeWidth = 2f * density

        tickPaint.style = Paint.Style.FILL
        tickPaint.isAntiAlias = true

        tickLabelPaint.style = Paint.Style.FILL
        tickLabelPaint.isAntiAlias = true
        tickLabelPaint.textAlign = Paint.Align.CENTER
        tickLabelPaint.textSize = 10f * density

        updateColors()
    }

    private fun updateColors() {
        accentColor = ThemeColors.accent(context)
        trackBgColor = (accentColor and 0x00FFFFFF) or 0x26000000
        textColor = ThemeColors.textPrimary(context)

        activeTrackPaint.color = accentColor
        trackPaint.color = trackBgColor
        thumbPaint.color = if (com.ufi_toolswidget.util.ThemeColors.isDark(context))
            com.ufi_toolswidget.util.ThemeColors.textSecondary(context) else accentColor
        thumbStrokePaint.color = 0xFFFFFFFF.toInt()
        tickPaint.color = (textColor and 0x00FFFFFF) or 0x50000000
        tickLabelPaint.color = textColor
    }

    /**
     * 计算统一 padding：同时容纳 thumb 和刻度标签，确保刻度点与标签位置一致。
     * 结果缓存：仅当刻度参数（tickStepSize/tickLabelFormatter/tick值）变化时重新计算。
     */
    private fun computeTrackPadding(): Float {
        if (cachedPadding != null) return cachedPadding!!

        val basePadding = thumbRadius
        val padding = if (tickStepSize > 0f && tickLabelFormatter != null) {
            var maxW = 0f
            for (v in getTickValues()) {
                val displayVal = Math.round(v * 100f) / 100f
                val lw = tickLabelPaint.measureText(tickLabelFormatter!!(displayVal))
                if (lw > maxW) maxW = lw
            }
            maxOf(basePadding, maxW / 2f)
        } else {
            basePadding
        }
        cachedPadding = padding
        return padding
    }

    /**
     * 返回对齐到步长整数倍的刻度值列表。
     * 第一个刻度始终为 minValue，最后一个始终为 maxValue，
     * 中间刻度对齐到 tickStepSize 的整数倍。
     *
     * 步长根据范围动态计算（保持为 tickStepSize 的整数倍），
     * 使刻度总数控制在 6 个左右，避免大范围场景下刻度过密。
     *
     * 结果缓存：当 minValue/maxValue/tickStepSize 未变化时返回缓存列表，
     * 避免 onDraw 中每帧重复计算。
     */
    private fun getTickValues(): List<Float> {
        if (tickStepSize <= 0f || maxValue <= minValue) return emptyList()

        // 缓存命中：三个依赖参数均未变化
        if (cachedTickValues != null
            && cachedTickMinVal == minValue
            && cachedTickMaxVal == maxValue
            && cachedTickStepSize == tickStepSize) {
            return cachedTickValues!!
        }

        val range = maxValue - minValue

        // 动态计算步长倍数，使刻度数接近 6 个
        val maxTicks = 6
        val intervals = maxTicks - 1  // 5
        val idealStep = range / intervals
        val multiplier = Math.ceil(idealStep.toDouble() / tickStepSize.toDouble()).toInt().coerceAtLeast(1)
        val step = multiplier * tickStepSize

        val result = mutableListOf(minValue)

        val firstAligned = Math.ceil(minValue.toDouble() / step.toDouble()).toFloat() * step
        var v = firstAligned

        // 首个对齐值刚好等于 minValue（已添加），从下一个步长开始
        if (v <= minValue + 0.001f) {
            v += step
        }

        while (v < maxValue - 0.001f) {
            result.add(v)
            v += step
        }

        if (result.last() < maxValue - 0.001f) {
            result.add(maxValue)
        }

        // 更新缓存
        cachedTickValues = result
        cachedTickMinVal = minValue
        cachedTickMaxVal = maxValue
        cachedTickStepSize = tickStepSize
        return result
    }

    /** 刻度参数变化时清除相关缓存 */
    private fun invalidateTickCache() {
        cachedTickValues = null
        cachedPadding = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padding = computeTrackPadding()
        val trackLeft = padding
        val trackRight = w - padding
        val trackTop = h / 2f - trackHeight / 2f
        val trackBottom = trackTop + trackHeight
        val trackWidth = trackRight - trackLeft

        trackRect.set(trackLeft, trackTop, trackRight, trackBottom)
        val cornerRadius = trackHeight / 2f

        // 背景轨道
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        // 活跃轨道
        val ratio = ((currentValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        val activeWidth = trackWidth * ratio
        if (activeWidth > 0f) {
            val activeRect = RectF(trackLeft, trackTop, trackLeft + activeWidth, trackBottom)
            canvas.drawRoundRect(activeRect, cornerRadius, cornerRadius, activeTrackPaint)
        }

        // 滑块thumb（带凸起动画）
        val thumbCenterX = trackLeft + activeWidth
        val thumbCenterY = h / 2f
        val thumbScale = lerp(1f, 1.2f, highlightProgress)
        val popThumbRadius = thumbRadius * thumbScale

        canvas.drawCircle(thumbCenterX, thumbCenterY, popThumbRadius, thumbPaint)
        canvas.drawCircle(thumbCenterX, thumbCenterY, popThumbRadius - 1f * density, thumbStrokePaint)

        // 刻度线和标签（使用同一坐标体系，确保位置一致）
        if (tickStepSize > 0 && maxValue > minValue) {
            val tickRadius = 2.5f * density
            val tickY = trackBottom + tickRadius + 5f * density
            for (tickValue in getTickValues()) {
                val tickRatio = ((tickValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                val tickCenterX = trackLeft + trackWidth * tickRatio
                canvas.drawCircle(tickCenterX, tickY, tickRadius, tickPaint)
            }

            // 刻度标签（滑动时淡入显示，与刻度点同坐标系）
            if (tickLabelFormatter != null && labelsAlpha > 0.01f) {
                drawTickLabels(canvas, trackLeft, trackWidth, labelsAlpha)
            }
        }

        // 标签渐入渐出 + 模糊动画
        val targetAlpha = if (isDragging) 1f else 0f
        labelsAlpha += (targetAlpha - labelsAlpha) * 0.075f
        if (Math.abs(labelsAlpha - targetAlpha) > 0.01f) invalidate()

        // 高亮凸起动画
        val highlightTarget = if (isDragging && labelsAlpha > 0.3f) 1f else 0f
        highlightProgress += (highlightTarget - highlightProgress) * 0.15f
        if (Math.abs(highlightProgress - highlightTarget) > 0.01f) invalidate()
    }

    /**
     * 绘制所有刻度标签（渐入渐出 + 模糊动画）
     */
    private fun drawTickLabels(
        canvas: Canvas,
        trackLeft: Float,
        trackWidth: Float,
        alpha: Float
    ) {
        val formatter = tickLabelFormatter ?: return
        val trackBottom = height / 2f + trackHeight / 2f
        val labelY = trackBottom + 20f * density

        // 收集所有刻度标签
        val tickData = mutableListOf<Pair<Float, String>>()
        for (tickValue in getTickValues()) {
            val displayVal = Math.round(tickValue * 100f) / 100f
            tickData.add(tickValue to formatter(displayVal))
        }

        // 模糊动画：alpha 越低越模糊（二次曲线，强化模糊感）
        val blurFactor = 1f - alpha
        val blurRadius = blurFactor * blurFactor * 12f * density
        if (blurRadius > 0.5f) {
            // 复用 BlurMaskFilter，避免每帧创建
            if (blurMaskFilter == null || lastBlurRadius != blurRadius) {
                blurMaskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                lastBlurRadius = blurRadius
            }
            tickLabelPaint.maskFilter = blurMaskFilter
        } else {
            tickLabelPaint.maskFilter = null
        }

        // 透明度 + 样式
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        tickLabelPaint.color = (textColor and 0x00FFFFFF) or (alphaInt shl 24)
        tickLabelPaint.isFakeBoldText = false
        tickLabelPaint.textSize = 10f * density

        for ((tickVal, label) in tickData) {
            val ratio = ((tickVal - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
            val x = trackLeft + trackWidth * ratio
            canvas.drawText(label, x, labelY, tickLabelPaint)
        }

        // 恢复
        tickLabelPaint.maskFilter = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var minHeight = (thumbRadius * 2 + 8f * density).toInt()
        if (tickStepSize > 0) {
            minHeight += if (tickLabelFormatter != null) (36f * density).toInt() else (14f * density).toInt()
        }
        val height = View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(minHeight)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val w = width.toFloat()
        val padding = computeTrackPadding()
        val trackLeft = padding
        val trackRight = w - padding
        val trackWidth = trackRight - trackLeft

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                suppressValueChange = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateValueFromX(event.x, trackLeft, trackWidth)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateValueFromX(event.x, trackLeft, trackWidth)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                suppressValueChange = false
                parent?.requestDisallowInterceptTouchEvent(false)
                updateValueFromX(event.x, trackLeft, trackWidth)
                // 释放时一次性通知快捷按钮
                onValueChange?.invoke(currentValue)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateValueFromX(x: Float, trackLeft: Float, trackWidth: Float) {
        val rawRatio = ((x - trackLeft) / trackWidth).coerceIn(0f, 1f)
        val rawValue = minValue + rawRatio * (maxValue - minValue)
        currentValue = rawValue
    }
}