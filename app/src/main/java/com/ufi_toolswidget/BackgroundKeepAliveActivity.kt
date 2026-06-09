package com.ufi_toolswidget

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.work.*
import com.ufi_toolswidget.service.BackgroundMonitorService
import com.ufi_toolswidget.service.KeepAliveAccessibilityService
import com.ufi_toolswidget.util.*
import com.ufi_toolswidget.view.ThemeSlider
import com.ufi_toolswidget.worker.KeepAlivePeriodicWorker
import java.util.concurrent.TimeUnit

class BackgroundKeepAliveActivity : AppCompatActivity() {

    private var bgServiceSwitchView: ViewGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_background_keep_alive)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initBackgroundServiceItem()
        initBatteryOptimizationItem()
        initAutoStartItem()
        initTaskLockGuide()
        initNotifCustomItem()
        initHideRecentsItem()
        initPeriodicWorkerItem()
        initAccessibilityItem()
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        updateAllSubtitles()
        updateNotifCustomVisibility()
        // 重新应用最近任务隐藏（跳转系统设置返回后可能需要）
        if (SPUtil.getHideFromRecents(this)) {
            applyHideRecents(true)
        }
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.flushToFile()
    }

    private fun updateAllSubtitles() {
        updateBackgroundServiceSubtitle()
        updateBatteryOptimizationSubtitle()
        updateNotifCustomSubtitle()
        updateHideRecentsSubtitle()
        updatePeriodicWorkerSubtitle()
        updateAccessibilitySubtitle()
    }

    // ==================== 1. 前台保活服务开关 ====================

    private fun initBackgroundServiceItem() {
        try {
            val bgServiceEnabled = SPUtil.getBackgroundServiceEnabled(this)
            val switchView = findViewById<ViewGroup>(R.id.item_background_service) ?: return
            bgServiceSwitchView = switchView
            CommonSettingsItemHelper.setupSwitchItem(
                itemView = switchView,
                iconRes = R.drawable.ic_rocket,
                label = "前台保活通知",
                subtitle = if (bgServiceEnabled) "运行中 · 点击可配置通知样式" else "通过前台通知防止进程被系统回收",
                initialChecked = bgServiceEnabled,
                onToggle = { checked ->
                    SPUtil.setBackgroundServiceEnabled(this, checked)
                    BackgroundMonitorService.syncState(this)
                    updateBackgroundServiceSubtitle()
                    updateNotifCustomVisibility()
                }
            )
            // 初始化时设置通知自定义区域的显隐
            updateNotifCustomVisibility()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init bg service FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun updateBackgroundServiceSubtitle() {
        try {
            val enabled = SPUtil.getBackgroundServiceEnabled(this)
            bgServiceSwitchView
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = if (enabled) "运行中 · 点击可配置通知样式" else "通过前台通知防止进程被系统回收"
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update bg service subtitle failed: ${e.message}")
        }
    }

    private fun updateNotifCustomVisibility() {
        try {
            val group = findViewById<View>(R.id.group_notif_custom) ?: return
            val enabled = SPUtil.getBackgroundServiceEnabled(this)
            group.visibility = if (enabled) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            DebugLogger.w(TAG, "updateNotifCustomVisibility failed: ${e.message}")
        }
    }

    // ==================== 2. 电池优化白名单 ====================

    private fun initBatteryOptimizationItem() {
        try {
            val itemView = findViewById<ViewGroup>(R.id.item_battery_optimization) ?: return
            CommonSettingsItemHelper.setupSettingItem(
                itemView = itemView,
                iconRes = R.drawable.ic_eye,
                title = "忽略电池优化",
                subtitle = "点击检查",
                onClick = {
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimization(this)
                }
            )
            // 异步检查白名单状态（部分 ROM 上 PowerManager IPC 可能阻塞主线程）
            Thread {
                try {
                    val ignoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
                    runOnUiThread {
                        itemView.findViewById<TextView>(R.id.common_item_subtitle)?.text =
                            if (ignoring) "已加入白名单" else "点击加入白名单"
                    }
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "battery opt check FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
                }
            }.start()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init battery opt FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun updateBatteryOptimizationSubtitle() {
        try {
            val subtitleView = findViewById<ViewGroup>(R.id.item_battery_optimization)
                ?.findViewById<TextView>(R.id.common_item_subtitle) ?: return
            Thread {
                try {
                    val ignoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
                    runOnUiThread {
                        subtitleView.text = if (ignoring) "已加入白名单" else "点击加入白名单"
                    }
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "battery opt subtitle update FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
                }
            }.start()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update battery opt subtitle FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    // ==================== 3. 自启动权限 ====================

    private fun initAutoStartItem() {
        try {
            val autoStartView = findViewById<ViewGroup>(R.id.item_auto_start) ?: return
            if (!BatteryOptimizationHelper.hasAutoStartPage()) {
                autoStartView.visibility = View.GONE
                return
            }
            CommonSettingsItemHelper.setupSettingItem(
                itemView = autoStartView,
                iconRes = R.drawable.ic_settings,
                title = "自启动权限",
                subtitle = "${BatteryOptimizationHelper.getRomBrand()} · 点击前往设置",
                onClick = {
                    BatteryOptimizationHelper.openAutoStartSettings(this)
                }
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init auto start FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    // ==================== 4. 最近任务锁定引导 ====================

    private fun initTaskLockGuide() {
        try {
            findViewById<TextView>(R.id.text_task_lock_guide)?.apply {
                text = BatteryOptimizationHelper.getTaskLockGuideText()
                setTextColor(ThemeColors.textPrimary(this@BackgroundKeepAliveActivity))
                alpha = 0.6f
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init task lock FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    // ==================== 5. 自定义通知（标题 + 内容合并） ====================

    private fun initNotifCustomItem() {
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_notif_custom),
            iconRes = R.drawable.ic_notification,
            title = "自定义通知",
            subtitle = getNotifCustomSubtitle(),
            onClick = { showEditNotifCustomDialog() }
        )
    }

    private fun getNotifCustomSubtitle(): String {
        val customTitle = SPUtil.getCustomNotifTitle(this)
        val customText = SPUtil.getCustomNotifText(this)
        return when {
            customTitle.isNotEmpty() || customText.isNotEmpty() -> "已自定义 · 点击编辑"
            else -> "自定义前台通知的标题和内容"
        }
    }

    private fun updateNotifCustomSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_notif_custom)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getNotifCustomSubtitle()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update notif custom subtitle failed: ${e.message}")
        }
    }

    private fun showEditNotifCustomDialog() {
        val currentTitle = SPUtil.getCustomNotifTitle(this)
        val currentText = SPUtil.getCustomNotifText(this)

        val titleEdit = CommonSettingsItemHelper.createThemedEditText(
            this,
            hint = "通知标题（留空使用默认）",
            text = currentTitle,
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        )
        val textEdit = CommonSettingsItemHelper.createThemedEditText(
            this,
            hint = "通知内容（留空使用默认）",
            text = currentText,
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        )

        CommonDialogHelper.showCommonDialog(
            context = this,
            title = "自定义通知",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                // 标题标签
                content.addView(TextView(this).apply {
                    text = "通知标题"
                    textSize = 13f
                    setTextColor(ThemeColors.textSecondary(this@BackgroundKeepAliveActivity))
                    val dp4 = (4 * resources.displayMetrics.density).toInt()
                    setPadding(0, 0, 0, dp4)
                })
                content.addView(titleEdit)

                // 间距
                val dp12 = (12 * resources.displayMetrics.density).toInt()
                content.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp12)
                })

                // 内容标签
                content.addView(TextView(this).apply {
                    text = "通知内容"
                    textSize = 13f
                    setTextColor(ThemeColors.textSecondary(this@BackgroundKeepAliveActivity))
                    val dp4 = (4 * resources.displayMetrics.density).toInt()
                    setPadding(0, 0, 0, dp4)
                })
                content.addView(textEdit)
            },
            primaryBtnText = "确认",
            onPrimaryClick = { dialog ->
                val newTitle = titleEdit.text.toString().trim()
                val newText = textEdit.text.toString().trim()
                SPUtil.setCustomNotifTitle(this, newTitle)
                SPUtil.setCustomNotifText(this, newText)
                updateNotifCustomSubtitle()
                BackgroundMonitorService.refreshNotification(this)
                dialog.dismiss()
            },
            secondaryBtnText = "恢复默认",
            onSecondaryClick = { dialog ->
                SPUtil.setCustomNotifTitle(this, "")
                SPUtil.setCustomNotifText(this, "")
                updateNotifCustomSubtitle()
                BackgroundMonitorService.refreshNotification(this)
                dialog.dismiss()
            }
        )
    }

    // ==================== 6. 后台隐藏软件（最近任务不显示） ====================

    private fun initHideRecentsItem() {
        try {
            val hideEnabled = SPUtil.getHideFromRecents(this)
            val switchView = findViewById<ViewGroup>(R.id.item_hide_recents) ?: return
            CommonSettingsItemHelper.setupSwitchItem(
                itemView = switchView,
                iconRes = R.drawable.ic_eye,
                label = "后台隐藏软件",
                subtitle = if (hideEnabled) "已从最近任务隐藏" else "在最近任务中显示",
                initialChecked = hideEnabled,
                onToggle = { checked ->
                    if (checked) {
                        // 开启前弹出提示，建议用户先锁定应用
                        showHideRecentsWarningDialog(switchView)
                    } else {
                        applyHideRecents(false)
                        updateHideRecentsSubtitle()
                    }
                }
            )
            // 已启用时立即应用隐藏
            if (hideEnabled) applyHideRecents(true)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init hide recents FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun showHideRecentsWarningDialog(switchView: ViewGroup) {
        CommonDialogHelper.showCommonDialog(
            context = this,
            title = "后台隐藏软件",
            iconRes = R.drawable.ic_eye,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = "开启后本应用将从最近任务列表中隐藏。\n\n建议您先在最近任务中长按本应用并选择「锁定」，防止系统清理后无法快速找到本应用。\n\n确认要开启吗？"
                    textSize = 14f
                    setTextColor(ThemeColors.textSecondary(this@BackgroundKeepAliveActivity))
                    val lineSpacing = (4 * resources.displayMetrics.density)
                    setLineSpacing(lineSpacing, 1f)
                })
            },
            primaryBtnText = "确认开启",
            onPrimaryClick = { dialog ->
                applyHideRecents(true)
                updateHideRecentsSubtitle()
                dialog.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { dialog ->
                // 恢复开关状态（用户取消操作）
                SPUtil.setHideFromRecents(this, false)
                ThemeUtil.setSwitchVisualSilently(switchView, false)
                updateHideRecentsSubtitle()
                dialog.dismiss()
            }
        )
    }

    private fun applyHideRecents(hide: Boolean) {
        try {
            SPUtil.setHideFromRecents(this, hide)
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            am?.let { activityManager ->
                for (task in activityManager.appTasks) {
                    task.setExcludeFromRecents(hide)
                }
            }
            DebugLogger.logSys(TAG, "hideFromRecents applied: $hide")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "applyHideRecents FAILED: ${e.message}", force = true)
        }
    }

    private fun updateHideRecentsSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_hide_recents)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = if (SPUtil.getHideFromRecents(this)) "已从最近任务隐藏" else "在最近任务中显示"
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update hide recents subtitle failed: ${e.message}")
        }
    }

    // ==================== 7. WorkManager 周期性任务 ====================

    private var periodicIntervalMin = 15

    private fun initPeriodicWorkerItem() {
        try {
            val workerEnabled = SPUtil.getPeriodicWorkerEnabled(this)
            val switchView = findViewById<ViewGroup>(R.id.item_periodic_worker) ?: return
            CommonSettingsItemHelper.setupSwitchItem(
                itemView = switchView,
                iconRes = R.drawable.ic_rocket,
                label = "周期性保活任务",
                subtitle = if (workerEnabled) "已开启 · 间隔 ${formatIntervalMin(periodicIntervalMin)}" else "已关闭",
                initialChecked = workerEnabled,
                onToggle = { checked ->
                    SPUtil.setPeriodicWorkerEnabled(this, checked)
                    if (checked) {
                        schedulePeriodicWorker()
                    } else {
                        cancelPeriodicWorker()
                    }
                    updatePeriodicWorkerSubtitle()
                    updatePeriodicWorkerIntervalVisibility()
                }
            )
            initPeriodicWorkerIntervalItem()
            updatePeriodicWorkerIntervalVisibility()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init periodic worker FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun getPeriodicWorkerSubtitle(): String {
        return if (SPUtil.getPeriodicWorkerEnabled(this)) "已开启 · 间隔 ${formatIntervalMin(periodicIntervalMin)}" else "已关闭"
    }

    private fun updatePeriodicWorkerSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_periodic_worker)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = getPeriodicWorkerSubtitle()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update periodic worker subtitle failed: ${e.message}")
        }
    }

    private fun updatePeriodicWorkerIntervalVisibility() {
        try {
            val enabled = SPUtil.getPeriodicWorkerEnabled(this)
            findViewById<View>(R.id.item_periodic_worker_interval)?.visibility =
                if (enabled) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun initPeriodicWorkerIntervalItem() {
        try {
            periodicIntervalMin = SPUtil.getPeriodicWorkerIntervalMin(this)
            val itemView = findViewById<ViewGroup>(R.id.item_periodic_worker_interval) ?: return
            CommonSettingsItemHelper.setupSettingItem(
                itemView = itemView,
                iconRes = R.drawable.ic_clock_bolt,
                title = "检查间隔",
                subtitle = formatIntervalMin(periodicIntervalMin),
                onClick = { showPeriodicIntervalDialog() }
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init periodic worker interval FAILED: ${e.message}", force = true)
        }
    }

    private fun showPeriodicIntervalDialog() {
        showSliderThresholdDialog(
            title = "检查间隔",
            iconRes = R.drawable.ic_clock_bolt,
            switchChecked = true,
            currentValue = periodicIntervalMin.toFloat(),
            unit = "分钟",
            sliderMin = 15f, sliderMax = 120f, sliderStep = 5f, tickStep = 15f,
            presets = listOf(15, 30, 60, 120),
            subtitle = "间隔越小越及时，但更耗电（最小 15 分钟）",
            showSwitch = false,
            onToggle = {},
            onThresholdChange = { v ->
                periodicIntervalMin = (v / 5).toInt() * 5  // 对齐到 5 的倍数
                SPUtil.setPeriodicWorkerIntervalMin(this, periodicIntervalMin)
                updatePeriodicIntervalSubtitle()
                updatePeriodicWorkerSubtitle()
                // 重新调度以应用新间隔
                if (SPUtil.getPeriodicWorkerEnabled(this)) {
                    schedulePeriodicWorker()
                }
            }
        )
    }

    private fun updatePeriodicIntervalSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_periodic_worker_interval)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = formatIntervalMin(periodicIntervalMin)
        } catch (_: Exception) {}
    }

    private fun formatIntervalMin(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes} 分钟"
            minutes % 60 == 0 -> "${minutes / 60} 小时"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

    private fun schedulePeriodicWorker() {
        try {
            val intervalMin = SPUtil.getPeriodicWorkerIntervalMin(this)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<KeepAlivePeriodicWorker>(
                intervalMin.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                KeepAlivePeriodicWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            DebugLogger.logSys(TAG, "Periodic worker scheduled (${intervalMin} min interval)")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "schedulePeriodicWorker FAILED: ${e.message}", force = true)
        }
    }

    private fun cancelPeriodicWorker() {
        try {
            WorkManager.getInstance(this).cancelUniqueWork(KeepAlivePeriodicWorker.WORK_NAME)
            DebugLogger.logSys(TAG, "Periodic worker cancelled")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "cancelPeriodicWorker FAILED: ${e.message}", force = true)
        }
    }

    // ==================== 8. 无障碍服务 ====================

    private fun initAccessibilityItem() {
        try {
            val itemView = findViewById<ViewGroup>(R.id.item_accessibility_service) ?: return
            CommonSettingsItemHelper.setupSettingItem(
                itemView = itemView,
                iconRes = R.drawable.ic_settings,
                title = "无障碍保活服务",
                subtitle = getAccessibilitySubtitle(),
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "open accessibility settings FAILED: ${e.message}", force = true)
                    }
                }
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init accessibility FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun getAccessibilitySubtitle(): String {
        return if (KeepAliveAccessibilityService.isRunning) "已连接 · 进程保活中" else "未连接 · 点击前往设置"
    }

    private fun updateAccessibilitySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_accessibility_service)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getAccessibilitySubtitle()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update accessibility subtitle failed: ${e.message}")
        }
    }

    // ==================== 9. 通用：滑条弹窗（带预设 + 自定义输入） ====================

    private var activeDialog: android.app.Dialog? = null

    private fun showSliderThresholdDialog(
        title: String,
        iconRes: Int,
        switchChecked: Boolean,
        currentValue: Float,
        unit: String,
        sliderMin: Float,
        sliderMax: Float,
        sliderStep: Float,
        tickStep: Float,
        presets: List<Int>,
        subtitle: String? = null,
        showSwitch: Boolean = true,
        onToggle: (Boolean) -> Unit,
        onThresholdChange: (Float) -> Unit
    ) {
        val dialog = CommonDialogHelper.createAnimatedDialog(this) { activeDialog = null }
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<android.widget.ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 开关行（部分弹窗如间隔设置不需要开关）
        if (showSwitch) {
            content.addView(CommonSettingsItemHelper.createSwitchRow(
                context = this, label = title, subtitle = subtitle,
                initialChecked = switchChecked, onToggle = onToggle
            ))
        }

        // 当前值标签
        val valueLabel = TextView(this).apply {
            text = "${currentValue.toInt()} $unit"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@BackgroundKeepAliveActivity))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(valueLabel)

        val thresholdCallback = onThresholdChange
        var updatePresets: (Int) -> Unit = {}

        // 滑条
        val slider = ThemeSlider(this).also { s ->
            s.minValue = sliderMin
            s.maxValue = sliderMax
            s.stepSize = sliderStep
            s.currentValue = currentValue.coerceIn(sliderMin, sliderMax)
            s.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            s.onValueChanging = { v -> valueLabel.text = "${v.toInt()} $unit" }
            s.onValueChange = { v ->
                valueLabel.text = "${v.toInt()} $unit"
                thresholdCallback(v)
                updatePresets(v.toInt())
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, tickStep) { "${it.toInt()}$unit" }
        content.addView(slider)

        // 预设芯片
        val (presetRow, updater) = CommonDialogHelper.createPresetRow(
            context = this,
            values = presets,
            formatLabel = { "$it$unit" },
            currentValue = currentValue.toInt(),
            onSelect = { value -> slider.currentValue = value.toFloat() }
        )
        updatePresets = updater
        content.addView(presetRow)

        // 自定义输入面板
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 $sliderMin-$sliderMax $unit",
            validate = { text ->
                val v = text.toFloatOrNull()
                when {
                    v == null -> "请输入有效数字"
                    v < sliderMin || v > sliderMax -> "请输入 $sliderMin-$sliderMax 之间的值"
                    else -> null
                }
            },
            onConfirm = { text -> slider.currentValue = text.toFloat() }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // 按钮
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确认"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")?.let { et ->
                        if (currentValue > 0 && (currentValue < sliderMin || currentValue > sliderMax)) {
                            et.setText(currentValue.toInt().toString())
                        }
                        et.requestFocus()
                    }
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BgKeepAlive"

        /** 供 BootReceiver 等外部组件调用，重新调度周期性 Worker */
        fun schedulePeriodicWorkerIfEnabled(context: android.content.Context) {
            if (!SPUtil.getPeriodicWorkerEnabled(context)) return
            try {
                val intervalMin = SPUtil.getPeriodicWorkerIntervalMin(context)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<KeepAlivePeriodicWorker>(
                    intervalMin.toLong(), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    KeepAlivePeriodicWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                DebugLogger.d(TAG, "Periodic worker re-scheduled (${intervalMin} min interval)")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "schedulePeriodicWorkerIfEnabled FAILED: ${e.message}", force = true)
            }
        }
    }
}
