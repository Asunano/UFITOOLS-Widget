package com.ufi_toolswidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 警报历史页面。
 *
 * 自定义列表项 UI（非 layout_common_setting_item），支持：
 * - 未读条目左侧主题色条 + 标题加粗，视觉突出
 * - 左右滑动：右滑标记已读，左滑删除（带弹性动画）
 * - 点击条目弹出详情 + 操作弹窗
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
        private const val SWIPE_THRESHOLD = 120  // dp
        private const val ACTION_BAR_PADDING = 6  // dp
    }

    private lateinit var alertList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var tvSubtitle: TextView

    private val shortTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_alert_history)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        alertList = findViewById(R.id.alert_list)
        emptyState = findViewById(R.id.empty_state)
        actionBar = findViewById(R.id.action_bar)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        buildActionBar()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        refreshList()
    }

    // ═══════════════════════════════════════════
    // Action bar
    // ═══════════════════════════════════════════

    private fun buildActionBar() {
        actionBar.removeAllViews()
        val accent = ThemeColors.accent(this)
        val subColor = ThemeColors.textSecondary(this)

        actionBar.addView(buildChipButton("全部已读", R.drawable.ic_check, accent) {
            AlertHistoryManager.markAllRead(this)
            refreshList()
        })
        actionBar.addView(buildChipButton("清空", null, subColor) {
            showClearConfirmDialog()
        })
    }

    /**
     * 构建 chip 风格文字按钮：圆角描边背景 + 图标（可选）。
     */
    private fun buildChipButton(
        text: String,
        iconRes: Int?,
        color: Int,
        onClick: () -> Unit
    ): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), color and 0x60FFFFFF)
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(ACTION_BAR_PADDING)
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }

        if (iconRes != null) {
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(color)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), 0)
            })
        }

        row.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
        })

        AnimationUtil.applyScaleClickAnimation(row) { onClick() }
        return row
    }

    // ═══════════════════════════════════════════
    // 列表刷新
    // ═══════════════════════════════════════════

    private fun refreshList() {
        alertList.removeAllViews()
        val records = AlertHistoryManager.getAll(this)

        if (records.isEmpty()) {
            alertList.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            actionBar.visibility = View.GONE
            tvSubtitle.text = "暂无警报记录"
            return
        }

        alertList.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        actionBar.visibility = View.VISIBLE

        val unreadCount = records.count { !it.isRead }
        tvSubtitle.text = if (unreadCount > 0) {
            "共 ${records.size} 条，${unreadCount} 条未读"
        } else {
            "共 ${records.size} 条"
        }

        for (record in records) {
            alertList.addView(buildAlertItem(record))
        }
    }

    // ═══════════════════════════════════════════
    // 列表项 UI（自定义，非公用 layout）
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAlertItem(record: AlertRecord): View {
        val ctx = this
        val accent = ThemeColors.accent(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val cardBg = ThemeColors.cardBg(ctx)

        // ── 外层容器（卡片样式）──
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
            }
            background = bg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }

        // ── 左侧未读色条 ──
        if (!record.isRead) {
            val bar = View(ctx).apply {
                val barBg = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(accent)
                }
                background = barBg
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(36))
            }
            card.addView(bar)
            card.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), 0)
            })
        }

        // ── 图标 ──
        val iconView = ImageView(ctx).apply {
            setImageResource(typeToIconRes(record.type))
            val iconColor = if (!record.isRead) accent else textSecondary
            setColorFilter(iconColor)
            alpha = if (record.isRead) 0.5f else 1f
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        card.addView(iconView)

        // ── 文本区 ──
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(12)
            layoutParams = lp
        }

        // 标题
        textArea.addView(TextView(ctx).apply {
            text = record.title
            textSize = 14f
            setTextColor(textPrimary)
            if (!record.isRead) {
                setTypeface(null, Typeface.BOLD)
            }
        })

        // 消息内容
        textArea.addView(TextView(ctx).apply {
            text = record.message
            textSize = 12f
            setTextColor(if (!record.isRead) textPrimary else textSecondary)
            maxLines = 2
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(3)
            layoutParams = lp
        })

        // 时间戳
        textArea.addView(TextView(ctx).apply {
            text = shortTimeFormat.format(Date(record.timestamp))
            textSize = 11f
            setTextColor(textSecondary)
            alpha = 0.6f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        })

        card.addView(textArea)

        // ── 点击：弹出详情弹窗 ──
        card.setOnClickListener { showAlertActionDialog(record) }

        // ── 滑动：右滑已读，左滑删除 ──
        attachSwipeHandler(card, record)

        return card
    }

    // ═══════════════════════════════════════════
    // 滑动处理
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeHandler(card: View, record: AlertRecord) {
        val threshold = dp(SWIPE_THRESHOLD).toFloat()
        var startX = 0f
        var startY = 0f
        var isSwiping = false
        var lastDx = 0f

        card.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwiping = false
                    lastDx = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY

                    if (!isSwiping && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        isSwiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isSwiping) {
                        // 弹性阻尼：滑越远阻力越大
                        val damped = dx * 0.6f
                        v.translationX = damped
                        lastDx = dx

                        // 滑动时背景变色提示
                        val bg = v.background as? GradientDrawable
                        if (bg != null) {
                            val cardBg = ThemeColors.cardBg(this)
                            if (dx > 0) {
                                // 右滑：绿色（已读）
                                val alpha = (Math.abs(dx) / threshold * 40).toInt().coerceIn(0, 40)
                                bg.setColor(blendColor(cardBg, Color.parseColor("#4CAF50"), alpha / 100f))
                            } else {
                                // 左滑：红色（删除）
                                val alpha = (Math.abs(dx) / threshold * 40).toInt().coerceIn(0, 40)
                                bg.setColor(blendColor(cardBg, Color.parseColor("#F44336"), alpha / 100f))
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        val absDx = Math.abs(lastDx)

                        if (absDx >= threshold) {
                            // 超过阈值：执行操作
                            val slideOut = if (lastDx > 0) v.width.toFloat() else -v.width.toFloat()
                            v.animate()
                                .translationX(slideOut)
                                .alpha(0f)
                                .setDuration(200)
                                .setListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        if (lastDx > 0) {
                                            AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                                        } else {
                                            AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                                        }
                                        refreshList()
                                    }
                                })
                                .start()
                        } else {
                            // 未超过阈值：弹回原位
                            val bg = v.background as? GradientDrawable
                            val cardBg = ThemeColors.cardBg(this)
                            bg?.setColor(cardBg)
                            v.animate()
                                .translationX(0f)
                                .setDuration(250)
                                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                                .setListener(null)
                                .start()
                        }
                    } else {
                        // 非滑动，不消费（让 onClick 处理）
                        return@setOnTouchListener false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun blendColor(base: Int, overlay: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()
        val a = Color.alpha(base)
        return Color.argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    // ═══════════════════════════════════════════
    // 弹窗
    // ═══════════════════════════════════════════

    private fun showAlertActionDialog(record: AlertRecord) {
        val ctx = this
        val timeStr = fullTimeFormat.format(Date(record.timestamp))

        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = record.title,
            iconRes = typeToIconRes(record.type),
            onFill = { content ->
                content.addView(TextView(ctx).apply {
                    text = record.message
                    textSize = 13f
                    setTextColor(ThemeColors.textPrimary(ctx))
                    setLineSpacing(0f, 1.3f)
                })
                content.addView(TextView(ctx).apply {
                    text = "触发时间: $timeStr"
                    textSize = 12f
                    setTextColor(ThemeColors.textSecondary(ctx))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(8)
                    layoutParams = lp
                })
                if (!record.isRead) {
                    content.addView(CommonSettingsItemHelper.createDivider(ctx).apply {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp(6)
                        lp.bottomMargin = dp(6)
                        layoutParams = lp
                    })
                    content.addView(TextView(ctx).apply {
                        text = "● 未读 — 右滑卡片可快速标记已读"
                        textSize = 12f
                        setTextColor(ThemeColors.accent(ctx))
                    })
                } else {
                    content.addView(TextView(ctx).apply {
                        text = "左滑卡片可快速删除"
                        textSize = 12f
                        setTextColor(ThemeColors.textSecondary(ctx))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp(6)
                        layoutParams = lp
                    })
                }
            },
            primaryBtnText = if (record.isRead) "关闭" else "标记已读",
            onPrimaryClick = { dialog ->
                if (!record.isRead) {
                    AlertHistoryManager.markRead(ctx, record.id)
                    refreshList()
                }
                dialog.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { dialog ->
                AlertHistoryManager.remove(ctx, record.id)
                dialog.dismiss()
                refreshList()
            }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = "清空警报历史",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(TextView(ctx).apply {
                    text = "确定要清空所有警报记录吗？此操作不可撤销。"
                    textSize = 14f
                    setTextColor(ThemeColors.textSecondary(ctx))
                })
            },
            primaryBtnText = "清空",
            onPrimaryClick = { dialog ->
                AlertHistoryManager.clearAll(ctx)
                dialog.dismiss()
                refreshList()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { dialog -> dialog.dismiss() }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private fun typeToIconRes(type: String): Int = when (type) {
        "daily_flow" -> R.drawable.ic_rocket
        "monthly_flow" -> R.drawable.ic_rocket
        "temp" -> R.drawable.ic_temp
        "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip
        "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router
        else -> R.drawable.ic_notification
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
