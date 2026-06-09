package com.ufi_toolswidget

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.ThemeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 警报列表 Adapter（传统分页，非 Paging3）。
 *
 * 使用 MaterialCardView 包裹，确保滑动时圆角无锯齿。
 */
class AlertItemAdapter(
    private val onItemClick: (AlertRecord, View) -> Unit
) : ListAdapter<AlertRecord, AlertItemAdapter.AlertViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AlertRecord>() {
            override fun areItemsTheSame(oldItem: AlertRecord, newItem: AlertRecord) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: AlertRecord, newItem: AlertRecord) =
                oldItem == newItem
        }
    }

    inner class AlertViewHolder(val card: com.google.android.material.card.MaterialCardView) :
        RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val ctx = parent.context
        val accent = ThemeColors.accent(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = ctx.resources.displayMetrics.density * 12f
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ThemeColors.cardBg(ctx))
            val lp = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (ctx.resources.displayMetrics.density * 8).toInt()
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (ctx.resources.displayMetrics.density * 14).toInt()
            val vPad = (ctx.resources.displayMetrics.density * 12).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }

        val barSpace = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 36))
            visibility = View.GONE
            tag = "bar"
        }
        content.addView(barSpace)

        val barGap = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), 0)
            visibility = View.GONE
            tag = "barGap"
        }
        content.addView(barGap)

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22))
            tag = "icon"
        }
        content.addView(icon)

        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(ctx, 12)
            layoutParams = lp
        }

        textArea.addView(TextView(ctx).apply {
            textSize = 14f
            setTextColor(textPrimary)
            tag = "title"
        })

        textArea.addView(TextView(ctx).apply {
            textSize = 12f
            maxLines = 2
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 3)
            layoutParams = lp
            tag = "message"
        })

        textArea.addView(TextView(ctx).apply {
            textSize = 11f
            setTextColor(textSecondary)
            alpha = 0.6f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4)
            layoutParams = lp
            tag = "time"
        })

        content.addView(textArea)
        card.addView(content)

        return AlertViewHolder(card)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val record = getItem(position) ?: return
        val ctx = holder.itemView.context
        val accent = ThemeColors.accent(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)

        val content = holder.card.getChildAt(0) as LinearLayout

        val bar = content.findViewWithTag<View>("bar")
        val barGap = content.findViewWithTag<View>("barGap")
        if (!record.isRead) {
            bar.visibility = View.VISIBLE
            bar.background = GradientDrawable().apply {
                cornerRadius = ctx.resources.displayMetrics.density * 2f
                setColor(accent)
            }
            barGap.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
            barGap.visibility = View.GONE
        }

        val icon = content.findViewWithTag<ImageView>("icon")
        icon.setImageResource(typeToIconRes(record.type))
        val iconColor = if (!record.isRead) accent else textSecondary
        icon.setColorFilter(iconColor)
        icon.alpha = if (record.isRead) 0.5f else 1f

        val title = content.findViewWithTag<TextView>("title")
        title.text = record.title
        title.setTextColor(textPrimary)
        if (!record.isRead) {
            title.setTypeface(null, Typeface.BOLD)
        } else {
            title.setTypeface(null, Typeface.NORMAL)
        }

        val message = content.findViewWithTag<TextView>("message")
        message.text = record.message
        message.setTextColor(if (!record.isRead) textPrimary else textSecondary)

        val time = content.findViewWithTag<TextView>("time")
        time.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(record.timestamp))

        holder.card.setOnClickListener { onItemClick(record, holder.card) }
    }

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

    private fun dp(ctx: android.content.Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
