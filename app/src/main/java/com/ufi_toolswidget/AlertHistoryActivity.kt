package com.ufi_toolswidget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var tvSubtitle: TextView

    private lateinit var viewModel: AlertHistoryViewModel
    private lateinit var adapter: AlertItemAdapter

    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // ── 筛选选项 ──

    private val typeFilters = listOf(
        FilterOption("all", "全部"),
        FilterOption("daily_flow", "日用量"),
        FilterOption("monthly_flow", "月用量"),
        FilterOption("temp", "温度"),
        FilterOption("cpu", "CPU"),
        FilterOption("memory", "内存"),
        FilterOption("battery", "电池"),
        FilterOption("device_online", "设备")
    )

    private val readFilters = listOf(
        FilterOption("all", "全部"),
        FilterOption("unread", "未读"),
        FilterOption("read", "已读")
    )

    // ── 速度追踪 ──
    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    // ── 广播 ──
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try { adapter.refresh() } catch (e: Exception) {
                DebugLogger.e(TAG, "Adapter refresh failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_alert_history)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        viewModel = ViewModelProvider(this)[AlertHistoryViewModel::class.java]

        alertList = findViewById(R.id.alert_list)
        emptyState = findViewById(R.id.empty_state)
        actionBar = findViewById(R.id.action_bar)
        btnFilterToggle = findViewById(R.id.btn_filter_toggle)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        registerRefreshReceiver()
        setupActionBar()
        setupFilterToggle()
        setupRecyclerView()
        observeData()
    }

    override fun onDestroy() {
        super.onDestroy()
        velocityTracker?.recycle()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun registerRefreshReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver,
                IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver,
                IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED))
        }
    }

    // ═══════════════════════════════════════════
    // 操作按钮 — 自动对比度计算确保文字可读
    // ═══════════════════════════════════════════

    private fun setupActionBar() {
        val accent = ThemeColors.accent(this)
        val accentText = contrastingTextColor(accent)
        val dangerColor = Color.parseColor("#F44336")

        val btnMarkAllRead = findViewById<MaterialButton>(R.id.btn_mark_all_read)
        btnMarkAllRead.backgroundTintList = ColorStateList.valueOf(accent)
        btnMarkAllRead.strokeWidth = 0
        btnMarkAllRead.strokeColor = ColorStateList.valueOf(accent)
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) {
            AlertHistoryManager.markAllRead(this)
        }
        btnMarkAllRead.setTextColor(accentText)
        btnMarkAllRead.iconTint = ColorStateList.valueOf(accentText)

        val btnClearAll = findViewById<MaterialButton>(R.id.btn_clear_all)
        btnClearAll.backgroundTintList = ColorStateList.valueOf(dangerColor)
        btnClearAll.strokeWidth = 0
        btnClearAll.strokeColor = ColorStateList.valueOf(dangerColor)
        AnimationUtil.applyScaleClickAnimation(btnClearAll) {
            showClearConfirmDialog()
        }
        btnClearAll.setTextColor(Color.WHITE)
        btnClearAll.iconTint = ColorStateList.valueOf(Color.WHITE)
    }

    /** 根据背景亮度返回黑/白文字色，确保对比度可读 */
    private fun contrastingTextColor(bg: Int): Int {
        val luminance = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }

    // ═══════════════════════════════════════════
    // 筛选弹窗
    // ═══════════════════════════════════════════

    private fun setupFilterToggle() {
        val textSecondary = ThemeColors.textSecondary(this)
        btnFilterToggle.setTextColor(textSecondary)
        btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)
        updateFilterToggleLabel()
        btnFilterToggle.setOnClickListener { showFilterDialog() }
    }

    private fun updateFilterToggleLabel() {
        val f = viewModel.filter.value
        val activeCount = (if (f.type != "all") 1 else 0) + (if (f.readStatus != "all") 1 else 0)
        val accent = ThemeColors.accent(this)
        val textSecondary = ThemeColors.textSecondary(this)
        if (activeCount > 0) {
            btnFilterToggle.text = "筛选($activeCount)"
            btnFilterToggle.setTextColor(accent)
            btnFilterToggle.iconTint = ColorStateList.valueOf(accent)
        } else {
            btnFilterToggle.text = "筛选"
            btnFilterToggle.setTextColor(textSecondary)
            btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)
        }
    }

    private fun showFilterDialog() {
        val ctx = this
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        var currentType = viewModel.filter.value.type
        var currentRead = viewModel.filter.value.readStatus

        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = "筛选警报",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                // 类型标签
                content.addView(sectionLabel("类型"))
                val typeContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = fillWidthParams().apply { bottomMargin = dp(10) }
                }
                val typeButtons = mutableListOf<MaterialButton>()
                for (opt in typeFilters) {
                    val btn = dialogChip(opt.label, opt.id == currentType, accent, textPrimary, cardBg)
                    btn.setOnClickListener {
                        currentType = opt.id
                        typeButtons.forEachIndexed { i, b ->
                            styleDialogChip(b, typeFilters[i].id == currentType, accent, textPrimary, cardBg)
                        }
                    }
                    typeButtons.add(btn)
                    typeContainer.addView(btn)
                }
                content.addView(typeContainer)

                // 状态标签
                content.addView(sectionLabel("状态"))
                val readContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = fillWidthParams()
                }
                val readBtns = mutableListOf<MaterialButton>()
                for (opt in readFilters) {
                    val btn = dialogChip(opt.label, opt.id == currentRead, accent, textPrimary, cardBg)
                    btn.setOnClickListener {
                        currentRead = opt.id
                        readBtns.forEachIndexed { i, b ->
                            styleDialogChip(b, readFilters[i].id == currentRead, accent, textPrimary, cardBg)
                        }
                    }
                    readBtns.add(btn)
                    readContainer.addView(btn)
                }
                content.addView(readContainer)
            },
            primaryBtnText = "应用",
            onPrimaryClick = { dialog ->
                viewModel.filter.value = AlertFilter(type = currentType, readStatus = currentRead)
                adapter.refresh()
                updateFilterToggleLabel()
                dialog.dismiss()
            },
            secondaryBtnText = "清除筛选",
            onSecondaryClick = { dialog ->
                viewModel.filter.value = AlertFilter()
                adapter.refresh()
                updateFilterToggleLabel()
                dialog.dismiss()
            }
        )
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(ThemeColors.textSecondary(this@AlertHistoryActivity))
        alpha = 0.6f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(4)
        layoutParams = lp
    }

    @SuppressLint("PrivateResource")
    private fun dialogChip(text: String, selected: Boolean, accent: Int, textPrimary: Int, cardBg: Int): MaterialButton {
        return MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            styleDialogChip(this, selected, accent, textPrimary, cardBg)
            this.text = text
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.marginEnd = dp(4)
            layoutParams = lp
        }
    }

    private fun styleDialogChip(btn: MaterialButton, selected: Boolean, accent: Int, textPrimary: Int, cardBg: Int) {
        btn.backgroundTintList = ColorStateList.valueOf(if (selected) accent else cardBg)
        btn.setTextColor(if (selected) contrastingTextColor(accent) else textPrimary)
        btn.strokeWidth = 0
        btn.cornerRadius = dp(8)
    }

    private fun fillWidthParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    // ═══════════════════════════════════════════
    // RecyclerView + ItemTouchHelper
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        adapter = AlertItemAdapter { record, _ -> showAlertActionDialog(record) }
        alertList.layoutManager = LinearLayoutManager(this)
        alertList.adapter = adapter
        alertList.setHasFixedSize(false)
        alertList.isNestedScrollingEnabled = true

        alertList.setOnTouchListener { _, event ->
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.let { vt ->
                    vt.computeCurrentVelocity(1000)
                    lastSwipeVelocityDpPerSec = Math.abs(vt.xVelocity) / resources.displayMetrics.density
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            false
        }

        ItemTouchHelper(SwipeCallback()).attachToRecyclerView(alertList)
    }

    private inner class SwipeCallback : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        private val labelPaint = Paint().apply {
            color = Color.WHITE; textSize = dpF(14f)
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        private var peakTranslationX = 0f

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = Float.MAX_VALUE
        override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val itemView = vh.itemView
            if (dX == 0f && !isCurrentlyActive) {
                peakTranslationX = 0f
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                return
            }
            if (Math.abs(dX) > Math.abs(peakTranslationX)) peakTranslationX = dX

            val bgPaint = Paint().apply { isAntiAlias = true }
            val cr = dpF(12f)
            val absDx = Math.abs(dX)

            if (dX > 0) {
                bgPaint.color = Color.argb((absDx / itemView.width * 180).toInt().coerceIn(0, 180), 76, 175, 80)
                c.drawRoundRect(RectF(itemView.left.toFloat(), itemView.top.toFloat(),
                    itemView.left + dX, itemView.bottom.toFloat()), cr, cr, bgPaint)
                if (absDx > dpF(30f)) {
                    val t = "已读  ✓"; val tw = labelPaint.measureText(t)
                    c.drawText(t, itemView.left + (dX - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f, labelPaint)
                }
            } else {
                bgPaint.color = Color.argb((absDx / itemView.width * 180).toInt().coerceIn(0, 180), 244, 67, 54)
                c.drawRoundRect(RectF(itemView.right + dX, itemView.top.toFloat(),
                    itemView.right.toFloat(), itemView.bottom.toFloat()), cr, cr, bgPaint)
                if (absDx > dpF(30f)) {
                    val t = "✕  删除"; val tw = labelPaint.measureText(t)
                    c.drawText(t, itemView.right + dX + (absDx - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f, labelPaint)
                }
            }

            val scale = 1f - (absDx / itemView.width * 0.03f).coerceAtMost(0.03f)
            itemView.scaleX = scale; itemView.scaleY = scale
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            val savedPeakX = peakTranslationX
            val itemWidth = vh.itemView.width.toFloat()
            val pos = vh.bindingAdapterPosition
            val record = adapter.peek(pos)

            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
            peakTranslationX = 0f

            if (record == null || pos == RecyclerView.NO_POSITION) return
            val absDx = Math.abs(savedPeakX)
            if (absDx < dpF(10f)) return

            if (shouldExecuteSwipe(absDx, itemWidth)) {
                if (savedPeakX > 0) {
                    // 右滑 → 标记已读 + 立即更新卡片视觉
                    applyReadVisuals(vh)
                    AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                } else if (savedPeakX < 0) {
                    // 左滑 → 删除
                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                }
            }
        }

        /** 右滑已读后立即更新卡片视觉状态（不等数据刷新） */
        private fun applyReadVisuals(vh: RecyclerView.ViewHolder) {
            val card = vh.itemView as? com.google.android.material.card.MaterialCardView ?: return
            val content = card.getChildAt(0) as? LinearLayout ?: return
            content.findViewWithTag<View>("bar")?.visibility = View.GONE
            content.findViewWithTag<View>("barGap")?.visibility = View.GONE
            content.findViewWithTag<ImageView>("icon")?.apply { alpha = 0.5f }
            content.findViewWithTag<TextView>("title")?.setTypeface(null, Typeface.NORMAL)
        }

        private fun shouldExecuteSwipe(absDx: Float, itemWidth: Float): Boolean {
            val r = absDx / itemWidth; val v = lastSwipeVelocityDpPerSec
            return r > 0.4f || (v > 800f && r > 0.15f) || (r > 0.25f && v > 500f)
        }
    }

    // ═══════════════════════════════════════════
    // 数据观察
    // ═══════════════════════════════════════════

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.alerts.collect { adapter.submitData(it) } }
                launch {
                    adapter.loadStateFlow.collect { ls ->
                        if (ls.refresh is LoadState.NotLoading) {
                            val isEmpty = adapter.itemCount == 0
                            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                            alertList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            if (isEmpty) {
                                actionBar.visibility = View.GONE
                                btnFilterToggle.visibility = View.GONE
                            } else {
                                actionBar.visibility = View.VISIBLE
                                btnFilterToggle.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                launch {
                    viewModel.subtitleInfo.collect { (total, unread) ->
                        tvSubtitle.text = if (unread > 0) "共 ${total} 条，${unread} 条未读" else "共 ${total} 条"
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 弹窗
    // ═══════════════════════════════════════════

    private fun showAlertActionDialog(record: AlertRecord) {
        val ctx = this
        val timeStr = fullTimeFormat.format(Date(record.timestamp))
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = record.title, iconRes = typeToIconRes(record.type),
            onFill = { content ->
                content.addView(TextView(ctx).apply {
                    text = record.message; textSize = 13f
                    setTextColor(ThemeColors.textPrimary(ctx)); setLineSpacing(0f, 1.3f)
                })
                content.addView(TextView(ctx).apply {
                    text = "触发时间: $timeStr"; textSize = 12f
                    setTextColor(ThemeColors.textSecondary(ctx))
                    layoutParams = fillWidthParams().apply { topMargin = dp(8) }
                })
                if (!record.isRead) {
                    content.addView(CommonSettingsItemHelper.createDivider(ctx).apply {
                        layoutParams = fillWidthParams().apply { topMargin = dp(6); bottomMargin = dp(6) }
                    })
                    content.addView(TextView(ctx).apply {
                        text = "● 未读 — 右滑卡片可快速标记已读"
                        textSize = 12f; setTextColor(ThemeColors.accent(ctx))
                    })
                } else {
                    content.addView(TextView(ctx).apply {
                        text = "左滑卡片可快速删除"; textSize = 12f
                        setTextColor(ThemeColors.textSecondary(ctx))
                        layoutParams = fillWidthParams().apply { topMargin = dp(6) }
                    })
                }
            },
            primaryBtnText = if (record.isRead) "关闭" else "标记已读",
            onPrimaryClick = { d -> if (!record.isRead) AlertHistoryManager.markRead(ctx, record.id); d.dismiss() },
            secondaryBtnText = "删除",
            onSecondaryClick = { d -> AlertHistoryManager.remove(ctx, record.id); d.dismiss() }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "清空警报历史", iconRes = R.drawable.ic_trash,
            onFill = { c ->
                c.addView(TextView(ctx).apply {
                    text = "确定要清空所有警报记录吗？此操作不可撤销。"
                    textSize = 14f; setTextColor(ThemeColors.textSecondary(ctx))
                })
            },
            primaryBtnText = "清空",
            onPrimaryClick = { d -> AlertHistoryManager.clearAll(ctx); d.dismiss() },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private fun typeToIconRes(type: String) = when (type) {
        "daily_flow", "monthly_flow" -> R.drawable.ic_rocket
        "temp" -> R.drawable.ic_temp; "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip; "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router; else -> R.drawable.ic_notification
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density
}

data class FilterOption(val id: String, val label: String)
