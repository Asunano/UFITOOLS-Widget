package com.ufi_toolswidget

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.PopupViewUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.ToastStyle
import com.ufi_toolswidget.util.ToastUtil
import com.ufi_toolswidget.util.UpdateChecker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
            AnimationUtil.applyCircleRevealPulse(this@AboutActivity) {
                ThemeUtil.applyThemeSync(this@AboutActivity, ThemeUtil.PageType.SECONDARY)
            }
            refreshAppIcon()
            currentMirror = SPUtil.getUpdateMirror(this@AboutActivity)
            refreshUpdateSettingsDialog()
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
        tvVersion.text = "Version $versionName"

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
                ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "调试模式已激活", "再次点击版本号查看日志")
            } else if (versionClickCount >= 3) {
                ToastUtil.showDropToast(this, ToastStyle.INFO, "再点击 ${5 - versionClickCount} 次激活调试模式")
            }
        }

        // 赞赏按钮
        setupDonateButton()

        // 开源地址
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.card_github),
            iconRes = R.drawable.ic_github,
            title = "开源地址",
            subtitle = "github.com/Asunano/UFITOOLS-Widget",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Asunano/UFITOOLS-Widget")))
            }
        )

        // 作者博客
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.card_blog),
            iconRes = R.drawable.ic_blog,
            title = "作者博客",
            subtitle = "blog.drxian.cn/archives/1322",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://blog.drxian.cn/archives/1322")))
            }
        )

        // 致谢
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.card_thanks),
            iconRes = R.drawable.ic_thanks,
            title = "致谢",
            subtitle = "基于 UFI-TOOLS 项目，感谢 kanoqwq 的开源贡献",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/kanoqwq/UFI-TOOLS")))
            }
        )

        // 加载当前镜像源设置
        currentMirror = SPUtil.getUpdateMirror(this)

        // 更新设置齿轮按钮
        findViewById<ImageView>(R.id.iv_update_settings).setOnClickListener {
            showUpdateSettingsDialog()
        }

        // 检查更新按钮（使用 layout_common_action_button 统一样式）
        val btnCheckRoot = findViewById<View>(R.id.btn_check_update)
        val btnCheckText = btnCheckRoot.findViewById<TextView>(R.id.common_btn_text)
        btnCheckText.text = "检查更新"
        btnCheckText.textSize = 14f
        // 动态取按钮底色，确保跟随主题配色
        btnCheckText.background = GradientDrawable().apply {
            setColor(ThemeColors.btnBg(this@AboutActivity))
            cornerRadius = 12f * resources.displayMetrics.density
        }

        AnimationUtil.applyScaleClickAnimation(btnCheckRoot) {
            if (!(btnCheckRoot.isEnabled)) return@applyScaleClickAnimation
            btnCheckRoot.isEnabled = false

            // 显示加载中提示
            ToastUtil.showLoadingToast(this@AboutActivity, "正在检查更新...")

            // 记录本次检查所使用的镜像源
            val usedMirror = currentMirror

            lifecycleScope.launch {
                when (val result = UpdateChecker.checkUpdate(this@AboutActivity)) {
                    is UpdateChecker.UpdateResult.Error -> {
                        if (usedMirror == 0 && UpdateChecker.isNetworkError(result.message)) {
                            ToastUtil.showDropToast(
                                activity = this@AboutActivity,
                                style = ToastStyle.WARNING,
                                title = "网络连接失败",
                                message = "建议切换至国内镜像源后重试"
                            )
                            showMirrorSwitchDialog()
                        } else {
                            ToastUtil.showDropToast(
                                activity = this@AboutActivity,
                                style = ToastStyle.WARNING,
                                title = "检查更新失败",
                                message = result.message
                            )
                        }
                    }
                    is UpdateChecker.UpdateResult.NewVersion -> {
                        ToastUtil.showDropToast(
                            activity = this@AboutActivity,
                            style = ToastStyle.INFO,
                            title = "发现新版本",
                            message = result.info.versionName
                        )
                        showUpdateDialog(result.info)
                    }
                    is UpdateChecker.UpdateResult.Latest -> {
                        ToastUtil.showDropToast(
                            activity = this@AboutActivity,
                            style = ToastStyle.SUCCESS,
                            title = "已是最新版本"
                        )
                    }
                }
                btnCheckRoot.isEnabled = true
            }
        }

    }

    // ==================== 赞赏 ====================

    /** 固定粉红色，不跟随主题 */
    private val DONATE_PINK = 0xFFFF6B9D.toInt()

    private fun setupDonateButton() {
        val btnDonate = findViewById<View>(R.id.btn_donate)
        val ivIcon = findViewById<ImageView>(R.id.iv_donate_icon)
        val tvLabel = findViewById<TextView>(R.id.tv_donate_label)

        // 固定粉色，使用 imageTintList 比 setColorFilter 更可靠
        ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(DONATE_PINK)
        tvLabel.setTextColor(DONATE_PINK)

        AnimationUtil.applyScaleClickAnimation(btnDonate) { showDonateDialog() }
    }

    private fun showDonateDialog() {
        try {
            val ctx = this
            val d = resources.displayMetrics.density
            val textSecondary = ThemeColors.textSecondary(ctx)

            // 在 dialog 外预先解码图片（降采样）
            val targetWidth = (280 * d).toInt()
            val bitmap = decodeDownsampledBitmap(ctx, R.drawable.img_donate_qr, targetWidth)

            CommonDialogHelper.showCommonDialog(
                context = ctx,
                title = "赞赏",
                iconRes = R.drawable.ic_heart,
                onFill = { content ->
                    // 提示文字
                    content.addView(TextView(ctx).apply {
                        text = "如果你喜欢这个软件,可以考虑请我喝一杯柠檬水哟~（狗头）下滑支持微信/支付宝💰💰💰"
                        textSize = 13f
                        setTextColor(textSecondary)
                        setLineSpacing(0f, 1.4f)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (12 * d).toInt() }
                    })

                    // 赞赏码图片
                    if (bitmap != null) {
                        val ivQr = ImageView(ctx).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            val cornerRadius = 14 * d
                            clipToOutline = true
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: android.graphics.Outline) {
                                    if (view.width > 0 && view.height > 0) {
                                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                                    }
                                }
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                        content.addView(ivQr)
                    }

                    // 底部小字
                    content.addView(TextView(ctx).apply {
                        text = "微信 / 支付宝 扫一扫"
                        textSize = 11f
                        setTextColor(textSecondary)
                        alpha = 0.5f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (10 * d).toInt() }
                    })
                },
                primaryBtnText = "关闭",
                onPrimaryClick = { dialog -> dialog.dismiss() }
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "showDonateDialog failed: ${e.message}")
        }
    }

    /**
     * 降采样加载大图，避免全分辨率解码导致 Canvas 崩溃。
     * 使用 openRawResource + decodeStream 跳过 decodeResource 的密度缩放。
     */
    private fun decodeDownsampledBitmap(ctx: Context, resId: Int, targetWidth: Int): android.graphics.Bitmap? {
        return try {
            // 第一步：仅读取原始尺寸
            val probeOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.resources.openRawResource(resId).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, probeOpts)
            }
            if (probeOpts.outWidth <= 0 || probeOpts.outHeight <= 0) return null

            // 第二步：计算 inSampleSize（必须是 2 的幂）
            var sampleSize = 1
            while (probeOpts.outWidth / (sampleSize * 2) >= targetWidth) {
                sampleSize *= 2
            }

            // 第三步：正式解码，RGB_565 省内存
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                inScaled = false  // 禁止密度缩放
            }
            ctx.resources.openRawResource(resId).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "decodeDownsampledBitmap failed: ${e.message}")
            null
        }
    }

    // ==================== 镜像源选择器 ====================
    private var currentMirror: Int = 0 // 0=官方, 1=镜像

    // 更新设置弹窗引用（用于主题变更时刷新）
    private var activeUpdateSettingsDialog: Dialog? = null

    /**
     * 显示更新设置弹窗（镜像源 + 自动检查更新开关 + 检查更新按钮）
     */
    private fun showUpdateSettingsDialog() {
        activeUpdateSettingsDialog?.takeIf { it.isShowing }?.dismiss()

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "更新设置"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_settings)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        fillUpdateSettingsContent(content)

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "完成"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeUpdateSettingsDialog = dialog
        dialog.show()
    }

    /**
     * 填充/刷新更新设置弹窗内容，使用最新的主题色。
     */
    private fun fillUpdateSettingsContent(content: LinearLayout) {
        content.removeAllViews()
        content.setPadding(0, dp2px(4), 0, 0)

        val ctx = this
        val accent = ThemeColors.accent(ctx)
        val cardBg = ThemeColors.cardBg(ctx)
        val textPrimary = ThemeColors.textPrimary(ctx)
        val textSecondary = ThemeColors.textSecondary(ctx)
        val chipRadius = 18f * resources.displayMetrics.density
        val animDuration = 200L

        // 动感动画：平滑切换 Chip 背景色
        fun animateChip(chip: TextView, selected: Boolean) {
            val toColor = if (selected) accent else cardBg
            val toTextColor = if (selected) 0xFFFFFFFF.toInt() else textPrimary
            val currentBg = chip.background as? GradientDrawable
            val fromColor = currentBg?.color?.defaultColor ?: toColor
            if (fromColor == toColor) {
                chip.setTextColor(toTextColor)
                return
            }
            ValueAnimator.ofArgb(fromColor, toColor).apply {
                duration = animDuration
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    chip.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(anim.animatedValue as Int)
                        cornerRadius = chipRadius
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        chip.setTextColor(toTextColor)
                    }
                })
                start()
            }
        }

        // ── 镜像源选择 ──
        content.addView(TextView(ctx).apply {
            text = "更新源"
            setTextColor(textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp2px(8))
        })

        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(38)
            )
        }
        val chipOfficial = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { marginEnd = dp2px(6) }
            gravity = Gravity.CENTER
            text = "GitHub 官方"
            textSize = 13f
            isClickable = true
            isFocusable = true
        }
        val chipProxy = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { marginStart = dp2px(6) }
            gravity = Gravity.CENTER
            text = "国内镜像"
            textSize = 13f
            isClickable = true
            isFocusable = true
        }

        // 初始样式
        chipOfficial.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(if (currentMirror == 0) accent else cardBg); cornerRadius = chipRadius
        }
        chipOfficial.setTextColor(if (currentMirror == 0) 0xFFFFFFFF.toInt() else textPrimary)
        chipProxy.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(if (currentMirror == 1) accent else cardBg); cornerRadius = chipRadius
        }
        chipProxy.setTextColor(if (currentMirror == 1) 0xFFFFFFFF.toInt() else textPrimary)

        chipOfficial.setOnClickListener {
            currentMirror = 0
            SPUtil.setUpdateMirror(this@AboutActivity, 0)
            animateChip(chipOfficial, true)
            animateChip(chipProxy, false)
            ToastUtil.showDropToast(this@AboutActivity, ToastStyle.INFO, "已切换至 GitHub 官方源")
        }
        chipProxy.setOnClickListener {
            currentMirror = 1
            SPUtil.setUpdateMirror(this@AboutActivity, 1)
            animateChip(chipOfficial, false)
            animateChip(chipProxy, true)
            ToastUtil.showDropToast(this@AboutActivity, ToastStyle.INFO, "已切换至 国内镜像源")
        }

        chipsRow.addView(chipOfficial)
        chipsRow.addView(chipProxy)
        content.addView(chipsRow)

        // ── 分隔线 ──
        content.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = dp2px(14)
                bottomMargin = dp2px(14)
            }
            setBackgroundColor(ThemeColors.divider(this@AboutActivity))
        })

        // ── 自动检查更新开关 ──
        val switchRow = CommonSettingsItemHelper.createSwitchRow(
            context = ctx,
            label = "启动时自动检查更新",
            initialChecked = SPUtil.getAutoCheckUpdate(this@AboutActivity),
            onToggle = { checked ->
                SPUtil.setAutoCheckUpdate(this@AboutActivity, checked)
            }
        )
        // 确保文字取色随主题（createSwitchRow 使用静态 style，需手动覆盖）
        switchRow.findViewById<TextView>(R.id.common_switch_label)?.setTextColor(textPrimary)
        content.addView(switchRow)
    }

    /**
     * 主题变更时刷新更新设置弹窗内容。
     */
    private fun refreshUpdateSettingsDialog() {
        activeUpdateSettingsDialog?.let { dialog ->
            if (dialog.isShowing) {
                CommonDialogHelper.applyThemeToDialogRoot(this, dialog)
                val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
                fillUpdateSettingsContent(content)
            }
        }
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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
                ToastUtil.showDropToast(this, ToastStyle.INFO, "已切换至国内镜像源", "请重新检查更新")
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

    // ===== 下载与安装逻辑（统一委托给 UpdateChecker） =====

    private fun downloadAndInstall(url: String, tag: String, sha256: String) {
        val receiver = UpdateChecker.prepareDownload(this, url, tag, sha256) { id ->
            downloadId = id
            ToastUtil.showDropToast(this, ToastStyle.INFO, "开始下载...")
        }
        if (receiver != null) {
            downloadReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
            downloadReceiver = receiver
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == UpdateChecker.PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "请重新点击下载")
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
        themeChangeReceiver = null
        activeUpdateSettingsDialog?.takeIf { it.isShowing }?.dismiss()
        activeUpdateSettingsDialog = null
        downloadReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        downloadReceiver = null
        super.onDestroy()
    }
}
