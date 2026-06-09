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
 * - ItemTouchHelper 智能滑动：综合距离+速度+幅度判定，右滑已读不滑出只回弹，左滑删除
 * - MaterialButton TextButton 筛选 + OutlinedButton 操作栏（与应用其他页面一致）
 * - ViewModel 管理筛选状态
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var actionBar: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var filterTypeGroup: LinearLayout
    private lateinit var filterReadGroup: LinearLayout

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

    // ── 当前筛选选中索引 ──
    private var selectedTypeIndex = 0
    private var selectedReadIndex = 0
    private val typeButtons = mutableListOf<MaterialButton>()
    private val readButtons = mutableListOf<MaterialButton>()

    // ── 速度追踪 ──
    private var velocityTracker: VelocityTracker? = null
    private var lastSwipeVelocityDpPerSec = 0f

    // ── 广播（数据变更同步） ──

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            adapter.refresh()
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
        tvSubtitle = findViewById(R.id.tv_alert_subtitle)
        filterTypeGroup = findViewById(R.id.filter_type_group)
        filterReadGroup = findViewById(R.id.filter_read_group)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        registerRefreshReceiver()
        buildFilterButtons()
        buildActionBar()
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
    // 筛选按钮（MaterialButton TextButton — 与 DebugLogActivity 一致）
    // ═══════════════════════════════════════════

    private fun buildFilterButtons() {
        buildTypeFilterButtons()
        buildReadFilterButtons()
    }

    private fun buildTypeFilterButtons() {
        filterTypeGroup.removeAllViews()
        typeButtons.clear()
        for ((index, opt) in typeFilters.withIndex()) {
            val btn = createFilterButton(opt.label, index == 0)
            btn.setOnClickListener {
                if (selectedTypeIndex != index) {
                    selectedTypeIndex = index
                    updateTypeButtonStyles()
                    viewModel.filter.value = viewModel.filter.value.copy(type = opt.id)
                    adapter.refresh()
                }
            }
            typeButtons.add(btn)
            filterTypeGroup.addView(btn)
        }
    }

    private fun buildReadFilterButtons() {
        filterReadGroup.removeAllViews()
        readButtons.clear()
        for ((index, opt) in readFilters.withIndex()) {
            val btn = createFilterButton(opt.label, index == 0)
            btn.setOnClickListener {
                if (selectedReadIndex != index) {
                    selectedReadIndex = index
                    updateReadButtonStyles()
                    viewModel.filter.value = viewModel.filter.value.copy(readStatus = opt.id)
                    adapter.refresh()
                }
            }
            readButtons.add(btn)
            filterReadGroup.addView(btn)
        }
    }

    private fun createFilterButton(text: String, selected: Boolean): MaterialButton {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        return MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            // 使用 TextButton 风格
            setTextColor(if (selected) accent else textPrimary)
            strokeColor = ColorStateList.valueOf(if (selected) accent else 0x00000000)
            strokeWidth = dp(1)
            cornerRadius = dp(8)
            this.text = text
            textSize = 11f
            insetTop = 0
            insetBottom = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            )
            lp.marginEnd = dp(4)
            layoutParams = lp
        }
    }

    private fun updateTypeButtonStyles() {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        typeButtons.forEachIndexed { i, btn ->
            if (i == selectedTypeIndex) {
                btn.setTextColor(accent)
                btn.strokeColor = ColorStateList.valueOf(accent)
            } else {
                btn.setTextColor(textPrimary)
                btn.strokeColor = ColorStateList.valueOf(0x00000000)
            }
        }
    }

    private fun updateReadButtonStyles() {
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        readButtons.forEachIndexed { i, btn ->
            if (i == selectedReadIndex) {
                btn.setTextColor(accent)
                btn.strokeColor = ColorStateList.valueOf(accent)
            } else {
                btn.setTextColor(textPrimary)
                btn.strokeColor = ColorStateList.valueOf(0x00000000)
            }
        }
    }

    // ═══════════════════════════════════════════
    // Action bar（OutlinedButton — 与 DebugLogActivity 一致）
    // ═══════════════════════════════════════════

    private fun buildActionBar() {
        actionBar.removeAllViews()
        val accent = ThemeColors.accent(this)
        val subColor = ThemeColors.textSecondary(this)

        actionBar.addView(createActionButton("全部已读", R.drawable.ic_check, accent) {
            AlertHistoryManager.markAllRead(this)
        })
        actionBar.addView(createActionButton("清空", null, subColor) {
            showClearConfirmDialog()
        })
    }

    private fun createActionButton(
        text: String, iconRes: Int?, color: Int, onClick: () -> Unit
    ): MaterialButton {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
        )
        lp.marginStart = dp(6)

        return MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = lp
            this.text = text
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(Color.argb(
                128, Color.red(color), Color.green(color), Color.blue(color)))
            setTextColor(color)
            if (iconRes != null) {
                icon = context.getDrawable(iconRes)
                iconTint = ColorStateList.valueOf(color)
                iconSize = dp(14)
                iconPadding = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    // ═══════════════════════════════════════════
    // RecyclerView + ItemTouchHelper（智能滑动）
    // ═══════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        adapter = AlertItemAdapter { record, _ -> showAlertActionDialog(record) }

        alertList.layoutManager = LinearLayoutManager(this)
        alertList.adapter = adapter
        alertList.setHasFixedSize(false)
        alertList.isNestedScrollingEnabled = true

        // 速度追踪：捕获 RecyclerView 的 touch 事件计算速度
        alertList.setOnTouchListener { _, event ->
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.let { vt ->
                    vt.computeCurrentVelocity(1000) // px/s
                    lastSwipeVelocityDpPerSec = Math.abs(vt.xVelocity) / resources.displayMetrics.density
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            false // 不拦截事件，让 ItemTouchHelper 继续处理
        }

        val swipeHelper = ItemTouchHelper(SwipeCallback())
        swipeHelper.attachToRecyclerView(alertList)
    }

    /**
     * 智能滑动回调。
     *
     * 右滑（标记已读）：
     *   - getSwipeThreshold 返回 Float.MAX_VALUE → onSwiped 永远不会被右滑触发
     *   - 在 onChildDraw 中绘制绿色背景 + "已读 ✓" 标签
     *   - 手指松开后 ItemTouchHelper 自动回弹卡片到原位
     *   - 综合判定（距离+速度+幅度）决定是否执行标记已读
     *
     * 左滑（删除）：
     *   - 正常阈值触发 onSwiped → 滑出 + 删除
     *   - 综合判定防误触
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

        /**
         * 右滑永远不触发 onSwiped（让卡片自动回弹），左滑使用默认阈值。
         * 实际的"是否执行操作"判定在 clearView 中通过综合分数完成。
         */
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
            return Float.MAX_VALUE
        }

        override fun getSwipeVelocityThreshold(defaultValue: Float): Float {
            return defaultValue * 0.3f // 降低速度阈值，让速度判定更灵敏
        }

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        /**
         * 仅左滑超过阈值时才会调用（右滑因 threshold=MAX 永远不会到达这里）。
         */
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            if (direction == ItemTouchHelper.LEFT) {
                val pos = vh.bindingAdapterPosition
                val record = adapter.peek(pos)
                if (record != null && shouldExecuteSwipe(vh.itemView.width.toFloat())) {
                    AlertHistoryManager.remove(this@AlertHistoryActivity, record.id)
                } else {
                    adapter.notifyItemChanged(vh.bindingAdapterPosition)
                }
            }
        }

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

            if (dX > 0) {
                // ── 右滑 → 绿色已读 ──
                val alpha = (dX / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 76, 175, 80) // #4CAF50 with alpha
                val rect = RectF(
                    itemView.left.toFloat(), itemView.top.toFloat(),
                    itemView.left + dX, itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

                if (dX > dpF(30f)) {
                    val text = "已读  ✓"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.left + (dX - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            } else {
                // ── 左滑 → 红色删除 ──
                val absDx = Math.abs(dX)
                val alpha = (absDx / itemView.width * 180).toInt().coerceIn(0, 180)
                bgPaint.color = Color.argb(alpha, 244, 67, 54) // #F44336 with alpha
                val rect = RectF(
                    itemView.right + dX, itemView.top.toFloat(),
                    itemView.right.toFloat(), itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

                if (absDx > dpF(30f)) {
                    val text = "✕  删除"
                    val tw = labelPaint.measureText(text)
                    c.drawText(text,
                        itemView.right + dX + (absDx - tw) / 2f,
                        itemView.top + itemView.height / 2f + labelPaint.textSize / 3f,
                        labelPaint)
                }
            }

            // 卡片微缩放（最大 3%，更微妙）
            val scale = 1f - (Math.abs(dX) / itemView.width * 0.03f).coerceAtMost(0.03f)
            itemView.scaleX = scale
            itemView.scaleY = scale

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
        }

        /**
         * 综合判定：距离 + 速度 + 幅度。
         *
         * 满足以下任一条件即执行：
         * 1. 滑动距离 > 40% 卡片宽度
         * 2. 滑动速度 > 800 dp/s 且距离 > 15% 卡片宽度
         * 3. 滑动距离 > 25% 且速度 > 500 dp/s
         */
        private fun shouldExecuteSwipe(itemWidth: Float): Boolean {
            val absDx = Math.abs(alertList.getChildAt(0)?.translationX ?: 0f)
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
                        }
                    }
                }
                launch {
                    viewModel.unreadCount.collect { count ->
                        val total = adapter.itemCount
                        tvSubtitle.text = if (count > 0) {
                            "共 ${total} 条，${count} 条未读"
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
