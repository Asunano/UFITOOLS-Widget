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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
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
    private lateinit var tvEmptyText: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var filterRow: FrameLayout
    private lateinit var paginationBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var tvSubtitle: TextView
    private lateinit var tvPageInfo: TextView

    private lateinit var viewModel: AlertHistoryViewModel
    private lateinit var adapter: AlertItemAdapter

    private val fullTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val typeOptions = listOf(
        "all" to "全部", "daily_flow" to "日用量", "monthly_flow" to "月用量",
        "temp" to "温度", "cpu" to "CPU", "memory" to "内存",
        "battery" to "电池", "device_online" to "设备"
    )
    private val readOptions = listOf(
        "all" to "全部", "unread" to "未读", "read" to "已读"
    )

    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try { viewModel.refresh() } catch (e: Exception) {
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
        tvEmptyText = findViewById(R.id.tv_empty_text)
        actionBar = findViewById(R.id.action_bar)
        filterRow = findViewById(R.id.filter_row)
        paginationBar = findViewById(R.id.pagination_bar)
        btnFilterToggle = findViewById(R.id.btn_filter_toggle)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)
        tvPageInfo = findViewById(R.id.tv_page_info)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_settings)) { showSettingsDialog() }

        registerRefreshReceiver()
        setupActionBar()
        setupFilterToggle()
        setupPagination()
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
    // 操作按钮 — 白色文字（ThemeUtil 已跳过这些 ID）
    // ═══════════════════════════════════════════

    private fun setupActionBar() {
        val accent = ThemeColors.accent(this)
        val dangerColor = Color.parseColor("#F44336")

        val btnMarkAllRead = findViewById<MaterialButton>(R.id.btn_mark_all_read)
        btnMarkAllRead.backgroundTintList = ColorStateList.valueOf(accent)
        btnMarkAllRead.strokeWidth = 0; btnMarkAllRead.strokeColor = ColorStateList.valueOf(accent)
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) { AlertHistoryManager.markAllRead(this); viewModel.refresh() }
        btnMarkAllRead.setTextColor(Color.WHITE); btnMarkAllRead.iconTint = ColorStateList.valueOf(Color.WHITE)

        val btnClearAll = findViewById<MaterialButton>(R.id.btn_clear_all)
        btnClearAll.backgroundTintList = ColorStateList.valueOf(dangerColor)
        btnClearAll.strokeWidth = 0; btnClearAll.strokeColor = ColorStateList.valueOf(dangerColor)
        AnimationUtil.applyScaleClickAnimation(btnClearAll) { showClearConfirmDialog() }
        btnClearAll.setTextColor(Color.WHITE); btnClearAll.iconTint = ColorStateList.valueOf(Color.WHITE)
    }

    // ═══════════════════════════════════════════
    // 筛选按钮（独立行，右对齐）
    // ═══════════════════════════════════════════

    private fun setupFilterToggle() {
        val secondaryColor = ThemeColors.textSecondary(this)
        btnFilterToggle.backgroundTintList = ColorStateList.valueOf(secondaryColor)
        btnFilterToggle.strokeWidth = 0
        AnimationUtil.applyScaleClickAnimation(btnFilterToggle) { showFilterDialog() }
        btnFilterToggle.setTextColor(Color.WHITE)
        btnFilterToggle.iconTint = ColorStateList.valueOf(Color.WHITE)
        updateFilterToggleLabel()
    }

    private fun updateFilterToggleLabel() {
        val f = viewModel.filter.value
        val n = (if (f.type != "all") 1 else 0) + (if (f.readStatus != "all") 1 else 0)
        btnFilterToggle.text = if (n > 0) "筛选($n)" else "筛选"
    }

    private fun showFilterDialog() {
        val ctx = this
        var curType = viewModel.filter.value.type
        var curRead = viewModel.filter.value.readStatus

        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "筛选警报", iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(sectionLabel("类型"))
                var typeUpdate: ((String) -> Unit)? = null
                val (typeGrid, tUpdate) = CommonDialogHelper.createStringPresetGrid(
                    context = ctx, options = typeOptions, currentValue = curType,
                    onSelect = { id -> curType = id; typeUpdate?.invoke(id) }
                )
                typeUpdate = tUpdate
                content.addView(typeGrid)

                content.addView(sectionLabel("状态").apply {
                    layoutParams = fillWidth().apply { topMargin = dp(8) }
                })
                var readUpdate: ((String) -> Unit)? = null
                val (readRow, rUpdate) = CommonDialogHelper.createStringPresetRow(
                    context = ctx, options = readOptions, currentValue = curRead,
                    onSelect = { id -> curRead = id; readUpdate?.invoke(id) }
                )
                readUpdate = rUpdate
                content.addView(readRow)
            },
            primaryBtnText = "应用",
            onPrimaryClick = { d ->
                viewModel.filter.value = AlertFilter(curType, curRead)
                viewModel.currentPage.value = 1
                viewModel.refresh()
                updateFilterToggleLabel()
                d.dismiss()
            },
            secondaryBtnText = "清除筛选",
            onSecondaryClick = { d ->
                viewModel.filter.value = AlertFilter()
                viewModel.currentPage.value = 1
                viewModel.refresh()
                updateFilterToggleLabel()
                d.dismiss()
            }
        )
    }

    // ═══════════════════════════════════════════
    // 翻页栏
    // ═══════════════════════════════════════════

    private fun setupPagination() {
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_first)) { viewModel.firstPage() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_prev)) { viewModel.prevPage() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_next)) { viewModel.nextPage() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_last)) { viewModel.lastPage() }
    }

    private fun updatePaginationUI(result: PageResult) {
        tvPageInfo.text = "${result.currentPage} / ${result.totalPages}"
        findViewById<MaterialButton>(R.id.btn_first).isEnabled = result.currentPage > 1
        findViewById<MaterialButton>(R.id.btn_prev).isEnabled = result.currentPage > 1
        findViewById<MaterialButton>(R.id.btn_next).isEnabled = result.currentPage < result.totalPages
        findViewById<MaterialButton>(R.id.btn_last).isEnabled = result.currentPage < result.totalPages
    }

    // ═══════════════════════════════════════════
    // 设置弹窗（createPresetRow）
    // ═══════════════════════════════════════════

    private fun showSettingsDialog() {
        val ctx = this
        var curPageSize = AlertHistoryManager.getPageSize(this)
        var curMaxCount = AlertHistoryManager.getMaxCount(this)

        val pageOptions = listOf(10, 20, 50, 100)
        val maxOptions = listOf(100, 500, 1000, 0)
        val maxLabels = mapOf(100 to "100", 500 to "500", 1000 to "1000", 0 to "不限")

        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "警报设置", iconRes = R.drawable.ic_settings,
            onFill = { content ->
                content.addView(sectionLabel("每页显示条数"))
                var pageUpdate: ((Int) -> Unit)? = null
                val (pageRow, pUpdate) = CommonDialogHelper.createPresetRow(
                    context = ctx, values = pageOptions,
                    formatLabel = { "$it" }, currentValue = curPageSize,
                    onSelect = { v -> curPageSize = v; pageUpdate?.invoke(v) }
                )
                pageUpdate = pUpdate
                content.addView(pageRow)

                content.addView(sectionLabel("最多保存通知数").apply {
                    layoutParams = fillWidth().apply { topMargin = dp(12) }
                })
                var maxUpdate: ((Int) -> Unit)? = null
                val (maxRow, mUpdate) = CommonDialogHelper.createPresetRow(
                    context = ctx, values = maxOptions,
                    formatLabel = { maxLabels[it] ?: "$it" }, currentValue = curMaxCount,
                    onSelect = { v -> curMaxCount = v; maxUpdate?.invoke(v) }
                )
                maxUpdate = mUpdate
                content.addView(maxRow)
            },
            primaryBtnText = "保存",
            onPrimaryClick = { d ->
                AlertHistoryManager.saveSettings(ctx, curPageSize, curMaxCount)
                viewModel.pageSize.value = curPageSize
                viewModel.currentPage.value = 1
                viewModel.refresh()
                d.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ── 辅助 ──

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(ThemeColors.textSecondary(this@AlertHistoryActivity)); alpha = 0.6f
        layoutParams = fillWidth().apply { bottomMargin = dp(4) }
    }

    private fun fillWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    // ═══════════════════════════════════════════
    // RecyclerView + ItemTouchHelper
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewNotifications")
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
            val pos = vh.bindingAdapterPosition
            val rec = if (pos != RecyclerView.NO_POSITION) adapter.currentList.getOrNull(pos) else null
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
                viewModel.refresh()
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
                // 页数据
                launch {
                    viewModel.pageData.collect { result ->
                        adapter.submitList(result.data)
                        updatePaginationUI(result)
                        val isEmpty = result.data.isEmpty()
                        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        alertList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        if (isEmpty && result.totalRecords > 0) {
                            tvEmptyText.text = "无匹配结果，请调整筛选条件"
                        } else {
                            tvEmptyText.text = "暂无警报记录"
                        }
                        // 翻页时滚动到顶部
                        if (result.data.isNotEmpty()) alertList.scrollToPosition(0)
                    }
                }

                // 副标题 + 按钮可见性
                launch {
                    viewModel.subtitleInfo.collect { (total, unread) ->
                        tvSubtitle.text = if (unread > 0) "共 ${total} 条，${unread} 条未读" else "共 ${total} 条"
                        if (total > 0) {
                            actionBar.visibility = View.VISIBLE
                            filterRow.visibility = View.VISIBLE
                            paginationBar.visibility = View.VISIBLE
                        } else {
                            actionBar.visibility = View.GONE
                            filterRow.visibility = View.GONE
                            paginationBar.visibility = View.GONE
                        }
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
        val cleanMsg = record.message.replace(Regex("\\n?触发时间:\\s*\\S+"), "").trimEnd()
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = record.title, iconRes = typeToIconRes(record.type),
            onFill = { c ->
                c.addView(TextView(ctx).apply { text = cleanMsg; textSize = 14f; setTextColor(ThemeColors.textPrimary(ctx)); setLineSpacing(0f, 1.4f) })
                c.addView(TextView(ctx).apply { text = "触发时间: $timeStr"; textSize = 12f; setTextColor(ThemeColors.textSecondary(ctx)); layoutParams = fillWidth().apply { topMargin = dp(8) } })
                if (!record.isRead) {
                    c.addView(CommonSettingsItemHelper.createDivider(ctx).apply { layoutParams = fillWidth().apply { topMargin = dp(6); bottomMargin = dp(6) } })
                    c.addView(TextView(ctx).apply { text = "● 未读 — 右滑卡片可快速标记已读"; textSize = 12f; setTextColor(ThemeColors.accent(ctx)) })
                } else {
                    c.addView(TextView(ctx).apply { text = "左滑卡片可快速删除"; textSize = 12f; setTextColor(ThemeColors.textSecondary(ctx)); layoutParams = fillWidth().apply { topMargin = dp(6) } })
                }
            },
            primaryBtnText = if (record.isRead) "关闭" else "标记已读",
            onPrimaryClick = { d ->
                if (!record.isRead) AlertHistoryManager.markRead(ctx, record.id)
                viewModel.refresh(); d.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { d -> AlertHistoryManager.remove(ctx, record.id); viewModel.refresh(); d.dismiss() }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "清空警报历史", iconRes = R.drawable.ic_trash,
            onFill = { c -> c.addView(TextView(ctx).apply { text = "确定要清空所有警报记录吗？此操作不可撤销。"; textSize = 14f; setTextColor(ThemeColors.textSecondary(ctx)) }) },
            primaryBtnText = "清空", onPrimaryClick = { d -> AlertHistoryManager.clearAll(ctx); viewModel.refresh(); d.dismiss() },
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
