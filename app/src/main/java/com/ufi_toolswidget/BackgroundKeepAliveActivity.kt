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
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.ufi_toolswidget.service.BackgroundMonitorService
import com.ufi_toolswidget.service.KeepAliveAccessibilityService
import com.ufi_toolswidget.util.*
import com.ufi_toolswidget.worker.KeepAlivePeriodicWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        initAccessibilityItem()
        initBackgroundServiceItem()
        initNotifCustomItem()
        initBatteryOptimizationItem()
        initAutoStartItem()
        initAutoRecoverItem()
        initTaskLockGuide()
        initHideRecentsItem()
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
        updateAccessibilitySubtitle()
        updateBackgroundServiceSubtitle()
        updateNotifCustomSubtitle()
        updateBatteryOptimizationSubtitle()
        updateAutoRecoverSubtitle()
        updateHideRecentsSubtitle()
    }

    // ==================== 1. 前台保活服务开关 ====================

    private fun initBackgroundServiceItem() {
        try {
            val bgServiceEnabled = SPUtil.getBackgroundServiceEnabled(this)
            val switchView = findViewById<ViewGroup>(R.id.item_background_service) ?: return
            bgServiceSwitchView = switchView
            CommonSettingsItemHelper.setupSwitchItem(
                itemView = switchView,
                iconRes = R.drawable.ic_heartbeat,
                label = "前台保活通知",
                subtitle = if (bgServiceEnabled) "运行中 · 点击可配置通知样式" else "辅助保活，建议优先开启无障碍服务",
                initialChecked = bgServiceEnabled,
                onToggle = { checked ->
                    SPUtil.setBackgroundServiceEnabled(this, checked)
                    BackgroundMonitorService.syncState(this)
                    // 同步刷新 Doze 穿透闹钟
                    if (checked) {
                        com.ufi_toolswidget.service.AlarmReceiver.scheduleNext(this)
                    } else {
                        com.ufi_toolswidget.service.AlarmReceiver.cancel(this)
                    }
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
                ?.text = if (enabled) "运行中 · 点击可配置通知样式" else "辅助保活，建议优先开启无障碍服务"
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

    /** 电池优化状态检查协程 Job，用于取消过期任务防止竞态 */
    private var batteryOptCheckJob: Job? = null

    /**
     * 电池优化白名单缓存。
     * - `true` = 已确认加入白名单，后续 onResume 跳过 IPC
     * - `false` = 确认未加入
     * - `null` = 尚未检查，或用户点击跳转系统设置后重置
     */
    private var batteryOptWhitelistedCached: Boolean? = null

    private fun initBatteryOptimizationItem() {
        try {
            val itemView = findViewById<ViewGroup>(R.id.item_battery_optimization) ?: return
            CommonSettingsItemHelper.setupSettingItem(
                itemView = itemView,
                iconRes = R.drawable.ic_eye,
                title = "忽略电池优化",
                subtitle = "点击检查",
                onClick = {
                    // 点击意味着用户要去系统设置操作，清空缓存让返回时重新检查
                    batteryOptWhitelistedCached = null
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimization(this)
                }
            )
            // 异步检查白名单状态（部分 ROM 上 PowerManager IPC 可能阻塞主线程）
            checkBatteryOptStatus()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init battery opt FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    /**
     * 检查电池优化白名单状态并更新副标题。
     *
     * 缓存优化：若已确认加入白名单（[batteryOptWhitelistedCached] == true），
     * 则直接显示"已加入白名单"，跳过 IPC 调用。
     * 仅当缓存为 null/false 时才发起实际 IPC 查询。
     */
    private fun checkBatteryOptStatus() {
        // 快速路径：已缓存为 whitelisted，直接刷新副标题，无需 IPC
        if (batteryOptWhitelistedCached == true) {
            findViewById<ViewGroup>(R.id.item_battery_optimization)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = "已加入白名单"
            return
        }
        batteryOptCheckJob?.cancel()
        batteryOptCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ignoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@BackgroundKeepAliveActivity)
                withContext(Dispatchers.Main) {
                    // 缓存结果
                    batteryOptWhitelistedCached = ignoring
                    val subtitleView = findViewById<ViewGroup>(R.id.item_battery_optimization)
                        ?.findViewById<TextView>(R.id.common_item_subtitle) ?: return@withContext
                    subtitleView.text = if (ignoring) "已加入白名单" else "点击加入白名单"
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "battery opt check FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
            }
        }
    }

    private fun updateBatteryOptimizationSubtitle() {
        // onResume 中直接复用统一的检查方法，取消上一次过期检查
        checkBatteryOptStatus()
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

    // ==================== 7. 进程死亡自动恢复 ====================

    private fun initAutoRecoverItem() {
        try {
            val autoRecoverEnabled = SPUtil.getAutoRecoverEnabled(this)
            val switchView = findViewById<ViewGroup>(R.id.item_auto_recover) ?: return
            CommonSettingsItemHelper.setupSwitchItem(
                itemView = switchView,
                iconRes = R.drawable.ic_sync,
                label = "进程死亡自动恢复",
                subtitle = if (autoRecoverEnabled) "已开启 · 进程被杀死后自动恢复" else "已关闭",
                initialChecked = autoRecoverEnabled,
                onToggle = { checked ->
                    SPUtil.setAutoRecoverEnabled(this, checked)
                    // 开启时同步建立闹钟链，关闭时如果前台服务也关闭则取消闹钟
                    if (checked) {
                        com.ufi_toolswidget.service.AlarmReceiver.scheduleNext(this)
                    }
                    updateAutoRecoverSubtitle()
                }
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "init auto recover FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun updateAutoRecoverSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_auto_recover)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = if (SPUtil.getAutoRecoverEnabled(this)) "已开启 · 进程被杀死后自动恢复" else "已关闭"
        } catch (e: Exception) {
            DebugLogger.w(TAG, "update auto recover subtitle failed: ${e.message}")
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