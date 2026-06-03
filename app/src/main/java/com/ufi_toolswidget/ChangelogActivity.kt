package com.ufi_toolswidget

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.UpdateChecker

class ChangelogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changelog)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.container_changelog)
        val progress = findViewById<ProgressBar>(R.id.progress_loading)
        val tvError = findViewById<TextView>(R.id.tv_error)
        val scrollView = findViewById<ScrollView>(R.id.scroll_view)

        // 从网络加载最新版本日志
        loadChangelogFromServer(container, progress, tvError, scrollView)
    }

    private fun loadChangelogFromServer(
        container: LinearLayout,
        progress: ProgressBar,
        tvError: TextView,
        scrollView: ScrollView
    ) {
        UpdateChecker.checkUpdate(this) { info, error ->
            progress.visibility = View.GONE

            when {
                error != null -> {
                    scrollView.visibility = View.GONE
                    tvError.visibility = View.VISIBLE
                    tvError.text = error
                }
                info != null -> {
                    showEntry(info)
                }
                else -> {
                    scrollView.visibility = View.GONE
                    tvError.visibility = View.VISIBLE
                    tvError.text = "暂无更新日志"
                }
            }
        }
    }

    /**
     * 渲染单条更新日志（最新版本）
     */
    private fun showEntry(info: UpdateChecker.UpdateInfo) {
        val container = findViewById<LinearLayout>(R.id.container_changelog)
        container.removeAllViews()
        container.addView(buildChangelogCard(info))
    }

    /**
     * 构建更新日志卡片
     */
    private fun buildChangelogCard(info: UpdateChecker.UpdateInfo): CardView {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            cardElevation = 0f
            radius = dp(16).toFloat()
            setCardBackgroundColor(
                ContextCompat.getColor(this@ChangelogActivity, android.R.color.transparent)
            )
            isClickable = false
        }

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // 版本标签行
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val versionBadge = TextView(this).apply {
            text = "v${info.versionName}"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ChangelogActivity, com.google.android.material.R.attr.colorAccent))
        }
        headerRow.addView(versionBadge)

        // 最新标签
        val latestBadge = TextView(this)
        latestBadge.text = " 最新"
        latestBadge.textSize = 10f
        latestBadge.setTextColor(ContextCompat.getColor(this@ChangelogActivity, android.R.color.white))
        latestBadge.setBackgroundColor(ContextCompat.getColor(this@ChangelogActivity, com.google.android.material.R.attr.colorAccent))
        latestBadge.setPadding(dp(6), dp(2), dp(6), dp(3))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.leftMargin = dp(8)
        lp.gravity = Gravity.CENTER_VERTICAL
        latestBadge.layoutParams = lp
        headerRow.addView(latestBadge)

        innerLayout.addView(headerRow)

        // 日期
        val date = UpdateChecker.formatDate(info.publishedAt)
        if (date.isNotBlank()) {
            val tvDate = TextView(this)
            tvDate.text = date
            tvDate.textSize = 12f
            tvDate.alpha = 0.5f
            tvDate.setTextColor(ContextCompat.getColor(this@ChangelogActivity, com.google.android.material.R.attr.colorOnSurface))
            val dateLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dateLp.topMargin = dp(8)
            tvDate.layoutParams = dateLp
            innerLayout.addView(tvDate)
        }

        // 更新日志内容
        val items = info.changelog.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (items.isNotEmpty()) {
            val tvContent = TextView(this)
            tvContent.text = items.joinToString("\n") { "• $it" }
            tvContent.textSize = 14f
            tvContent.alpha = 0.75f
            tvContent.setLineSpacing(dp(4).toFloat(), 1f)
            tvContent.setTextColor(ContextCompat.getColor(this@ChangelogActivity, com.google.android.material.R.attr.colorOnSurface))
            val contentLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            contentLp.topMargin = dp(10)
            tvContent.layoutParams = contentLp
            innerLayout.addView(tvContent)
        }

        card.addView(innerLayout)
        return card
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
