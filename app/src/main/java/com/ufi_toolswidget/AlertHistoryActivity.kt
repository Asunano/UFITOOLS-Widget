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
    private lateinit var contentLayout: View
    private lateinit var rootLayout: FrameLayout
    private lateinit var paginationBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var tvSubtitle: TextView

    /** 导航栏高度（px），由 insets listener 记录，用于内容底部 padding 和分页栏偏移 */
    private var navBarBottom = 0

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
        btnFilterToggle = findViewById(R.id.btn_filter_toggle)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)
        contentLayout = findViewById(R.id.content_layout)
        rootLayout = findViewById(R.id.root_layout)

        // 手动处理系统栏内边距
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val statusBar = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            navBarBottom = navBar.bottom
            // 状态栏高度应用到 header 顶部
            val contentLl = contentLayout as? LinearLayout
            val headerFrame = contentLl?.getChildAt(0) as? FrameLayout
            headerFrame?.setPadding(
                headerFrame.paddingLeft,
                statusBar.top,
                headerFrame.paddingRight,
                headerFrame.paddingBottom
            )
            // 导航栏高度应用到内容区域底部，阻止列表数据被遮挡
            if (navBarBottom > 0) {
                contentLayout.setPadding(
                    contentLayout.paddingLeft,
                    contentLayout.paddingTop,
                    contentLayout.paddingRight,
                    navBarBottom
                )
                // 分页栏贴导航栏上缘悬浮（不再额外留 16dp 空白）
                if (::paginationBar.isInitialized) {
                    val lp = paginationBar.layoutParams as? FrameLayout.LayoutParams
                    val d = resources.displayMetrics.density
                    val minMargin = (8 * d).toInt()
                    lp?.bottomMargin = if (navBarBottom > 0) navBarBottom else minMargin
                    paginationBar.layoutParams = lp
                }
            }
            insets
        }

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_settings)) { showSettingsDialog() }

        registerRefreshReceiver()
        setupActionBar()
        setupFilterToggle()
        setupPaginationBar()
        setupRecyclerView()
        observeData()
    }

    override fun onDestroy() {
        super.onDestroy()
        velocityTracker?.recycle()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun registerRefreshReceiver() {
        val filter = IntentFilter(AlertHistoryManager.ACTION_DATA_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
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
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) {
            lifecycleScope.launch {
                AlertHistoryManager.markAllRead(this@AlertHistoryActivity)
                viewModel.refresh()
            }
        }
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
    // 翻页栏（PaginationBarHelper 公共组件）
    // ═══════════════════════════════════════════

    private fun setupPaginationBar() {
        paginationBar = PaginationBarHelper.create(this) { action ->
            when (action) {
                PaginationBarHelper.Action.FIRST -> viewModel.firstPage()
                PaginationBarHelper.Action.PREV -> viewModel.prevPage()
                PaginationBarHelper.Action.NEXT -> viewModel.nextPage()
                PaginationBarHelper.Action.LAST -> viewModel.lastPage()
                is PaginationBarHelper.Action.Jump -> viewModel.goToPage(action.page)
            }
        }
        // 独立挂载到根 FrameLayout 底部，居中
        val d = resources.displayMetrics.density
        rootLayout.addView(paginationBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val minMargin = (8 * d).toInt()
            bottomMargin = if (navBarBottom > 0) navBarBottom else minMargin
            leftMargin = (16 * d).toInt()
            rightMargin = (16 * d).toInt()
        })
        paginationBar.visibility = View.GONE
        paginationBar.alpha = 0f
    }

    // ═══════════════════════════════════════════
    // 设置弹窗
    // ═══════════════════════════════════════════

    private fun showSettingsDialog() {
        val ctx = this
        var curPageSize = AlertHistoryManager.getPageSize(this)
        var curMaxCount = AlertHistoryManager.getMaxCount(this)

        val pageOptions = listOf(10, 20, 50, 100)
        val maxOptions = listOf(100, 500, 1000, 0)
        val maxLabels = mapOf(100 to "100", 500 to "500", 1000 to "1000", 0 to "不限")

        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "通知历史记录设置", iconRes = R.drawable.ic_settings,
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
                lifecycleScope.launch {
                    AlertHistoryManager.saveSettings(ctx, curPageSize, curMaxCount)
                    viewModel.pageSize.value = curPageSize
                    viewModel.currentPage.value = 1
                    viewModel.refresh()
                }
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
    // RecyclerView + 滑动动画（优化阈值同步）
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
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.let { vt ->
                        vt.computeCurrentVelocity(1000)
                        lastSwipeVelocityDpPerSec = Math.abs(vt.xVelocity) / resources.displayMetrics.density
                    }
                }
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.recycle(); velocityTracker = null
            }
            false
        }
        ItemTouchHelper(SwipeCallback()).attachToRecyclerView(alertList)
    }

    /**
     * 滑动回调 — 统一阈值体系：
     *
     * 触发条件（满足任一即可）：
     *   - ratio >= 0.35（慢拖，超过卡片宽度 35%）
     *   - ratio >= 0.20 且 velocity >= 600 dp/s（快扫）
     *
     * 视觉反馈（与阈值同步）：
     *   - 背景色透明度随 ratio 线性增长（0 → 180 alpha）
     *   - ratio >= 0.20 时显示文字提示（"已读 ✓" / "✕ 删除"）
     *   - 文字高亮与实际触发阈值同步：
     *       · 慢拖（velocity < 600 dp/s）→ ratio >= 0.35 时高亮
     *       · 快扫（velocity >= 600 dp/s）→ ratio >= 0.20 时即高亮
     *   - 卡片微缩放（最大 3%）增强触感
     */
    private inner class SwipeCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val labelPaint = Paint().apply {
            color = Color.WHITE; textSize = dpF(14f)
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        private var peakTranslationX = 0f

        // 阈值常量（dp 转 px 后比较）
        private val SLOW_RATIO = 0.35f
        private val FAST_RATIO = 0.20f
        private val FAST_VELOCITY = 600f // dp/s

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = Float.MAX_VALUE
        override fun getSwipeEscapeVelocity(d: Float) = Float.MAX_VALUE

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            val iv = vh.itemView
            if (dX == 0f && !isCurrentlyActive) {
                peakTranslationX = 0f
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                return
            }
            if (Math.abs(dX) > Math.abs(peakTranslationX)) peakTranslationX = dX

            val w = iv.width.toFloat()
            val abs = Math.abs(dX)
            val ratio = abs / w
            val cr = dpF(14f)

            // 回落动画期间使用峰值渲染，避免视觉反馈在触发前消退
            val renderAbs = if (!isCurrentlyActive && abs > 0f) Math.abs(peakTranslationX) else abs
            val renderRatio = renderAbs / w

            // 背景色 alpha（触发阈值附近更高饱和度）
            val bgAlpha = (renderRatio * 500f).toInt().coerceIn(0, 220)
            val bp = Paint().apply { isAntiAlias = true }

            if (dX > 0) {
                // 右滑 → 绿色（已读）
                bp.color = Color.argb(bgAlpha, 76, 175, 80)
                c.drawRoundRect(RectF(iv.left.toFloat(), iv.top.toFloat(), iv.left + dX, iv.bottom.toFloat()), cr, cr, bp)
            } else {
                // 左滑 → 红色（删除）
                bp.color = Color.argb(bgAlpha, 244, 67, 54)
                c.drawRoundRect(RectF(iv.right + dX, iv.top.toFloat(), iv.right.toFloat(), iv.bottom.toFloat()), cr, cr, bp)
            }

            // 文字提示（renderRatio >= 0.20 显示）
            if (renderRatio >= FAST_RATIO) {
                // 高亮与实际触发阈值同步：慢拖需达到 35%，快扫（velocity >= 600 dp/s）在 20% 即触发
                val velocity = lastSwipeVelocityDpPerSec
                val highlight = renderRatio >= SLOW_RATIO || (renderRatio >= FAST_RATIO && velocity >= FAST_VELOCITY)
                labelPaint.alpha = if (highlight) 255 else 160
                val text = if (dX > 0) "已读  ✓" else "✕  删除"
                val tw = labelPaint.measureText(text)
                val tx = if (dX > 0) iv.left + (dX - tw) / 2f else iv.right + dX + (abs - tw) / 2f
                val ty = iv.top + iv.height / 2f + labelPaint.textSize / 3f
                c.drawText(text, tx, ty, labelPaint)
            }

            // 卡片微缩放（最大 2.5%）
            val scale = 1f - (ratio * 0.025f).coerceAtMost(0.025f)
            iv.scaleX = scale; iv.scaleY = scale

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            val peak = peakTranslationX
            val w = vh.itemView.width.toFloat()
            val pos = vh.bindingAdapterPosition
            val rec = if (pos != RecyclerView.NO_POSITION) adapter.currentList.getOrNull(pos) else null
            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
            peakTranslationX = 0f
            if (rec == null || pos == RecyclerView.NO_POSITION) return
            val abs = Math.abs(peak); if (abs < dpF(10f)) return
            val ratio = abs / w
            val velocity = lastSwipeVelocityDpPerSec

            // 触发判定（与视觉反馈同步）
            val triggered = ratio >= SLOW_RATIO || (ratio >= FAST_RATIO && velocity >= FAST_VELOCITY)
            if (triggered) {
                if (peak > 0) {
                    val readId = rec.id
                    lifecycleScope.launch {
                        applyReadVisuals(vh)
                        AlertHistoryManager.markRead(this@AlertHistoryActivity, readId)
                        viewModel.refresh()
                    }
                } else if (peak < 0) {
                    val removeId = rec.id
                    lifecycleScope.launch {
                        AlertHistoryManager.remove(this@AlertHistoryActivity, removeId)
                        viewModel.refresh()
                    }
                }
            }
        }

        private fun applyReadVisuals(vh: RecyclerView.ViewHolder) {
            val card = vh.itemView as? com.google.android.material.card.MaterialCardView ?: return
            val content = card.getChildAt(0) as? LinearLayout ?: return
            content.findViewWithTag<ImageView>("icon")?.alpha = 0.45f
            content.findViewWithTag<TextView>("title")?.apply {
                setTypeface(null, Typeface.NORMAL)
                alpha = 0.75f
            }
            content.findViewWithTag<TextView>("message")?.alpha = 0.65f
            // 色条变灰
            content.findViewWithTag<View>("bar")?.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * vh.itemView.resources.displayMetrics.density
                setColor((ThemeColors.textSecondary(vh.itemView.context) and 0x00FFFFFF) or 0x30000000)
            }
        }
    }

    // ═══════════════════════════════════════════
    // 数据观察
    // ═══════════════════════════════════════════

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pageData.collect { result ->
                        adapter.submitList(result.data)
                        PaginationBarHelper.update(paginationBar, result.currentPage, result.totalPages)
                        val isEmpty = result.data.isEmpty()
                        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        alertList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        if (isEmpty && result.totalRecords > 0) {
                            tvEmptyText.text = "无匹配结果，请调整筛选条件"
                        } else {
                            tvEmptyText.text = "暂无警报记录"
                        }
                        if (result.data.isNotEmpty()) alertList.scrollToPosition(0)

                        // 翻页栏渐入渐出：多页时显示，单页/无数据时隐藏
                        val showPagination = result.totalPages > 1
                        PaginationBarHelper.fadeVisibility(paginationBar, showPagination)
                        updateContentMargin(showPagination)
                    }
                }
                launch {
                    viewModel.subtitleInfo.collect { (total, unread) ->
                        tvSubtitle.text = if (unread > 0) "共 ${total} 条，${unread} 条未读" else "共 ${total} 条"
                        if (total > 0) {
                            actionBar.visibility = View.VISIBLE
                            filterRow.visibility = View.VISIBLE
                        } else {
                            actionBar.visibility = View.GONE
                            filterRow.visibility = View.GONE
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
                val act = this@AlertHistoryActivity
                val id = record.id
                lifecycleScope.launch {
                    if (!record.isRead) AlertHistoryManager.markRead(act, id)
                    viewModel.refresh()
                }
                d.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { d ->
                val act = this@AlertHistoryActivity
                val id = record.id
                lifecycleScope.launch {
                    AlertHistoryManager.remove(act, id)
                    viewModel.refresh()
                }
                d.dismiss()
            }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx, title = "清空警报历史", iconRes = R.drawable.ic_trash,
            onFill = { c -> c.addView(TextView(ctx).apply { text = "确定要清空所有警报记录吗？此操作不可撤销。"; textSize = 14f; setTextColor(ThemeColors.textSecondary(ctx)) }) },
            primaryBtnText = "清空", onPrimaryClick = { d ->
                val act = this@AlertHistoryActivity
                lifecycleScope.launch {
                    AlertHistoryManager.clearAll(act)
                    viewModel.refresh()
                }
                d.dismiss()
            },
            secondaryBtnText = "取消", onSecondaryClick = { d -> d.dismiss() }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private fun typeToIconRes(t: String) = when (t) {
        "daily_flow", "monthly_flow" -> R.drawable.ic_clock_bolt
        "temp" -> R.drawable.ic_temp; "cpu" -> R.drawable.ic_cpu
        "memory" -> R.drawable.ic_chip; "battery" -> R.drawable.ic_battery_2
        "device_online" -> R.drawable.ic_router; else -> R.drawable.ic_notification
    }

    /** 动态调整内容区域底部边距，翻页栏悬浮于列表之上无需额外空间 */
    private fun updateContentMargin(paginationVisible: Boolean) {
        // 分页栏是悬浮在 FrameLayout 上的，不需要为它预留 margin
        val targetBottom = 0
        val lp = contentLayout.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin != targetBottom) {
            contentLayout.animate().cancel()
            android.animation.ValueAnimator.ofInt(lp.bottomMargin, targetBottom).apply {
                duration = 200
                addUpdateListener { anim ->
                    lp.bottomMargin = anim.animatedValue as Int
                    contentLayout.layoutParams = lp
                }
                start()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density
}

data class FilterOption(val id: String, val label: String)
