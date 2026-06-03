package com.ufi_toolswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.worker.WifiWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class BaseWifiWidget(val layoutId: Int) : AppWidgetProvider() {

    companion object {
        private const val TAG = "WifiWidget"
        const val ACTION_REFRESH = "com.ufi_toolswidget.ACTION_REFRESH"

        fun getWidgetErrorLog(context: Context): String {
            return context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                .getString("error_log", "暂无日志") ?: "暂无日志"
        }

        fun clearWidgetErrorLog(context: Context) {
            context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                .edit().putString("error_log", "").apply()
        }

        fun appendLog(context: Context, msg: String) {
            try {
                val sp = context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                val old = sp.getString("error_log", "") ?: ""
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val newLog = "[$timestamp] $msg\n$old"
                sp.edit().putString("error_log", newLog.lines().take(50).joinToString("\n")).apply()
                Log.d(TAG, msg)
            } catch (_: Exception) {}
        }

        fun renderAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, WifiWidget4x2::class.java))
            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.widget_4x2)
                performRender(context, rv)
                applyWidgetTheme(context, rv)
                setupClick(context, rv, WifiWidget4x2::class.java)
                appWidgetManager.updateAppWidget(id, rv)
            }
        }

        private fun setupClick(context: Context, rv: RemoteViews, clazz: Class<*>) {
            val intent = Intent(context, clazz).apply { action = ACTION_REFRESH }
            val pi = PendingIntent.getBroadcast(context, clazz.hashCode(), intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // 确保布局中有 id 为 widget_root 的根容器
            try { rv.setOnClickPendingIntent(R.id.widget_root, pi) } catch (_: Exception) {}
        }

        private fun performRender(context: Context, rv: RemoteViews) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val firmwareVer = sp.getString("firmware_ver", "") ?: ""
            val flow = sp.getString("flow", "--") ?: "--"
            val daily = sp.getString("daily_flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val temp = sp.getString("temp", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val appVerCode = sp.getString("app_ver_code", "") ?: ""
            val cpu = sp.getString("cpu", "--") ?: "--"
            val mem = sp.getString("mem", "--") ?: "--"
            val netType = sp.getString("net_type", "") ?: ""
            val batteryCurrent = sp.getString("battery_current", "") ?: ""
            val internalStorage = sp.getString("internal_storage", "") ?: ""
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // ===== 第一行：设备头部 + 信号 + 网络类型 + 电量 =====
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })
            // 固件版本格式：UFI v4.0.0.20260421
            safeSetText(rv, R.id.tv_version,
                if (firmwareVer.isNotEmpty()) "UFI v$firmwareVer" else "")
            // 版本代码，紧跟固件版本后
            safeSetText(rv, R.id.tv_app_ver_code,
                if (appVerCode.isNotEmpty()) "build$appVerCode" else "")

            // 信号格数矢量图标
            val signalLevel = parseSignalLevel(signal)
            val signalRes = when (signalLevel) {
                0 -> R.drawable.ic_signal_0
                1 -> R.drawable.ic_signal_1
                2 -> R.drawable.ic_signal_2
                3 -> R.drawable.ic_signal_3
                4 -> R.drawable.ic_signal_4
                5 -> R.drawable.ic_signal_5
                else -> R.drawable.ic_signal_0
            }
            safeSetImageResource(rv, R.id.iv_signal_bars, signalRes)

            // 电量矢量图标 (0-4 格)
            val batteryLevel = parseBatteryLevel(battery)
            val batteryRes = when (batteryLevel) {
                0 -> R.drawable.ic_battery_0
                1 -> R.drawable.ic_battery_1
                2 -> R.drawable.ic_battery_2
                3 -> R.drawable.ic_battery_3
                4 -> R.drawable.ic_battery_4
                else -> R.drawable.ic_battery_0
            }
            safeSetImageResource(rv, R.id.iv_battery, batteryRes)

            // 电量文本
            safeSetText(rv, R.id.tv_battery, battery)

            // 充电标志：根据电流正负判断，有正值电流显示⚡
            val isCharging = try {
                val current = batteryCurrent.replace("mA", "").replace(" ", "").toIntOrNull()
                (current != null && current > 50)
            } catch (_: Exception) { false }
            safeSetText(rv, R.id.tv_charging, if (isCharging) "⚡" else "")

            // ===== 第二行：流量大数字 =====
            safeSetText(rv, R.id.tv_daily, daily.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())

            // ===== 第三行：温度 + CPU + RAM + 信号质量 =====
            val tempClean = temp.replace("℃", "°C").replace("C", "°C")
            safeSetText(rv, R.id.tv_temp, tempClean)

            val cpuClean = cpu.replace("%", "").trim()
            safeSetText(rv, R.id.tv_cpu, "CPU ${cpuClean}%")

            val memClean = mem.replace("%", "").trim()
            safeSetText(rv, R.id.tv_mem, "RAM ${memClean}%")

            // ===== 第一行：网络类型 + 信号 dBm 已合并到第三行 =====
            val networkRes = when {
                netType.contains("5G", true) -> R.drawable.ic_network_5g
                netType.contains("4G+", true) || netType.contains("LTE+", true) -> R.drawable.ic_network_4g_plus
                netType.contains("4G", true) || netType.contains("LTE", true) -> R.drawable.ic_network_4g
                netType.contains("3G", true) || netType.contains("WCDMA", true) -> R.drawable.ic_network_3g
                netType.contains("2G", true) || netType.contains("GSM", true) -> R.drawable.ic_network_2g
                else -> R.drawable.ic_network_4g
            }
            safeSetImageResource(rv, R.id.iv_network, networkRes)
            safeSetText(rv, R.id.tv_signal_dbm, signal)

            // ===== 路由器图标：离线时切换为 ic_router_off =====
            val routerRes = if (com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context)) {
                R.drawable.ic_router_off
            } else {
                R.drawable.ic_router
            }
            safeSetImageResource(rv, R.id.iv_router, routerRes)

            // ===== 第四行：时间戳 =====
            safeSetText(rv, R.id.tv_update_time, updateTime)

            // 显隐设置
            val showFlow = sp.getBoolean("show_flow", true)
            val showTemp = sp.getBoolean("show_temp", true)
            val showModel = sp.getBoolean("show_model", true)
            val showSignal = sp.getBoolean("show_signal", true)
            val showBattery = sp.getBoolean("show_battery", true)
            val showCpu = sp.getBoolean("show_cpu", true)
            val showMem = sp.getBoolean("show_mem", true)
            val showTime = sp.getBoolean("show_time", true)

            safeSetVisibility(rv, R.id.tv_model, showModel)
            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_daily, showFlow)

            // 温度
            safeSetVisibility(rv, R.id.tv_temp, showTemp)
            safeSetVisibility(rv, R.id.iv_temp, showTemp)

            // 信号
            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.iv_network, showSignal)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)
            safeSetVisibility(rv, R.id.iv_antenna, showSignal)

            // 电池
            safeSetVisibility(rv, R.id.iv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_charging, showBattery)

            // CPU
            safeSetVisibility(rv, R.id.tv_cpu, showCpu)
            safeSetVisibility(rv, R.id.iv_cpu, showCpu)

            // 内存
            safeSetVisibility(rv, R.id.tv_mem, showMem)
            safeSetVisibility(rv, R.id.iv_chip, showMem)

            // 更新时间
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /** 从 RSRP dBm 信号值推算 1-5 格信号强度 */
        private fun parseSignalLevel(signal: String): Int {
            return try {
                val rssi = signal.replace("dBm", "").trim().toIntOrNull() ?: 0
                // RSRP 是负数；>=0 视为无效（如 "null"/"--"）
                if (rssi >= 0) return 0
                when {
                    rssi > -85  -> 5   // 非常好
                    rssi >= -95 -> 4   // 良好
                    rssi >= -105 -> 3  // 一般 / 中等
                    rssi >= -115 -> 2  // 较差
                    else         -> 1   // 极差
                }
            } catch (_: Exception) { 0 }
        }

        private fun safeSetText(rv: RemoteViews, id: Int, text: String) {
            try { rv.setTextViewText(id, text) } catch (_: Exception) {}
        }

        private fun safeSetVisibility(rv: RemoteViews, id: Int, visible: Boolean) {
            try { rv.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE) } catch (_: Exception) {}
        }

        private fun safeSetImageResource(rv: RemoteViews, id: Int, resId: Int) {
            try { rv.setImageViewResource(id, resId) } catch (_: Exception) {}
        }

        private fun safeSetTextColor(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setTextColor(id, color) } catch (_: Exception) {}
        }

        private fun safeSetImageViewTint(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setInt(id, "setColorFilter", color) } catch (_: Exception) {}
        }

        /** 根据主题模式设置小组件背景和文字颜色（背景跟随主界面 pageBg，颜色两级制简洁统一） */
        private fun applyWidgetTheme(context: Context, rv: RemoteViews) {
            val isDark = SPUtil.isWidgetDark(context)
            val themeId = SPUtil.getSp(context).getInt("color_theme", 0)
            val palette = ThemeColors.getById(context, themeId)

            // 根据浅/深选择色值 — 仅用两级文字色
            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textPrimary = if (isDark) palette.textPrimaryDark else palette.textPrimaryLight
            val textSecondary = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            // 根容器背景 → 使用主界面背景色（与主界面主题一致）
            rv.setInt(R.id.widget_root, "setBackgroundColor", pageBg)

            // ── 文字色（两级制：主色 + 副色）──
            // 主色：核心数据
            safeSetTextColor(rv, R.id.tv_model, textPrimary)
            safeSetTextColor(rv, R.id.tv_battery, textPrimary)
            safeSetTextColor(rv, R.id.tv_daily, textPrimary)
            safeSetTextColor(rv, R.id.tv_flow, textPrimary)
            safeSetTextColor(rv, R.id.tv_cpu, textPrimary)
            safeSetTextColor(rv, R.id.tv_daily_label, textPrimary)
            safeSetTextColor(rv, R.id.tv_flow_label, textPrimary)

            // 副色：辅助信息
            safeSetTextColor(rv, R.id.tv_version, textSecondary)
            safeSetTextColor(rv, R.id.tv_app_ver_code, textSecondary)
            safeSetTextColor(rv, R.id.tv_daily_unit, textSecondary)
            safeSetTextColor(rv, R.id.tv_flow_unit, textSecondary)
            safeSetTextColor(rv, R.id.tv_mem, textSecondary)
            safeSetTextColor(rv, R.id.tv_signal_dbm, textSecondary)
            safeSetTextColor(rv, R.id.tv_update_time, textSecondary)

            // 语义色（功能性，仅充电和温度保留）
            safeSetTextColor(rv, R.id.tv_charging, if (isDark) 0xFFFBBF24.toInt() else 0xFFF59E0B.toInt())
            safeSetTextColor(rv, R.id.tv_temp, if (isDark) 0xFFFB923C.toInt() else 0xFFD9480F.toInt())

            // ── 分割线 ──
            rv.setInt(R.id.divider_flow, "setBackgroundColor", divider)

            // ── 图标着色（统一用副色，不抢文字注意力）──
            val generalIconIds = listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_cpu,
                R.id.iv_chip, R.id.iv_antenna
            )
            generalIconIds.forEach { safeSetImageViewTint(rv, it, textSecondary) }

            // 温度图标用语义色
            val tempColor = if (isDark) 0xFFFB923C.toInt() else 0xFFD9480F.toInt()
            safeSetImageViewTint(rv, R.id.iv_temp, tempColor)
        }

        /** 从电量百分比推算 0-4 格电量图标等级 */
        private fun parseBatteryLevel(battery: String): Int {
            return try {
                val pct = battery.replace("%", "").trim().toIntOrNull() ?: 0
                when {
                    pct >= 90 -> 4
                    pct >= 70 -> 3
                    pct >= 40 -> 2
                    pct >= 15 -> 1
                    else -> 0
                }
            } catch (_: Exception) { 0 }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val rv = RemoteViews(context.packageName, layoutId)
            performRender(context, rv)
            applyWidgetTheme(context, rv)
            setupClick(context, rv, this::class.java)
            manager.updateAppWidget(id, rv)
        }
        triggerWorker(context)
    }

    /** 小组件尺寸变化时自动重绘以适应新尺寸 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        val newWidth = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 248)
        appendLog(context, "尺寸变化 → ${newWidth}dp，重新渲染")
        val rv = RemoteViews(context.packageName, layoutId)
        performRender(context, rv)
        applyWidgetTheme(context, rv)
        setupClick(context, rv, this::class.java)
        manager.updateAppWidget(appWidgetId, rv)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            appendLog(context, "点击刷新触发")
            triggerWorker(context)
        }
    }

    private fun triggerWorker(context: Context) {
        try {
            // 手动刷新 → 重置失败状态，允许 Worker 重新尝试
            WifiWorker.resetFailureState(context)
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WifiWorker>().build())
        } catch (_: Exception) {}
    }
}

class WifiWidget4x2 : BaseWifiWidget(R.layout.widget_4x2)
