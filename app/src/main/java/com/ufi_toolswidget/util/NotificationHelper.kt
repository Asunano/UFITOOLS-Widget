package com.ufi_toolswidget.util

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ufi_toolswidget.MainActivity
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备状态通知工具类。
 *
 * 提供通知通道创建、权限申请、各类型阈值触发通知等能力。
 * 所有通知在主数据刷新周期 ([MainActivity.applyWifiEntityToUi]) 中统一检查并触发。
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"

    private const val CHANNEL_ID = "device_alerts"
    private const val CHANNEL_NAME = "设备提醒"

    // 通知类型标识（用于警报历史分类）
    private const val TYPE_DAILY_FLOW      = "daily_flow"
    private const val TYPE_MONTHLY_FLOW    = "monthly_flow"
    private const val TYPE_TEMP            = "temp"
    private const val TYPE_CPU             = "cpu"
    private const val TYPE_DEVICE_ONLINE   = "device_online"
    private const val TYPE_BATTERY         = "battery"
    private const val TYPE_MEMORY          = "memory"

    // 通知 ID 自增计数器起始值
    private const val NOTIFY_ID_BASE = 10000

    /** 防抖间隔：动态读取用户设置的监控间隔，最小 15 秒 */
    private fun getDebounceMs(context: Context): Long {
        return SPUtil.getMonitorIntervalSec(context).coerceIn(15, 600) * 1000L
    }

    /** 复用时间格式化器，避免每次通知创建新实例 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ─── 初始化 ───

    /**
     * 应用启动时调用，创建通知渠道。
     */
    fun init(context: Context) {
        createNotificationChannel(context)
    }

    /**
     * 创建 Android 8.0+ 通知渠道。
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "流量使用、设备状态异常提醒"
                // 横幅 + 铃声 + 震动 + LED —— 确保国产 ROM 上不被降级为静默通知
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setSound(alarmSound, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                enableLights(true)
                lightColor = 0xFFFF6B35.toInt() // 橙色 LED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    // ─── 权限 ───

    /**
     * 检查是否有通知权限。
     * Android 13+ 需要 [Manifest.permission.POST_NOTIFICATIONS]。
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    /**
     * 申请通知权限（Android 13+）。
     */
    fun requestPermission(activity: Activity, requestCode: Int = 200) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(activity)) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
            }
        }
    }

    // ─── 国产 ROM 通知渠道检测 ───

    /**
     * 检查通知渠道是否被系统降级为静默通知。
     * 国产 ROM（ColorOS / MIUI / EMUI 等）常在用户不知情的情况下
     * 将渠道 importance 降为 LOW 或关闭横幅/铃声权限。
     *
     * @return true = 渠道正常（IMPORTANCE_HIGH），false = 被降级需要引导
     */
    fun isChannelImportanceSufficient(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /**
     * 打开系统通知渠道设置页面。
     * 用户可在此页面手动开启横幅、铃声、震动等权限。
     * 国产 ROM 上这是解决静默通知问题的最直接方式。
     */
    fun openChannelSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) { /* fallback below */ }
        }
        // 降级：打开应用通知设置总页
        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "openChannelSettings failed: ${e.message}")
        }
    }

    // ─── 通知发送 ───

    /**
     * 获取下一个通知 ID（自增，持久化）。
     * 每次通知使用唯一 ID，避免覆盖之前的通知。
     * 使用 synchronized 保证读-改-写的原子性。
     */
    private fun nextNotifyId(context: Context): Int {
        synchronized(debounceLock) {
            val sp = SPUtil.getSp(context)
            val current = sp.getInt("notify_id_counter", NOTIFY_ID_BASE)
            val next = if (current > NOTIFY_ID_BASE + 5000) NOTIFY_ID_BASE else current + 1
            sp.edit().putInt("notify_id_counter", next).apply()
            return next
        }
    }

    /**
     * 发送一条通知并记录到警报历史。
     * 每次调用生成唯一通知 ID，不会顶掉之前的通知。
     */
    private fun showNotification(context: Context, type: String, title: String, message: String) {
        if (!hasPermission(context)) {
            DebugLogger.logApi(TAG, "showNotification: no permission, skipping type=$type title=$title")
            // 无通知权限时仍然记录到警报历史（异步）
            addAlertAsync(context, type, title, message)
            return
        }

        val notifyId = nextNotifyId(context)
        DebugLogger.logApi(TAG, "showNotification: id=$notifyId type=$type title=$title msg=$message")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifyId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // fullScreenIntent：国产 ROM 横幅通知的可靠保障
        // 即使系统压制 IMPORTANCE_HIGH，fullScreenIntent 仍强制弹出横幅
        val fullScreenPending = PendingIntent.getActivity(
            context, notifyId + 10000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)  // 最高优先级，确保横幅弹出
            .setDefaults(NotificationCompat.DEFAULT_ALL) // 声音+震动+LED 全部启用
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenPending, true) // 强制横幅，确保 OEM ROM 不被降级
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏可见
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        NotificationManagerCompat.from(context).notify(notifyId, notification)

        // 写入警报历史（异步）
        addAlertAsync(context, type, title, message)
    }

    /** 异步写入警报历史（fire-and-forget，不阻塞调用线程） */
    private fun addAlertAsync(context: Context, type: String, title: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            AlertHistoryManager.addAlert(context, type, title, message)
        }
    }

    // ─── 主检查入口 ───

    /**
     * 单独检查设备上下线状态并发送通知。
     *
     * 当数据获取失败（设备离线）时，[checkAndNotify] 不会被调用，
     * 需要由调用方（如 [NotificationMonitor]、[WifiWorker]）单独调用此方法，
     * 确保设备下线通知能及时发出。
     *
     * @param context  上下文
     * @param isOnline 设备是否在线（数据获取成功 = true，失败 = false）
     */
    fun checkDeviceOnlineStatus(context: Context, isOnline: Boolean) {
        checkDeviceOnline(context, isOnline, activity = null)
    }

    /**
     * 主检查入口。在每次数据刷新成功后调用。
     *
     * @param context        上下文
     * @param activity       可选 Activity，传入时会在发送系统通知的同时显示应用内提示
     *                       （流量→警告确认弹窗，上下线→Toast，其他→警告Toast）
     * @param dailyFlowStr   今日流量文本（如 "1.2 GB"）
     * @param monthlyFlowStr 本月流量文本
     * @param tempStr        温度文本（如 "65℃"）
     * @param cpuStr         CPU 文本（如 "45%"）
     * @param memStr         内存文本（如 "60%"）
     * @param batteryPercent 电池百分比
     * @param isDeviceOnline 设备是否在线
     */
    fun checkAndNotify(
        context: Context,
        dailyFlowStr: String,
        monthlyFlowStr: String,
        tempStr: String,
        cpuStr: String,
        memStr: String,
        batteryPercent: Int,
        isDeviceOnline: Boolean,
        activity: Activity? = null
    ) {
        val masterEnabled = SPUtil.getNotificationEnabled(context)
        DebugLogger.logApi(TAG, "checkAndNotify: masterEnabled=$masterEnabled" +
                " dailyFlow=$dailyFlowStr monthlyFlow=$monthlyFlowStr temp=$tempStr" +
                " cpu=$cpuStr mem=$memStr battery=$batteryPercent online=$isDeviceOnline")

        if (!masterEnabled) {
            DebugLogger.logApi(TAG, "checkAndNotify: master switch OFF, skipping all checks")
            return
        }

        checkDailyFlow(context, dailyFlowStr, activity)
        checkMonthlyFlow(context, monthlyFlowStr, activity)
        checkTemperature(context, tempStr, activity)
        checkCpu(context, cpuStr, activity)
        checkMemory(context, memStr, activity)
        checkBattery(context, batteryPercent, activity)
        checkDeviceOnline(context, isDeviceOnline, activity)
    }

    // ─── 各类型检查 ───

    private fun checkDailyFlow(context: Context, flowStr: String, activity: Activity? = null) {
        if (!SPUtil.getNotifyDailyFlow(context)) {
            DebugLogger.logApi(TAG, "checkDailyFlow: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_daily_flow")) {
            DebugLogger.logApi(TAG, "checkDailyFlow: debounce blocked")
            return
        }

        val bytes = parseFlowToBytes(flowStr)
        DebugLogger.logApi(TAG, "checkDailyFlow: parsed=$bytes flowStr=$flowStr")
        if (bytes == null) return
        val threshold = SPUtil.getNotifyDailyFlowThreshold(context)
        DebugLogger.logApi(TAG, "checkDailyFlow: bytes=$bytes threshold=$threshold exceeded=${bytes >= threshold}")
        if (bytes >= threshold) {
            val title = "今日流量提醒"
            val msg = "今日已使用 ${formatBytes(bytes)}，超过阈值 ${formatBytes(threshold)}\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_DAILY_FLOW, title, msg)
            updateLastNotifyTime(context, "last_notify_daily_flow")
            activity?.let {
                ToastUtil.showWarningConfirmDialog(
                    it,
                    title = title,
                    message = "$msg\n\n请注意控制流量使用，避免超额产生额外费用。",
                    confirmText = "我知道了",
                    onConfirm = {}
                )
            }
        }
    }

    private fun checkMonthlyFlow(context: Context, flowStr: String, activity: Activity? = null) {
        if (!SPUtil.getNotifyMonthlyFlow(context)) {
            DebugLogger.logApi(TAG, "checkMonthlyFlow: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_monthly_flow")) {
            DebugLogger.logApi(TAG, "checkMonthlyFlow: debounce blocked")
            return
        }

        val bytes = parseFlowToBytes(flowStr)
        DebugLogger.logApi(TAG, "checkMonthlyFlow: parsed=$bytes flowStr=$flowStr")
        if (bytes == null) return
        val threshold = SPUtil.getNotifyMonthlyFlowThreshold(context)
        DebugLogger.logApi(TAG, "checkMonthlyFlow: bytes=$bytes threshold=$threshold exceeded=${bytes >= threshold}")
        if (bytes >= threshold) {
            val title = "本月流量提醒"
            val msg = "本月已使用 ${formatBytes(bytes)}，超过阈值 ${formatBytes(threshold)}\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_MONTHLY_FLOW, title, msg)
            updateLastNotifyTime(context, "last_notify_monthly_flow")
            // 应用内警告确认弹窗
            activity?.let {
                ToastUtil.showWarningConfirmDialog(
                    it,
                    title = title,
                    message = "$msg\n\n请合理规划流量使用，避免超额产生额外费用。",
                    confirmText = "我知道了",
                    onConfirm = {}
                )
            }
        }
    }

    private fun checkTemperature(context: Context, tempStr: String, activity: Activity? = null) {
        if (!SPUtil.getNotifyTemp(context)) {
            DebugLogger.logApi(TAG, "checkTemperature: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_temp")) {
            DebugLogger.logApi(TAG, "checkTemperature: debounce blocked")
            return
        }

        val temp = parseTemperature(tempStr)
        DebugLogger.logApi(TAG, "checkTemperature: parsed=$temp raw=$tempStr")
        if (temp == null) return
        val threshold = SPUtil.getNotifyTempThreshold(context)
        DebugLogger.logApi(TAG, "checkTemperature: temp=$temp threshold=$threshold triggered=${temp >= threshold}")
        if (temp >= threshold) {
            val title = "温度过高提醒"
            val msg = "当前设备温度 ${temp}℃，超过阈值 ${threshold}℃\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_TEMP, title, msg)
            updateLastNotifyTime(context, "last_notify_temp")
            // 应用内警告 Toast
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.WARNING, title, msg)
            }
        }
    }

    private fun checkCpu(context: Context, cpuStr: String, activity: Activity? = null) {
        if (!SPUtil.getNotifyCpu(context)) {
            DebugLogger.logApi(TAG, "checkCpu: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_cpu")) {
            DebugLogger.logApi(TAG, "checkCpu: debounce blocked")
            return
        }

        val pct = parsePercentage(cpuStr)
        DebugLogger.logApi(TAG, "checkCpu: parsed=$pct raw=$cpuStr")
        if (pct == null) return
        val threshold = SPUtil.getNotifyCpuThreshold(context)
        DebugLogger.logApi(TAG, "checkCpu: pct=$pct threshold=$threshold triggered=${pct >= threshold}")
        if (pct >= threshold) {
            val title = "CPU 异常占用提醒"
            val msg = "当前 CPU 占用 ${pct}%，超过阈值 ${threshold}%\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_CPU, title, msg)
            updateLastNotifyTime(context, "last_notify_cpu")
            // 应用内警告 Toast
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.WARNING, title, msg)
            }
        }
    }

    private fun checkMemory(context: Context, memStr: String, activity: Activity? = null) {
        if (!SPUtil.getNotifyMemory(context)) {
            DebugLogger.logApi(TAG, "checkMemory: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_memory")) {
            DebugLogger.logApi(TAG, "checkMemory: debounce blocked")
            return
        }

        val pct = parsePercentage(memStr)
        DebugLogger.logApi(TAG, "checkMemory: parsed=$pct raw=$memStr")
        if (pct == null) return
        val threshold = SPUtil.getNotifyMemoryThreshold(context)
        DebugLogger.logApi(TAG, "checkMemory: pct=$pct threshold=$threshold triggered=${pct >= threshold}")
        if (pct >= threshold) {
            val title = "内存占用过高提醒"
            val msg = "当前内存占用 ${pct}%，超过阈值 ${threshold}%\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_MEMORY, title, msg)
            updateLastNotifyTime(context, "last_notify_memory")
            // 应用内警告 Toast
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.WARNING, title, msg)
            }
        }
    }

    private fun checkBattery(context: Context, percent: Int, activity: Activity? = null) {
        if (!SPUtil.getNotifyBattery(context)) {
            DebugLogger.logApi(TAG, "checkBattery: individual toggle OFF")
            return
        }
        if (!debouncePass(context, "last_notify_battery")) {
            DebugLogger.logApi(TAG, "checkBattery: debounce blocked")
            return
        }
        if (percent < 0) {
            DebugLogger.logApi(TAG, "checkBattery: invalid percent=$percent")
            return
        }

        val threshold = SPUtil.getNotifyBatteryThreshold(context)
        DebugLogger.logApi(TAG, "checkBattery: percent=$percent threshold=$threshold triggered=${percent <= threshold}")
        if (percent <= threshold) {
            val title = "电量过低提醒"
            val msg = "当前电量 ${percent}%，低于阈值 ${threshold}%\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_BATTERY, title, msg)
            updateLastNotifyTime(context, "last_notify_battery")
            // 应用内警告 Toast
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.WARNING, title, msg)
            }
        }
    }

    private fun checkDeviceOnline(context: Context, isOnline: Boolean, activity: Activity? = null) {
        if (!SPUtil.getNotifyDeviceOnline(context)) {
            DebugLogger.logApi(TAG, "checkDeviceOnline: individual toggle OFF")
            return
        }
        val prevOnline = SPUtil.getNotifyPrevOnline(context)
        DebugLogger.logApi(TAG, "checkDeviceOnline: prevOnline=$prevOnline isOnline=$isOnline changed=${prevOnline != isOnline}")
        if (prevOnline == isOnline) return  // 状态未改变

        SPUtil.setNotifyPrevOnline(context, isOnline)
        // 设备上下线不做防抖，状态变化就要通知
        if (isOnline) {
            val title = "设备已上线"
            val msg = "设备已恢复连接，当前在线\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_DEVICE_ONLINE, title, msg)
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.INFO, title, msg)
            }
        } else {
            val title = "设备已离线"
            val msg = "设备连接已断开\n触发时间: ${currentTime()}"
            showNotification(context, TYPE_DEVICE_ONLINE, title, msg)
            activity?.let {
                ToastUtil.showDropToast(it, ToastStyle.WARNING, title, msg)
            }
        }
    }

    // ─── 防抖（原子操作，防止多线程并发触发重复通知） ───

    private val debounceLock = Any()

    /**
     * 防抖检查：距离上次通知是否已超过防抖间隔。
     * 仅做时间窗口判断，不更新时间戳（时间戳在通知实际发送时由 [updateLastNotifyTime] 写入）。
     */
    private fun debouncePass(context: Context, key: String): Boolean {
        synchronized(debounceLock) {
            val lastTime = SPUtil.getNotifyLastTime(context, key)
            val elapsed = System.currentTimeMillis() - lastTime
            val debounceMs = getDebounceMs(context)
            val pass = elapsed >= debounceMs
            if (!pass) {
                DebugLogger.logApi(TAG, "debouncePass: key=$key blocked, elapsed=${elapsed / 1000}s < ${debounceMs / 1000}s")
            }
            return pass
        }
    }

    /**
     * 原子更新防抖时间戳。与 [debouncePass] 使用同一把锁，消除 TOCTOU 竞态。
     */
    private fun updateLastNotifyTime(context: Context, key: String) {
        synchronized(debounceLock) {
            // 二次校验：防止在 debouncePass 和 updateLastNotifyTime 之间另一线程已更新
            val lastTime = SPUtil.getNotifyLastTime(context, key)
            val debounceMs = getDebounceMs(context)
            if (System.currentTimeMillis() - lastTime >= debounceMs) {
                SPUtil.setNotifyLastTime(context, key, System.currentTimeMillis())
            }
        }
    }

    // ─── 解析工具 ───

    /**
     * 将流量文本（如 "1.2 GB", "500 MB", "--"）解析为字节数。
     */
    private fun parseFlowToBytes(flow: String): Long? {
        val cleaned = flow.trim().uppercase().replace(",", ".")
        if (cleaned.isEmpty() || cleaned == "--" || cleaned == "-") return null

        return when {
            cleaned.endsWith("GB") -> {
                val num = cleaned.removeSuffix("GB").trim().toDoubleOrNull() ?: return null
                (num * 1_073_741_824).toLong()
            }
            cleaned.endsWith("MB") -> {
                val num = cleaned.removeSuffix("MB").trim().toDoubleOrNull() ?: return null
                (num * 1_048_576).toLong()
            }
            cleaned.endsWith("KB") -> {
                val num = cleaned.removeSuffix("KB").trim().toDoubleOrNull() ?: return null
                (num * 1024).toLong()
            }
            cleaned.endsWith("B") -> {
                cleaned.removeSuffix("B").trim().toLongOrNull()
            }
            else -> {
                // 纯数字，当作字节
                cleaned.toLongOrNull()
            }
        }
    }

    /**
     * 从温度文本（如 "65℃", "65°C", "65"）提取数值。
     */
    private fun parseTemperature(temp: String): Int? {
        val cleaned = temp.trim().uppercase()
            .replace("℃", "").replace("°C", "").replace("°", "")
        if (cleaned.isEmpty() || cleaned == "--" || cleaned == "-") return null
        return cleaned.toDoubleOrNull()?.toInt()
    }

    /**
     * 从百分比文本（如 "45%", "45"）提取数值。
     */
    private fun parsePercentage(value: String): Int? {
        val cleaned = value.trim().replace("%", "")
        if (cleaned.isEmpty() || cleaned == "--" || cleaned == "-") return null
        return cleaned.toDoubleOrNull()?.toInt()
    }

    /**
     * 将字节格式化为可读文本（自动选择 GB/MB 单位）。
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun currentTime(): String {
        synchronized(timeFormat) {
            return timeFormat.format(Date())
        }
    }
}
