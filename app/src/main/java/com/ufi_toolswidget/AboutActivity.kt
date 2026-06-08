package com.ufi_toolswidget

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.PopupViewUtil
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.UpdateChecker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class AboutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AboutActivity"
    }

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    /** 调试模式：版本号点击计数 */
    private var versionClickCount = 0
    private var versionClickLastTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SECONDARY)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@AboutActivity, ThemeUtil.PageType.SECONDARY)
            refreshAppIcon()
            currentMirror = SPUtil.getUpdateMirror(this@AboutActivity)
        }
        
        // 沉浸式入场动画：淡出旧画面截图
        AnimationUtil.applyCrossfadeEnterFromRecreate(this)

        DebugLogger.init(this)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        // 加载应用图标并清除滤镜
        refreshAppIcon()

        // 显示版本号
        val versionName = UpdateChecker.getLocalVersionName(this)
        val tvVersion = findViewById<TextView>(R.id.tv_app_version)
        tvVersion.text = "版本 $versionName"

        // 调试模式入口：连续点击版本号 5 次激活，再次点击进入日志页
        tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - versionClickLastTime > 1500) {
                versionClickCount = 0
            }
            versionClickLastTime = now
            versionClickCount++

            if (DebugLogger.enabled) {
                // 调试模式已启用 → 直接进入日志页
                startActivity(Intent(this, DebugLogActivity::class.java))
                versionClickCount = 0
            } else if (versionClickCount >= 5) {
                // 激活调试模式
                DebugLogger.enabled = true
                versionClickCount = 0
                DebugLogger.i(TAG, "调试模式已激活")
                Toast.makeText(this, "调试模式已激活 ✓\n再次点击版本号查看日志", Toast.LENGTH_LONG).show()
            } else if (versionClickCount >= 3) {
                Toast.makeText(this, "再点击 ${5 - versionClickCount} 次激活调试模式", Toast.LENGTH_SHORT).show()
            }
        }

        // GitHub 链接点击
        findViewById<View>(R.id.card_github).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Asunano/UFITOOLS-Widget"))
            startActivity(intent)
        }

        // 作者博客点击
        findViewById<View>(R.id.card_blog).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://blog.drxian.cn/archives/1322"))
            startActivity(intent)
        }

        // 致谢链接点击
        findViewById<View>(R.id.tv_thanks_link)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/kanoqwq/UFI-TOOLS"))
            startActivity(intent)
        }

        // 加载当前镜像源设置
        currentMirror = SPUtil.getUpdateMirror(this)

        // 更新设置齿轮按钮
        findViewById<ImageView>(R.id.iv_update_settings).setOnClickListener {
            showUpdateSettingsDialog()
        }

        // 检查更新按钮
        val btnCheck = findViewById<MaterialButton>(R.id.btn_check_update)
        val tvUpdateStatus = findViewById<TextView>(R.id.tv_update_status)
        val progressUpdate = findViewById<ProgressBar>(R.id.progress_update)

        btnCheck.apply {
            setOnClickListener {
                btnCheck.isEnabled = false
                tvUpdateStatus.visibility = View.VISIBLE
                tvUpdateStatus.text = "正在检查更新..."
                progressUpdate.visibility = View.VISIBLE

                // 记录本次检查所使用的镜像源
                val usedMirror = currentMirror

                lifecycleScope.launch {
                    when (val result = UpdateChecker.checkUpdate(this@AboutActivity)) {
                        is UpdateChecker.UpdateResult.Error -> {
                            if (usedMirror == 0 && UpdateChecker.isNetworkError(result.message)) {
                                tvUpdateStatus.text = "${result.message}\n\n💡 检测到网络问题，建议切换至「国内镜像」源后重试"
                                showMirrorSwitchDialog()
                            } else {
                                tvUpdateStatus.text = result.message
                            }
                        }
                        is UpdateChecker.UpdateResult.NewVersion -> {
                            tvUpdateStatus.text = "发现新版本 ${result.info.versionName}"
                            showUpdateDialog(result.info)
                        }
                        is UpdateChecker.UpdateResult.Latest -> {
                            tvUpdateStatus.text = "当前已是最新版本 ✓"
                        }
                    }
                    progressUpdate.visibility = View.GONE
                    btnCheck.isEnabled = true
                }
            }
            setOnTouchListener(ScaleTouchListener())
        }

    }

    // ==================== 镜像源选择器 ====================
    private var currentMirror: Int = 0 // 0=官方, 1=镜像

    /**
     * 显示更新设置弹窗（镜像源 + 自动检查更新开关 + 检查更新按钮）
     */
    private fun showUpdateSettingsDialog() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)
        val chipRadius = 18f * resources.displayMetrics.density

        val chipSelected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            cornerRadius = chipRadius
        }
        val chipUnselected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            cornerRadius = chipRadius
        }

        CommonDialogHelper.showCommonDialog(
            this,
            title = "更新设置",
            iconRes = R.drawable.ic_settings,
            onFill = { content ->
                content.setPadding(0, dp2px(4), 0, 0)

                // ── 镜像源选择 ──
                content.addView(TextView(this).apply {
                    text = "更新源"
                    setTextColor(textSecondary)
                    textSize = 12f
                    setPadding(0, 0, 0, dp2px(8))
                })

                val chipsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp2px(38)
                    )
                }
                val chipOfficial = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    ).apply { marginEnd = dp2px(6) }
                    gravity = Gravity.CENTER
                    text = "GitHub 官方"
                    textSize = 13f
                    isClickable = true
                    isFocusable = true
                }
                val chipProxy = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    ).apply { marginStart = dp2px(6) }
                    gravity = Gravity.CENTER
                    text = "国内镜像"
                    textSize = 13f
                    isClickable = true
                    isFocusable = true
                }

                // 刷新 Chip 样式
                fun refreshChips() {
                    chipOfficial.background = if (currentMirror == 0) chipSelected else chipUnselected
                    chipOfficial.setTextColor(if (currentMirror == 0) 0xFFFFFFFF.toInt() else textPrimary)
                    chipProxy.background = if (currentMirror == 1) chipSelected else chipUnselected
                    chipProxy.setTextColor(if (currentMirror == 1) 0xFFFFFFFF.toInt() else textPrimary)
                }

                chipOfficial.setOnClickListener {
                    currentMirror = 0
                    SPUtil.setUpdateMirror(this@AboutActivity, 0)
                    refreshChips()
                    findViewById<TextView>(R.id.tv_update_status).visibility = View.GONE
                    Toast.makeText(this@AboutActivity, "已切换至 GitHub 官方源", Toast.LENGTH_SHORT).show()
                }
                chipProxy.setOnClickListener {
                    currentMirror = 1
                    SPUtil.setUpdateMirror(this@AboutActivity, 1)
                    refreshChips()
                    findViewById<TextView>(R.id.tv_update_status).visibility = View.GONE
                    Toast.makeText(this@AboutActivity, "已切换至 国内镜像源", Toast.LENGTH_SHORT).show()
                }

                refreshChips()

                chipsRow.addView(chipOfficial)
                chipsRow.addView(chipProxy)
                content.addView(chipsRow)

                // ── 分隔线 ──
                content.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        topMargin = dp2px(14)
                        bottomMargin = dp2px(14)
                    }
                    setBackgroundColor(ThemeColors.divider(this@AboutActivity))
                })

                // ── 自动检查更新开关 ──
                content.addView(CommonSettingsItemHelper.createSwitchRow(
                    context = this,
                    label = "启动时自动检查更新",
                    initialChecked = SPUtil.getAutoCheckUpdate(this@AboutActivity),
                    onToggle = { checked ->
                        SPUtil.setAutoCheckUpdate(this@AboutActivity, checked)
                    }
                ))
            },
            primaryBtnText = "完成"
        )
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 网络连接失败时弹窗提示切换国内镜像源 */

    /** 网络连接失败时弹窗提示切换国内镜像源 */
    private fun showMirrorSwitchDialog() {
        if (currentMirror == 1) return // 已经是镜像源，不再提示
        PopupViewUtil.showConfirmDialog(
            this,
            title = "网络连接失败",
            message = "当前使用 GitHub 官方源检查更新失败，可能是网络不通。\n\n是否切换至国内镜像源？切换后需重新点击「检查更新」。",
            primaryBtnText = "切换至国内镜像",
            secondaryBtnText = "暂不切换",
            onConfirm = {
                currentMirror = 1
                SPUtil.setUpdateMirror(this, 1)
                findViewById<TextView>(R.id.tv_update_status).visibility = View.GONE
                Toast.makeText(this, "已切换至国内镜像源，请重新检查更新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 弹窗显示更新详情
     */
    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = buildString {
            append("发现新版本: ${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) {
                append("更新日志:\n")
                append(UpdateChecker.formatChangelog(info.changelog))
                append("\n\n")
            }
            if (info.apkSize > 0) {
                append("大小: ${UpdateChecker.formatFileSize(info.apkSize)}")
            }
        }

        CommonDialogHelper.showCommonDialog(
            this,
            title = "更新可用",
            iconRes = R.drawable.ic_check,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = message
                    textSize = 13f
                    setTextColor(ThemeColors.textPrimary(this@AboutActivity))
                    setLineSpacing(0f, 1.4f)
                })
            },
            primaryBtnText = "下载更新",
            onPrimaryClick = { d ->
                d.dismiss()
                downloadAndInstall(info.apkUrl, info.tagName, info.apkSha256)
            },
            secondaryBtnText = "稍后"
        )
    }

    // ===== 下载与安装逻辑 =====

    /**
     * 下载并安装（自动应用镜像加速）
     */
    private fun downloadAndInstall(url: String, tag: String, sha256: String) {
        val finalUrl = UpdateChecker.applyMirrorToUrl(this, url)
        if (finalUrl.isBlank()) {
            Toast.makeText(this, "没有可下载的 APK", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 9 及以下需要存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
                return
            }
        }

        startDownload(finalUrl, tag, sha256)
    }

    private fun startDownload(url: String, tag: String, sha256: String) {
        val fileName = "UFITOOLS-Widget-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("UFITOOLS-Widget")
            .setDescription("正在下载 $tag 版本...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show()

        // 监听下载完成
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName, sha256)
                    unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(fileName: String, sha256: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (!file.exists()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // SHA256 校验
        if (sha256.isNotBlank()) {
            if (!UpdateChecker.verifySha256(file, sha256)) {
                file.delete()
                Toast.makeText(this, "文件校验失败，已删除损坏文件\n请重新下载", Toast.LENGTH_LONG).show()
                return
            }
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请重新点击下载", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SECONDARY)
        refreshAppIcon()
        currentMirror = SPUtil.getUpdateMirror(this)
    }

    /** 刷新应用图标显示，确保不被主题滤镜覆盖 */
    private fun refreshAppIcon() {
        val iconView = findViewById<ImageView>(R.id.iv_app_icon) ?: return
        try {
            val icon = packageManager.getApplicationIcon(packageName)
            iconView.setImageDrawable(icon)
        } catch (_: Exception) {
            iconView.setImageResource(R.mipmap.ic_launcher)
        }
        iconView.clearColorFilter()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        downloadReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
