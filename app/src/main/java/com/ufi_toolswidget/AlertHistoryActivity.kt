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

/**
 * 警报历史页面。
 *
 * - RecyclerView + Paging3 + Flow 实时刷新
 * - ItemTouchHelper 智能滑动：getSwipeThreshold=MAX_VALUE 阻止 onSwiped，
 *   所有判定在 clearView 中完成，保证卡片自然回弹不卡住
 * - 独立操作按钮（全部已读 + 清空）+ 可折叠筛选面板
 * - ViewModel 管理筛选状态
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var btnFilterToggle: MaterialButton
    private lateinit var filterPanel: LinearLayout
    private lateinit var filterTypeGroup: LinearLayout
    private lateinit var filterReadGroup: LinearLayout
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

    private var selectedTypeIndex = 0
    private var selectedReadIndex = 0
    private val typeButtons = mutableListOf<MaterialButton>()
    private val readButtons = mutableListOf<MaterialButton>()
    private var isFilterExpanded = false

    // ── 速度追踪 ──
    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    // ── 广播 ──

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                adapter.refresh()
            } catch (e: Exception) {
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
        filterPanel = findViewById(R.id.filter_panel)
        filterTypeGroup = findViewById(R.id.filter_type_group)
        filterReadGroup = findViewById(R.id.filter_read_group)
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        registerRefreshReceiver()
        setupActionBar()
        setupFilterToggle()
        buildFilterPanel()
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
    // 操作按钮（全部已读 + 清空记录 — 独立按钮，参考公共弹窗按钮风格）
    // ═══════════════════════════════════════════

    private fun setupActionBar() {
        val accent = ThemeColors.accent(this)

        // 全部已读 — accent 填充
        val btnMarkAllRead = findViewById<MaterialButton>(R.id.btn_mark_all_read)
        btnMarkAllRead.backgroundTintList = ColorStateList.valueOf(accent)
        btnMarkAllRead.strokeWidth = 0
        btnMarkAllRead.strokeColor = ColorStateList.valueOf(accent)
        btnMarkAllRead.setTextColor(Color.WHITE)
        btnMarkAllRead.iconTint = ColorStateList.valueOf(Color.WHITE)
        AnimationUtil.applyScaleClickAnimation(btnMarkAllRead) {
            AlertHistoryManager.markAllRead(this)
        }

        // 清空记录 — 红色填充 + 垃圾桶图标
        val dangerColor = Color.parseColor("#F44336")
        val btnClearAll = findViewById<MaterialButton>(R.id.btn_clear_all)
        btnClearAll.backgroundTintList = ColorStateList.valueOf(dangerColor)
        btnClearAll.strokeWidth = 0
        btnClearAll.strokeColor = ColorStateList.valueOf(dangerColor)
        btnClearAll.setTextColor(Color.WHITE)
        btnClearAll.iconTint = ColorStateList.valueOf(Color.WHITE)
        AnimationUtil.applyScaleClickAnimation(btnClearAll) {
            showClearConfirmDialog()
        }
    }

    // ═══════════════════════════════════════════
    // 筛选面板（可折叠，默认收起）
    // ═══════════════════════════════════════════

    private fun setupFilterToggle() {
        val accent = ThemeColors.accent(this)
        val textSecondary = ThemeColors.textSecondary(this)

        btnFilterToggle.setTextColor(textSecondary)
        btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)

        btnFilterToggle.setOnClickListener {
            isFilterExpanded = !isFilterExpanded
            updateFilterToggleState()
        }
    }

    private fun updateFilterToggleState(animated: Boolean = true) {
        val accent = ThemeColors.accent(this)
        val textSecondary = ThemeColors.textSecondary(this)

        if (isFilterExpanded) {
            // 展开：先设置可见再动画进入
            filterPanel.visibility = View.VISIBLE
            if (animated) {
                filterPanel.alpha = 0f
                filterPanel.translationY = -dp(8).toFloat()
                filterPanel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            btnFilterToggle.text = "收起筛选"
            btnFilterToggle.setTextColor(accent)
            btnFilterToggle.iconTint = ColorStateList.valueOf(accent)
        } else {
            // 收起：动画退出后再隐藏
            if (animated) {
                filterPanel.animate()
                    .alpha(0f)
                    .translationY(-dp(8).toFloat())
                    .setDuration(150)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        filterPanel.visibility = View.GONE
                        filterPanel.alpha = 1f
                        filterPanel.translationY = 0f
                    }
                    .start()
            } else {
                filterPanel.visibility = View.GONE
            }
            btnFilterToggle.text = "筛选"
            btnFilterToggle.setTextColor(textSecondary)
            btnFilterToggle.iconTint = ColorStateList.valueOf(textSecondary)
        }
    }

    private fun buildFilterPanel() {
        buildFilterRow(
            filterTypeGroup, typeFilters,
            typeButtons,
            onSelect = { index ->
                selectedTypeIndex = index
                updateFilterButtonStyles()
                viewModel.filter.value = viewModel.filter.value.copy(type = typeFilters[index].id)
                adapter.refresh()
            }
        )
        buildFilterRow(
            filterReadGroup, readFilters,
            readButtons,
            onSelect = { index ->
                selectedReadIndex = index
                updateFilterButtonStyles()
                viewModel.filter.value = viewModel.filter.value.copy(readStatus = readFilters[index].id)
                adapter.refresh()
            }
        )
        // 初始选中态
        updateFilterButtonStyles()
    }

    private fun buildFilterRow(
        container: LinearLayout,
        options: List<FilterOption>,
        buttonList: MutableList<MaterialButton>,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        buttonList.clear()
        for ((index, opt) in options.withIndex()) {
            val btn = createFilterChip(opt.label, false)
            btn.setOnClickListener {
                // 使用 buttonList.indexOf 实时获取当前索引，而非闭包捕获的值
                val currentIndex = buttonList.indexOf(btn)
                if (currentIndex != -1 && currentIndex != getSelectedIndex(buttonList)) {
                    onSelect(index)
                }
            }
            buttonList.add(btn)
            container.addView(btn)
        }
    }

    /** 获取当前选中的按钮索引（通过背景色判断） */
    private fun getSelectedIndex(buttonList: List<MaterialButton>): Int {
        val accent = ThemeColors.accent(this)
        return buttonList.indexOfFirst { btn ->
            val tint = btn.backgroundTintList?.defaultColor
            tint == accent
        }
    }

    /**
     * 创建筛选标签：选中时 accent 填充 + 白色文字（参考应用对话框选中项），
     * 未选时卡片底色 + 主文字色。
     */
    @SuppressLint("PrivateResource")
    private fun createFilterChip(text: String, selected: Boolean): MaterialButton {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        return MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            if (selected) {
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(Color.WHITE)
            } else {
                backgroundTintList = ColorStateList.valueOf(cardBg)
                setTextColor(textPrimary)
            }
            strokeWidth = 0
            cornerRadius = dp(8)
            rippleColor = ColorStateList.valueOf(Color.argb(32,
                Color.red(accent), Color.green(accent), Color.blue(accent)))
            this.text = text
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            setPadding(dp(14), 0, dp(14), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            )
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
    }

    private fun updateFilterButtonStyles() {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val cardBg = ThemeColors.cardBg(this)
        typeButtons.forEachIndexed { i, btn ->
            val sel = i == selectedTypeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (sel) accent else cardBg)
            btn.setTextColor(if (sel) Color.WHITE else textPrimary)
        }
        readButtons.forEachIndexed { i, btn ->
            val sel = i == selectedReadIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (sel) accent else cardBg)
            btn.setTextColor(if (sel) Color.WHITE else textPrimary)
        }
    }

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

        // 速度追踪（用于 clearView 中的多条件判定）
        alertList.setOnTouchListener { _, event ->
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
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

        val swipeHelper = ItemTouchHelper(SwipeCallback())
        swipeHelper.attachToRecyclerView(alertList)
    }

    /**
     * 滑动回调。
     *
     * 核心设计：
     * 1. getSwipeThreshold() 返回 Float.MAX_VALUE，使 ItemTouchHelper
     *    **永远不触发 onSwiped()**，卡片不会被当作"完成滑动"移出列表。
     * 2. getSwipeEscapeVelocity() 返回 Float.MAX_VALUE，阻止快速甩动绕过距离阈值。
     * 3. 手指松开后 ItemTouchHelper 自然将卡片回弹到原位，然后调用 clearView()。
     * 4. 在 clearView() 中保存回弹前的 translationX，根据距离+速度综合判定
     *    是否执行右滑已读 / 左滑删除。
     */
    private inner class SwipeCallback : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        private val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpF(14f)
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            // 永远不会被调用（getSwipeThreshold = Float.MAX_VALUE）。
        }

        /**
         * 关键：禁用距离和速度两个默认阈值，让所有滑动都回到 clearView 处理。
         */
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float =
            Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float): Float =
            Float.MAX_VALUE

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val itemView = vh.itemView
            if (dX == 0f) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                return
            }

            val bgPaint = Paint().apply { isAntiAlias = true }
            val cornerRadius = dpF(12f)
            val absDx = Math.abs(dX)

            if (dX > 0) {
                // 右滑 → 绿色已读
                val alpha = (absDx / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 76, 175, 80)
                c.drawRoundRect(
                    RectF(itemView.left.toFloat(), itemView.top.toFloat(),
                        itemView.left + dX, itemView.bottom.toFloat()),
                    cornerRadius, cornerRadius, bgPaint)

                if (absDx > dpF(30f)) {
                    val text = "已读  ✓"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.left + (dX - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            } else {
                // 左滑 → 红色删除
                val alpha = (absDx / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 244, 67, 54)
                c.drawRoundRect(
                    RectF(itemView.right + dX, itemView.top.toFloat(),
                        itemView.right.toFloat(), itemView.bottom.toFloat()),
                    cornerRadius, cornerRadius, bgPaint)

                if (absDx > dpF(30f)) {
                    val text = "✕  删除"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.right + dX + (absDx - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            }

            // 卡片微缩放
            val scale = 1f - (absDx / itemView.width * 0.03f).coerceAtMost(0.03f)
            itemView.scaleX = scale
            itemView.scaleY = scale

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        /**
         * 手指松开后由 ItemTouchHelper 调用。
         * 此时卡片已经（或正在）回弹到原位。
         * 在 super 重置 translationX 之前保存位移，判定操作。
         */
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            // ① 保存松手时的位移（super 会清零）
            val savedTranslationX = vh.itemView.translationX
            val itemWidth = vh.itemView.width.toFloat()
            val pos = vh.bindingAdapterPosition
            val record = adapter.peek(pos)

            // ② super 重置 itemView 属性
            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f

            // ③ 判定是否执行操作
            if (record == null || pos == RecyclerView.NO_POSITION) return

            val absDx = Math.abs(savedTranslationX)
            if (absDx < dpF(10f)) return // 小于 10dp 忽略

            if (shouldExecuteSwipe(absDx, itemWidth)) {
                if (savedTranslationX > 0) {
                    // 右滑 → 标记已读
                    AlertHistoryManager.markRead(this@AlertHistoryActivity, record.id)
                } else if (savedTranslationX < 0) {
                    // 左滑 → 删除
                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                }
            }
        }

        /**
         * 综合判定：距离 + 速度 + 幅度，防误触。
         *
         * 满足以下任一条件即执行：
         * 1. 滑动距离 > 40% 卡片宽度
         * 2. 滑动速度 > 800 dp/s 且距离 > 15% 卡片宽度
         * 3. 滑动距离 > 25% 且速度 > 500 dp/s
         */
        private fun shouldExecuteSwipe(absDx: Float, itemWidth: Float): Boolean {
            val distanceRatio = absDx / itemWidth
            val velocity = lastSwipeVelocityDpPerSec
            return when {
                distanceRatio > 0.4f -> true
                velocity > 800f && distanceRatio > 0.15f -> true
                distanceRatio > 0.25f && velocity > 500f -> true
                else -> false
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
                    viewModel.alerts.collect { pagingData ->
                        adapter.submitData(pagingData)
                    }
                }
                launch {
                    adapter.loadStateFlow.collect { loadStates ->
                        val refresh = loadStates.refresh
                        if (refresh is LoadState.NotLoading) {
                            val isEmpty = adapter.itemCount == 0
                            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                            alertList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            actionBar.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            btnFilterToggle.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.subtitleInfo.collect { (total, unread) ->
                        tvSubtitle.text = if (unread > 0) {
                            "共 ${total} 条，${unread} 条未读"
                        } else {
                            "共 ${total} 条"
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
                }
                dialog.dismiss()
            },
            secondaryBtnText = "删除",
            onSecondaryClick = { dialog ->
                AlertHistoryManager.remove(ctx, record.id)
                dialog.dismiss()
            }
        )
    }

    private fun showClearConfirmDialog() {
        val ctx = this
        CommonDialogHelper.showCommonDialog(
            context = ctx,
            title = "清空警报历史",
            iconRes = R.drawable.ic_trash,
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float =
        value * resources.displayMetrics.density
}

data class FilterOption(val id: String, val label: String)
