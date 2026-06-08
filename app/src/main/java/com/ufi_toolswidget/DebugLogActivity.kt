package com.ufi_toolswidget

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CrashHandler
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.DebugLogger.Category
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.ToastStyle
import com.ufi_toolswidget.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CRASH_MODE = "crash_mode"
    }

    private lateinit var tvLogContent: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var crashBanner: View
    private var isCrashMode = false

    /** 当前选中的诊断分类（null = 未选择） */
    private var currentCategory: Category? = null

    /** 分类按钮列表 */
    private val categoryButtons = mutableMapOf<Category, MaterialButton>()

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SECONDARY)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@DebugLogActivity, ThemeUtil.PageType.SECONDARY)
            refreshCurrentView()
            updateStatus()
        }
        setContentView(R.layout.activity_debug_log)

        isCrashMode = intent.getBooleanExtra(EXTRA_CRASH_MODE, false)

        tvLogContent = findViewById(R.id.tv_log_content)
        scrollLog = findViewById(R.id.scroll_log)
        tvStatus = findViewById(R.id.tv_log_status)
        switchEnabled = findViewById(R.id.switch_debug_enabled)
        crashBanner = findViewById(R.id.crash_banner)

        // 初始化分类按钮映射
        categoryButtons[Category.SYS] = findViewById(R.id.btn_cat_sys)
        categoryButtons[Category.API] = findViewById(R.id.btn_cat_api)
        categoryButtons[Category.UI] = findViewById(R.id.btn_cat_ui)
        categoryButtons[Category.EXCEPTION] = findViewById(R.id.btn_cat_exc)
        // btn_cat_full 不映射到单一 Category

        // 设置分类按钮点击
        categoryButtons.forEach { (cat, btn) ->
            btn.setOnClickListener {
                selectCategory(cat)
            }
        }
        findViewById<View>(R.id.btn_cat_full).setOnClickListener {
            selectFullReport()
        }

        // 崩溃模式：直接显示崩溃日志
        if (isCrashMode) {
            setupCrashBanner()
            showCrashContent()
        }

        // 返回按钮
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        // 调试开关
        switchEnabled.isChecked = DebugLogger.enabled
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            DebugLogger.enabled = isChecked
            refreshCurrentView()
            updateStatus()
            ToastUtil.showDropToast(this, ToastStyle.INFO, if (isChecked) "调试模式已开启" else "调试模式已关闭")
        }

        // 刷新按钮
        findViewById<View>(R.id.btn_refresh_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { refreshCurrentView() }
        }

        // 复制按钮
        findViewById<View>(R.id.btn_copy_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { copyCurrent() }
        }

        // UI 快照按钮
        findViewById<View>(R.id.btn_ui_snapshot).apply {
            AnimationUtil.applyScaleClickAnimation(this) { captureUiSnapshot() }
        }

        // 全量分享按钮
        findViewById<View>(R.id.btn_share_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { shareFullReport() }
        }

        // 清空按钮
        findViewById<View>(R.id.btn_clear_log).apply {
            AnimationUtil.applyScaleClickAnimation(this) { clearAll() }
        }

        updateStatus()
        // 默认显示系统信息
        selectCategory(Category.SYS)
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SECONDARY)
        refreshCurrentView()
        updateStatus()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 分类切换 ====================

    private fun selectCategory(cat: Category) {
        currentCategory = cat

        // 更新按钮高亮
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        categoryButtons.forEach { (c, btn) ->
            if (c == cat) {
                btn.setTextColor(accent)
                btn.strokeColor = ColorStateList.valueOf(accent)
            } else {
                btn.setTextColor(textPrimary)
                btn.strokeColor = ColorStateList.valueOf(0x00000000)
            }
        }
        // 取消全量按钮高亮
        findViewById<MaterialButton>(R.id.btn_cat_full).apply {
            setTextColor(textPrimary)
            strokeColor = ColorStateList.valueOf(0x00000000)
        }

        refreshCategoryView(cat)
    }

    private fun selectFullReport() {
        currentCategory = null
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        categoryButtons.forEach { (_, btn) ->
            btn.setTextColor(textPrimary)
            btn.strokeColor = ColorStateList.valueOf(0x00000000)
        }
        findViewById<MaterialButton>(R.id.btn_cat_full).apply {
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }
        showFullReport()
    }

    // ==================== 分类视图 ====================

    private fun refreshCurrentView() {
        if (currentCategory != null) {
            refreshCategoryView(currentCategory!!)
        } else {
            showFullReport()
        }
    }

    private fun refreshCategoryView(cat: Category) {
        val entries = DebugLogger.getByCategory(cat, 200)
        val stats = DebugLogger.getCategoryStats()
        val totalCount = stats.values.sum()

        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  分类: ${cat.label}")
        sb.appendLine("  条目: ${entries.size} / $totalCount  (总计)")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        if (cat == Category.SYS) {
            // 系统信息：优先显示系统快照
            sb.appendLine(DebugLogger.getSystemInfo())
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("════════════ 最近系统日志 ════════════")
            sb.appendLine()
            if (entries.isEmpty()) {
                sb.appendLine("(暂无系统日志 — 应用运行后自动采集)")
            } else {
                entries.forEach { sb.appendLine(it.formatted()) }
            }
        } else {
            if (entries.isEmpty()) {
                sb.appendLine("(暂无 ${cat.label} 日志)")
                if (!DebugLogger.enabled) {
                    sb.appendLine()
                    sb.appendLine("提示: 调试模式已关闭，请先启用顶部开关")
                }
            } else {
                entries.forEach { sb.appendLine(it.formatted()) }
            }
        }

        tvLogContent.text = sb.toString()
        scrollToTop()
    }

    private fun showFullReport() {
        lifecycleScope.launch {
            tvLogContent.text = "正在生成全量诊断报告..."
            val report = withContext(Dispatchers.IO) {
                DebugLogger.generateFullReport(this@DebugLogActivity, window.decorView)
            }
            tvLogContent.text = report
            scrollToTop()
        }
    }

    private fun showCrashContent() {
        val crashLog = CrashHandler.readCrashLog(this)
        if (crashLog.isBlank()) {
            tvLogContent.text = "(无崩溃日志)"
            return
        }
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  崩溃日志 (已脱敏)")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()
        sb.appendLine(crashLog)
        tvLogContent.text = sb.toString()
    }

    /** UI 快照：捕获当前窗口视图层级 */
    private fun captureUiSnapshot() {
        val snapshot = DebugLogger.captureUiSnapshot(window.decorView)
        currentCategory = Category.UI
        selectCategory(Category.UI)
        tvLogContent.text = snapshot
        scrollToTop()
        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "UI 视图快照已生成")
    }

    // ==================== 操作 ====================

    private fun copyCurrent() {
        val text = tvLogContent.text?.toString() ?: ""
        if (text.isBlank()) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "没有可复制的内容")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("diagnostic", text))
        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "已复制到剪贴板")
    }

    private fun shareFullReport() {
        lifecycleScope.launch {
            try {
                val fullText = withContext(Dispatchers.IO) {
                    buildString {
                        // 1. 崩溃日志（如有）
                        val crashLog = CrashHandler.readCrashLog(this@DebugLogActivity)
                        if (crashLog.isNotBlank()) {
                            appendLine(crashLog)
                            appendLine()
                        }
                        // 2. 全量诊断报告
                        appendLine(DebugLogger.generateFullReport(
                            this@DebugLogActivity, window.decorView
                        ))
                    }
                }

                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir("logs") ?: filesDir
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "ufitools_diagnostic.txt")
                    f.writeText(fullText)
                    f
                }

                val uri = FileProvider.getUriForFile(
                    this@DebugLogActivity,
                    "$packageName.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "UFITOOLS-Widget 诊断报告（已脱敏）")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "发送诊断报告"))

                // 分享后清除崩溃标志
                SPUtil.clearCrashInfo(this@DebugLogActivity)
                crashBanner.visibility = View.GONE
            } catch (e: Exception) {
                ToastUtil.showDropToast(this@DebugLogActivity, ToastStyle.WARNING, "分享失败", "${e.message}")
            }
        }
    }

    private fun clearAll() {
        DebugLogger.clear()
        CrashHandler.clearCrashLog(this)
        SPUtil.clearCrashInfo(this)
        refreshCurrentView()
        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "全部日志已清空")
    }

    // ==================== 崩溃横幅 ====================

    private fun setupCrashBanner() {
        crashBanner.visibility = View.VISIBLE
        val crashTime = SPUtil.getLastCrashTime(this)
        val crashSummary = SPUtil.getLastCrashSummary(this)

        val timeText = if (crashTime > 0) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(crashTime))
        } else "未知时间"
        findViewById<TextView>(R.id.tv_crash_time).text = "崩溃时间: $timeText"
        findViewById<TextView>(R.id.tv_crash_summary).text =
            crashSummary.ifEmpty { "（无崩溃摘要信息）" }

        findViewById<View>(R.id.btn_crash_ignore).setOnClickListener {
            SPUtil.clearCrashInfo(this)
            CrashHandler.clearCrashLog(this)
            crashBanner.visibility = View.GONE
            ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "崩溃记录已清除")
        }

        findViewById<View>(R.id.btn_crash_save).setOnClickListener {
            selectFullReport()
            shareFullReport()
        }
    }

    // ==================== 辅助 ====================

    private fun updateStatus() {
        val stats = DebugLogger.getCategoryStats()
        val total = stats.values.sum()
        val parts = stats.map { (cat, count) -> "${cat.colorTag}:$count" }.joinToString(" ")
        tvStatus.text = "调试: ${if (DebugLogger.enabled) "ON" else "OFF"} | 总计: $total | $parts"
    }

    private fun scrollToTop() {
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_UP) }
    }
}
