package com.ufi_toolswidget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DebugLogActivity : AppCompatActivity() {

    private lateinit var tvLogContent: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_log)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)

        tvLogContent = findViewById(R.id.tv_log_content)
        scrollLog = findViewById(R.id.scroll_log)
        tvStatus = findViewById(R.id.tv_log_status)

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 刷新按钮
        findViewById<MaterialButton>(R.id.btn_refresh_log).setOnClickListener {
            refreshLog()
        }

        // 复制全部按钮
        findViewById<MaterialButton>(R.id.btn_copy_log).setOnClickListener {
            copyAll()
        }

        // 分享按钮
        findViewById<MaterialButton>(R.id.btn_share_log).setOnClickListener {
            shareLog()
        }

        // 转储状态按钮
        findViewById<MaterialButton>(R.id.btn_dump_state).setOnClickListener {
            dumpState()
        }

        // 清空按钮
        findViewById<MaterialButton>(R.id.btn_clear_log).setOnClickListener {
            DebugLogger.clear()
            refreshLog()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToSecondaryPage(this)
        refreshLog()
    }

    private fun refreshLog() {
        val entries = DebugLogger.getRecent(200)
        val enabled = DebugLogger.enabled
        tvStatus.text = "调试模式: ${if (enabled) "已启用" else "已禁用"} | 日志条数: ${DebugLogger.size()}"

        if (entries.isEmpty()) {
            tvLogContent.text = if (enabled) {
                "(暂无日志 — 请在主界面操作后刷新查看)"
            } else {
                "(调试模式未启用 — 请在关于页面连续点击版本号激活)"
            }
        } else {
            tvLogContent.text = entries.joinToString("\n") { it.formatted() }
        }

        // 滚动到底部（最新日志在底部）
        scrollLog.post {
            scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyAll() {
        val text = DebugLogger.getAllText()
        if (text.isBlank()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", text))
        Toast.makeText(this, "已复制 ${DebugLogger.size()} 条日志到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        lifecycleScope.launch {
            try {
                val fullText = withContext(Dispatchers.IO) {
                    buildString {
                        appendLine(DebugLogger.dumpState(this@DebugLogActivity))
                        appendLine()
                        appendLine("========== 调试日志 ==========")
                        appendLine(DebugLogger.getAllText())
                    }
                }

                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val f = File(dir, "debug_log_share.txt")
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
                    putExtra(Intent.EXTRA_SUBJECT, "UFITOOLS-Widget 调试日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享调试日志"))
            } catch (e: Exception) {
                Toast.makeText(this@DebugLogActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dumpState() {
        val stateText = DebugLogger.dumpState(this)
        tvLogContent.text = stateText
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_state", stateText))
        Toast.makeText(this, "状态已转储并复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
