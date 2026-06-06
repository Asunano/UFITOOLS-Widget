package com.ufi_toolswidget

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.*
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

    // 详情弹窗相关
    private var activeDialog: Dialog? = null
    private var activeDialogType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SECONDARY)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@AboutActivity, ThemeUtil.PageType.SECONDARY)
            refreshAppIcon()
            currentMirror = SPUtil.getUpdateMirror(this@AboutActivity)
            refreshMirrorChips()
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

        // 更新镜像源选择器
        initMirrorSelector()

        // 检查更新按钮
        val btnCheck = findViewById<MaterialButton>(R.id.btn_check_update)
        val tvUpdateStatus = findViewById<TextView>(R.id.tv_update_status)
        val progressUpdate = findViewById<ProgressBar>(R.id.progress_update)

        // 自动检查更新开关
        findViewById<View>(R.id.item_auto_update)?.apply {
            findViewById<TextView>(R.id.common_switch_label).text = "启动时自动检查更新"
            ThemeUtil.setupSwitch(this, SPUtil.getAutoCheckUpdate(this@AboutActivity)) { isChecked ->
                SPUtil.setAutoCheckUpdate(this@AboutActivity, isChecked)
            }
        }

        btnCheck.apply {
            setOnClickListener {
                btnCheck.isEnabled = false
                tvUpdateStatus.visibility = View.VISIBLE
                tvUpdateStatus.text = "正在检查更新..."
                progressUpdate.visibility = View.VISIBLE

                // 记录本次检查所使用的镜像源
                val usedMirror = currentMirror

                UpdateChecker.checkUpdate(this@AboutActivity) { info, error ->
                    progressUpdate.visibility = View.GONE
                    btnCheck.isEnabled = true

                    when {
                        error != null -> {
                            if (usedMirror == 0 && UpdateChecker.isNetworkError(error)) {
                                // 使用官方源且网络出错 → 提示切换国内镜像
                                tvUpdateStatus.text = "$error\n\n💡 检测到网络问题，建议切换至「国内镜像」源后重试"
                                showMirrorSwitchDialog()
                            } else {
                                tvUpdateStatus.text = error
                            }
                        }
                        info != null -> {
                            tvUpdateStatus.text = "发现新版本 ${info.versionName}"
                            showUpdateDialog(info)
                        }
                        else -> {
                            tvUpdateStatus.text = "当前已是最新版本 ✓"
                        }
                    }
                }
            }
            setOnTouchListener(ScaleTouchListener())
        }

    }

    // ==================== 镜像源选择器 ====================
    private var currentMirror: Int = 0 // 0=官方, 1=镜像

    private fun initMirrorSelector() {
        currentMirror = SPUtil.getUpdateMirror(this)
        val chipOfficial = findViewById<TextView>(R.id.chip_mirror_official)
        val chipProxy = findViewById<TextView>(R.id.chip_mirror_proxy)

        fun selectMirror(mirror: Int) {
            currentMirror = mirror
            SPUtil.setUpdateMirror(this, mirror)
            refreshMirrorChips()
            // 切换镜像源时清除旧的检查状态
            findViewById<TextView>(R.id.tv_update_status).visibility = View.GONE
            val name = if (mirror == 0) "GitHub 官方" else "国内镜像"
            Toast.makeText(this, "已切换至 $name 源", Toast.LENGTH_SHORT).show()
        }

        chipOfficial.setOnClickListener { selectMirror(0) }
        chipProxy.setOnClickListener { selectMirror(1) }

        refreshMirrorChips()
    }

    private fun refreshMirrorChips() {
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)
        val textPrimary = ThemeColors.textPrimary(this)
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

        findViewById<TextView>(R.id.chip_mirror_official).apply {
            background = if (currentMirror == 0) chipSelected else chipUnselected
            setTextColor(if (currentMirror == 0) 0xFFFFFFFF.toInt() else textPrimary)
        }
        findViewById<TextView>(R.id.chip_mirror_proxy).apply {
            background = if (currentMirror == 1) chipSelected else chipUnselected
            setTextColor(if (currentMirror == 1) 0xFFFFFFFF.toInt() else textPrimary)
        }
    }

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
                refreshMirrorChips()
                Toast.makeText(this, "已切换至国内镜像源，请重新检查更新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 显示通用主题弹窗的公共逻辑 (Ported from MainActivity)
     */
    private fun showCommonDialog(
        type: String,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout) -> Unit,
        primaryBtnText: String = "关闭",
        onPrimaryClick: ((Dialog) -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: ((Dialog) -> Unit)? = null
    ): Dialog? {
        val dialog = createThemedDialog(R.layout.layout_common_dialog, dialogType = type)
        
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)
        
        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.removeAllViews()
        onFill(content)
        
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = primaryBtnText
        btnPrimary.setOnClickListener {
            if (onPrimaryClick != null) onPrimaryClick(dialog) else dialog.dismiss()
        }
        
        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        if (secondaryBtnText != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryBtnText
            btnSecondary.setOnClickListener {
                onSecondaryClick?.invoke(dialog) ?: dialog.dismiss()
            }
        } else {
            btnSecondary.visibility = View.GONE
        }
        
        dialog.show()
        return dialog
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

        showCommonDialog(
            type = "update",
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

    // ===== 弹窗框架 (Ported from MainActivity) =====

    private fun createThemedDialog(layoutRes: Int, widthRatio: Float = 0.92f, dialogType: String): Dialog {
        if (activeDialog?.isShowing == true && activeDialogType == dialogType) {
            return activeDialog!!
        }
        dismissActiveDialog()

        val dialog = object : Dialog(this) {
            fun realDismiss() {
                super.dismiss()
                activeDialog = null
                activeDialogType = null
            }

            override fun dismiss() {
                if (window == null) {
                    realDismiss()
                    return
                }
                activeDialogType = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AnimationUtil.applyDialogBlurOut(this) { realDismiss() }
                } else {
                    realDismiss()
                }
            }
        }

        dialog.setContentView(layoutRes)
        PopupViewUtil.applyThemeToDialogRoot(this, dialog)

        // ── 动态高度适配 ──
        PopupViewUtil.autoAdjustDialogHeight(this, dialog, widthRatio)

        // ── 动态模糊背景：API 31+ 原生模糊 ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurIn(dialog)
        }

        dialog.show()
        return dialog
    }

    private fun dismissActiveDialog() {
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        activeDialogType = null
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
        refreshMirrorChips()
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
        downloadReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
