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

    companion object { private const val TAG = "AlertHistoryActivity" }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var tvSubtitle: TextView

    private lateinit var viewModel: AlertHistoryViewModel
    private lateinit var adapter: AlertItemAdapter

    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val typeFilters = listOf(
        FilterOption("all", "全部"), FilterOption("daily_flow", "日用量"),
        FilterOption("monthly_flow", "月用量"), FilterOption("temp", "温度"),
        FilterOption("cpu", "CPU"), FilterOption("memory", "内存"),
        FilterOption("battery", "电池"), FilterOption("device_online", "设备")
    )
    private val readFilters = listOf(
        FilterOption("all", "全部"), FilterOption("unread", "未读"), FilterOption("read", "已读")
    )

    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try { adapter.refresh() } catch (e: Exception) {
                DebugLogger.e(TAG, "Refresh failed: ${e.message}")
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
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_settings)) { showSettingsDialog() }

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
            registerReceiver(refreshReceiver, IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED))
        }
    }

    // ═══════════════════════════════════════════
    // 操作按钮 — 白色文字
    // ═══════════════════════════════════════════

    private fun setupActionBar() {
        val accent = ThemeColors.accent(this)
        val dangerColor = Color.parseColor("#F44336")

        val btnMarkAllRead = findViewById<MaterialButton>(R.id.btn_mark_all_read)
        btnMarkAllRead.backgroundTintList = ColorStateList.valueOf(accent)
        btnMarkAllRead.strokeWidth = 0; btnMarkAllRead.strokeColor = ColorStateList.valueOf(accent)
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) { AlertHistoryManager.markAllRead(this) }
        btnMarkAllRead.setTextColor(Color.WHITE); btnMarkAllRead.iconTint = ColorStateList.valueOf(Color.WHITE)

        val btnClearAll = findViewById<MaterialButton>(R.id.btn_clear_all)
        btnClearAll.backgroundTintList = ColorStateList.valueOf(dangerColor)
        btnClearAll.strokeWidth = 0; btnClearAll.strokeColor = ColorStateList.valueOf(dangerColor)
        AnimationUtil.applyScaleClickAnimation(btnClearAll) { showClearConfirmDialog() }
        btnClearAll.setTextColor(Color.WHITE); btnClearAll.iconTint = ColorStateList.valueOf(Color.WHITE)
    }

    // ═══════════════════════════════════════════
    // 筛选（弹窗）
    // ═══════════════════════════════════════════

    private fun setupFilterToggle() {
        val textSecondary = ThemeColors.textSecondary(this)
        btnFilterToggle.setTextColor(textSecondary)
        updateFilterToggleLabel()
        btnFilterToggle.setOnClickListener { showFilterDialog() }
    }

    private fun updateFilterToggleLabel() {
        val f = viewModel.filter.value
        val n = (if (f.type != "all") 1 else 0) + (if (f.readStatus != "all") 1 else 0)
        val accent = ThemeColors.accent(this)
        val textSec = ThemeColors.textSecondary(this)
        if (n > 0) {
            btnFilterToggle.text = "筛选($n)"
            btnFilterToggle.setTextColor(accent)
        } else {
            btnFilterToggle.text = "筛选"
            btnFilterToggle.setTextColor(textSec)
        }
    }

    private fun showFilterDialog() {
        val ctx = this
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        var curType = viewModel.filter.value.type
        var curRead = viewModel.filter.value.readStatus

        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "筛选警报", iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(sectionLabel("类型"))
                val typeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = fillWidth().apply { bottomMargin = dp(10) } }
                val tBtns = mutableListOf<MaterialButton>()
                for (opt in typeFilters) {
                    val b = chip(opt.label, opt.id == curType, accent, textPrimary, cardBg)
                    b.setOnClickListener { curType = opt.id; tBtns.forEachIndexed { i, btn -> styleChip(btn, typeFilters[i].id == curType, accent, textPrimary, cardBg) } }
                    tBtns.add(b); typeRow.addView(b)
                }
                content.addView(typeRow)

                content.addView(sectionLabel("状态"))
                val readRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = fillWidth() }
                val rBtns = mutableListOf<MaterialButton>()
                for (opt in readFilters) {
                    val b = chip(opt.label, opt.id == curRead, accent, textPrimary, cardBg)
                    b.setOnClickListener { curRead = opt.id; rBtns.forEachIndexed { i, btn -> styleChip(btn, readFilters[i].id == curRead, accent, textPrimary, cardBg) } }
                    rBtns.add(b); readRow.addView(b)
                }
                content.addView(readRow)
            },
            primaryBtnText = "应用",
            onPrimaryClick = { d ->
                viewModel.filter.value = AlertFilter(curType, curRead); adapter.refresh()
                updateFilterToggleLabel(); d.dismiss()
            },
            secondaryBtnText = "清除筛选",
            onSecondaryClick = { d ->
                viewModel.filter.value = AlertFilter(); adapter.refresh()
                updateFilterToggleLabel(); d.dismiss()
            }
        )
    }

    // ═══════════════════════════════════════════
    // 设置弹窗
    // ═══════════════════════════════════════════

    private fun showSettingsDialog() {
        val ctx = this
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        var curPageSize = AlertHistoryManager.getPageSize(this)
        var curMaxCount = AlertHistoryManager.getMaxCount(this)

        val pageOptions = listOf(10, 20, 50, 100)
        val maxOptions = listOf(100, 500, 1000, 0) // 0=无限制
        val maxLabels = listOf("100", "500", "1000", "不限")

        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "警报设置", iconRes = R.drawable.ic_settings,
            onFill = { content ->
                // 每页条数
                content.addView(sectionLabel("每页显示条数"))
                val pageRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = fillWidth().apply { bottomMargin = dp(12) } }
                val pBtns = mutableListOf<MaterialButton>()
                for (v in pageOptions) {
                    val b = chip("$v", v == curPageSize, accent, textPrimary, cardBg)
                    b.setOnClickListener { curPageSize = v; pBtns.forEachIndexed { i, btn -> styleChip(btn, pageOptions[i] == curPageSize, accent, textPrimary, cardBg) } }
                    pBtns.add(b); pageRow.addView(b)
                }
                content.addView(pageRow)

                // 最大保存数量
                content.addView(sectionLabel("最多保存通知数"))
                val maxRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = fillWidth() }
                val mBtns = mutableListOf<MaterialButton>()
                for ((idx, v) in maxOptions.withIndex()) {
                    val b = chip(maxLabels[idx], v == curMaxCount, accent, textPrimary, cardBg)
                    b.setOnClickListener { curMaxCount = v; mBtns.forEachIndexed { i, btn -> styleChip(btn, maxOptions[i] == curMaxCount, accent, textPrimary, cardBg) } }
                    mBtns.add(b); maxRow.addView(b)
                }
                content.addView(maxRow)
            },
            primaryBtnText = "保存",
            onPrimaryClick = { d ->
                AlertHistoryManager.saveSettings(ctx, curPageSize, curMaxCount)
                viewModel.pageSize.value = curPageSize
                adapter.refresh()
                d.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ── Dialog chip helpers ──

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(ThemeColors.textSecondary(this@AlertHistoryActivity)); alpha = 0.6f
        layoutParams = fillWidth().apply { bottomMargin = dp(4) }
    }

    @SuppressLint("PrivateResource")
    private fun chip(text: String, sel: Boolean, accent: Int, textPri: Int, cardBg: Int): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            styleChip(this, sel, accent, textPri, cardBg)
            this.text = text; textSize = 12f; insetTop = 0; insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) }
        }
    }

    private fun styleChip(b: MaterialButton, sel: Boolean, accent: Int, textPri: Int, cardBg: Int) {
        b.backgroundTintList = ColorStateList.valueOf(if (sel) accent else cardBg)
        b.setTextColor(if (sel) Color.WHITE else textPri)
        b.strokeWidth = 0; b.cornerRadius = dp(8)
    }

    private fun fillWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

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
                velocityTracker?.let { vt -> vt.computeCurrentVelocity(1000); lastSwipeVelocityDpPerSec = Math.abs(vt.xVelocity) / resources.displayMetrics.density }
                velocityTracker?.recycle(); velocityTracker = null
            }
            false
        }
        ItemTouchHelper(SwipeCallback()).attachToRecyclerView(alertList)
    }

    private inner class SwipeCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val labelPaint = Paint().apply { color = Color.WHITE; textSize = dpF(14f); typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        private var peakTranslationX = 0f

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = Float.MAX_VALUE
        override fun getSwipeEscapeVelocity(d: Float) = Float.MAX_VALUE

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            val iv = vh.itemView
            if (dX == 0f && !isCurrentlyActive) { peakTranslationX = 0f; super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive); return }
            if (Math.abs(dX) > Math.abs(peakTranslationX)) peakTranslationX = dX
            val bp = Paint().apply { isAntiAlias = true }; val cr = dpF(12f); val a = Math.abs(dX)
            if (dX > 0) {
                bp.color = Color.argb((a / iv.width * 180).toInt().coerceIn(0, 180), 76, 175, 80)
                c.drawRoundRect(RectF(iv.left.toFloat(), iv.top.toFloat(), iv.left + dX, iv.bottom.toFloat()), cr, cr, bp)
                if (a > dpF(30f)) { val t = "已读  ✓"; val tw = labelPaint.measureText(t); c.drawText(t, iv.left + (dX - tw) / 2f, iv.top + iv.height / 2f + labelPaint.textSize / 3f, labelPaint) }
            } else {
                bp.color = Color.argb((a / iv.width * 180).toInt().coerceIn(0, 180), 244, 67, 54)
                c.drawRoundRect(RectF(iv.right + dX, iv.top.toFloat(), iv.right.toFloat(), iv.bottom.toFloat()), cr, cr, bp)
                if (a > dpF(30f)) { val t = "✕  删除"; val tw = labelPaint.measureText(t); c.drawText(t, iv.right + dX + (a - tw) / 2f, iv.top + iv.height / 2f + labelPaint.textSize / 3f, labelPaint) }
            }
            val s = 1f - (a / iv.width * 0.03f).coerceAtMost(0.03f); iv.scaleX = s; iv.scaleY = s
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            val peak = peakTranslationX; val w = vh.itemView.width.toFloat()
            val pos = vh.bindingAdapterPosition; val rec = adapter.peek(pos)
            super.clearView(rv, vh); vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f; peakTranslationX = 0f
            if (rec == null || pos == RecyclerView.NO_POSITION) return
            val abs = Math.abs(peak); if (abs < dpF(10f)) return
            val r = abs / w; val v = lastSwipeVelocityDpPerSec
            if (r > 0.4f || (v > 800f && r > 0.15f) || (r > 0.25f && v > 500f)) {
                if (peak > 0) {
                    applyReadVisuals(vh); AlertHistoryManager.markRead(this@AlertHistoryActivity, rec.id)
                } else if (peak < 0) {
                    AlertHistoryManager.remove(this@AlertHistoryActivity, rec.id)
                }
            }
        }

        private fun applyReadVisuals(vh: RecyclerView.ViewHolder) {
            val card = vh.itemView as? com.google.android.material.card.MaterialCardView ?: return
            val content = card.getChildAt(0) as? LinearLayout ?: return
            content.findViewWithTag<View>("bar")?.visibility = View.GONE
            content.findViewWithTag<View>("barGap")?.visibility = View.GONE
            content.findViewWithTag<ImageView>("icon")?.alpha = 0.5f
            content.findViewWithTag<TextView>("title")?.setTypeface(null, Typeface.NORMAL)
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
                            val empty = adapter.itemCount == 0
                            emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                            alertList.visibility = if (empty) View.GONE else View.VISIBLE
                            if (empty) { actionBar.visibility = View.GONE; btnFilterToggle.visibility = View.GONE }
                            else { actionBar.visibility = View.VISIBLE; btnFilterToggle.visibility = View.VISIBLE }
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
        val ctx = this; val timeStr = fullTimeFormat.format(Date(record.timestamp))
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = record.title, iconRes = typeToIconRes(record.type),
            onFill = { c ->
                c.addView(TextView(ctx).apply { text = record.message; textSize = 13f; setTextColor(ThemeColors.textPrimary(ctx)); setLineSpacing(0f, 1.3f) })
                c.addView(TextView(ctx).apply { text = "触发时间: $timeStr"; textSize = 12f; setTextColor(ThemeColors.textSecondary(ctx)); layoutParams = fillWidth().apply { topMargin = dp(8) } })
                if (!record.isRead) {
                    c.addView(CommonSettingsItemHelper.createDivider(ctx).apply { layoutParams = fillWidth().apply { topMargin = dp(6); bottomMargin = dp(6) } })
                    c.addView(TextView(ctx).apply { text = "● 未读 — 右滑卡片可快速标记已读"; textSize = 12f; setTextColor(ThemeColors.accent(ctx)) })
                } else {
                    c.addView(TextView(ctx).apply { text = "左滑卡片可快速删除"; textSize = 12f; setTextColor(ThemeColors.textSecondary(ctx)); layoutParams = fillWidth().apply { topMargin = dp(6) } })
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
            onFill = { c -> c.addView(TextView(ctx).apply { text = "确定要清空所有警报记录吗？此操作不可撤销。"; textSize = 14f; setTextColor(ThemeColors.textSecondary(ctx)) }) },
            primaryBtnText = "清空", onPrimaryClick = { d -> AlertHistoryManager.clearAll(ctx); d.dismiss() },
            secondaryBtnText = "取消", onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private fun typeToIconRes(t: String) = when (t) {
        "daily_flow", "monthly_flow" -> R.drawable.ic_rocket
        "temp" -> R.drawable.ic_temp; "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip; "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router; else -> R.drawable.ic_notification
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density
}

data class FilterOption(val id: String, val label: String)
