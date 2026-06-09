package com.ufi_toolswidget

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * 使用 layout_common_setting_item 公用组件展示每条警报记录，
 * 支持单条已读/删除、全部已读、一键清空。
 */
class AlertHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertHistoryActivity"
    }

    private lateinit var alertList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var actionBar: View
    private lateinit var tvSubtitle: TextView

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val shortTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

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

        // 全部已读
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_mark_all_read)) {
            AlertHistoryManager.markAllRead(this)
            refreshList()
        }

        // 清空
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_clear_all)) {
            showClearConfirmDialog()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        refreshList()
    }

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

    // ── 构建单条警报：复用 layout_common_setting_item ──

    private fun buildAlertItem(record: AlertRecord): View {
        val ctx = this
        val itemView = layoutInflater.inflate(
            R.layout.layout_common_setting_item, alertList, false
        )

        val iconRes = typeToIconRes(record.type)
        val timeStr = shortTimeFormat.format(Date(record.timestamp))
        val subtitle = "${record.message}\n$timeStr"

        CommonSettingsItemHelper.setupSettingItem(
            itemView = itemView,
            iconRes = iconRes,
            title = record.title,
            subtitle = subtitle,
            onClick = { showAlertActionDialog(record) }
        )

        // 未读条目：标题加粗 + 图标着主题色
        val titleView = itemView.findViewById<TextView>(R.id.common_item_title)
        val iconView = itemView.findViewById<ImageView>(R.id.common_item_icon)
        val arrowView = itemView.findViewById<ImageView>(R.id.common_item_arrow)

        if (!record.isRead) {
            titleView?.setTypeface(null, android.graphics.Typeface.BOLD)
            iconView?.setColorFilter(ThemeColors.accent(ctx))
            // 箭头改为未读圆点指示
            arrowView?.let { arrow ->
                arrow.setImageResource(R.drawable.ic_check)
                arrow.alpha = 0.6f
                arrow.rotation = 0f
            }
        } else {
            iconView?.alpha = 0.5f
            arrowView?.visibility = View.GONE
        }

        return itemView
    }

    // ── 单条警报操作弹窗 ──

    private fun showAlertActionDialog(record: AlertRecord) {
        val ctx = this
        val timeStr = timeFormat.format(Date(record.timestamp))

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
                        lp.topMargin = dp(4)
                        lp.bottomMargin = dp(4)
                        layoutParams = lp
                    })
                    content.addView(TextView(ctx).apply {
                        text = "● 未读"
                        textSize = 12f
                        setTextColor(ThemeColors.accent(ctx))
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

    // ── 清空确认弹窗 ──

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

    // ── 辅助方法 ──

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
