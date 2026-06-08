package com.ufi_toolswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.WidgetBitmapCache
import com.ufi_toolswidget.worker.WifiWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class BaseWifiWidget(val layoutId: Int) : AppWidgetProvider() {

    companion object {
        private const val TAG = "WifiWidget"
        private const val RENDER_DEDUP_MS = 2000L // 2 秒内重复调用直接跳过
        const val ACTION_REFRESH = "com.ufi_toolswidget.ACTION_REFRESH"

        /** 上次 renderAllWidgets 完成时间戳，用于去重 Worker 与 MainActivity 双重渲染 */
        @Volatile private var lastRenderTime = 0L

        /** 上次渲染的数据指纹，数据未变时跳过整次渲染（performRender + applyWidgetTheme） */
        @Volatile private var lastDataHash: Int = 0

        /**
         * 获取或创建背景 Bitmap（委托 WidgetBitmapCache，分离纯色/自定义图缓存）。
         */
        private fun getOrCreateBgBitmap(context: Context, uri: String, color: Int, cornerRadiusDp: Float): Bitmap? {
            return if (uri.isNotBlank()) {
                WidgetBitmapCache.getOrCreateImageBitmap(context, uri, cornerRadiusDp)
            } else {
                WidgetBitmapCache.getOrCreateSolidBitmap(context, color, cornerRadiusDp)
            }
        }

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
            } catch (_: Exception) {
                // SharedPreferences 写入失败（磁盘满/权限），调试日志丢失可接受
            }
        }

        fun renderAllWidgets(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (now - lastRenderTime < RENDER_DEDUP_MS && !force) {
                return // 短时间内已渲染过（Worker 和 MainActivity 双重触发去重）
            }

            val currentHash = computeDataHash(context)
            if (!force && currentHash != 0 && currentHash == lastDataHash) {
                return // SP 数据未变，跳过整次渲染（performRender + applyWidgetTheme）
            }
            lastDataHash = currentHash
            lastRenderTime = now

            val appWidgetManager = AppWidgetManager.getInstance(context)

            // ── 4×2 小组件 ──
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, WifiWidget4x2::class.java))
            if (ids.isNotEmpty()) {
                val rv = RemoteViews(context.packageName, R.layout.widget_4x2)
                performRender(context, rv)
                applyWidgetTheme(context, rv)
                setupClick(context, rv, WifiWidget4x2::class.java)
                appWidgetManager.updateAppWidget(ids, rv)
                applyWidgetLabel(context, appWidgetManager, ids)
            }

            // ── 2×1 迷你小组件（已禁用）──
            // val ids2x1 = appWidgetManager.getAppWidgetIds(ComponentName(context, WifiWidget2x1::class.java))
            // if (ids2x1.isNotEmpty()) {
            //     val rv2x1 = RemoteViews(context.packageName, R.layout.widget_2x1)
            //     performRender2x1(context, rv2x1)
            //     applyWidgetTheme2x1(context, rv2x1)
            //     setupClick(context, rv2x1, WifiWidget2x1::class.java)
            //     appWidgetManager.updateAppWidget(ids2x1, rv2x1)
            // }

            // ── 4×1 条形小组件（已禁用）──
            // val ids4x1 = appWidgetManager.getAppWidgetIds(ComponentName(context, WifiWidget4x1::class.java))
            // if (ids4x1.isNotEmpty()) {
            //     val rv4x1 = RemoteViews(context.packageName, R.layout.widget_4x1)
            //     performRender4x1(context, rv4x1)
            //     applyWidgetTheme4x1(context, rv4x1)
            //     setupClick(context, rv4x1, WifiWidget4x1::class.java)
            //     appWidgetManager.updateAppWidget(ids4x1, rv4x1)
            // }

            // ── 4×4 详情小组件（已禁用）──
            // val ids4x4 = appWidgetManager.getAppWidgetIds(ComponentName(context, WifiWidget4x4::class.java))
            // if (ids4x4.isNotEmpty()) {
            //     val rv4x4 = RemoteViews(context.packageName, R.layout.widget_4x4)
            //     performRender4x4(context, rv4x4)
            //     applyWidgetTheme4x4(context, rv4x4)
            //     setupClick(context, rv4x4, WifiWidget4x4::class.java)
            //     appWidgetManager.updateAppWidget(ids4x4, rv4x4)
            // }
        }

        /**
         * 计算 SP 数据指纹：覆盖影响小组件渲染的所有字段（数据 + 外观设置）。
         * 数据未变时 hash 相同，可跳过 performRender + applyWidgetTheme。
         */
        private fun computeDataHash(context: Context): Int {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            var h = 17
            // 数据字段
            h = 31 * h + (sp.getString("device_model", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("model", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("firmware_ver", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("flow", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("daily_flow", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("signal", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("temp", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("battery", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("cpu", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("mem", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("net_type", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("app_ver_code", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("battery_current", "") ?: "").hashCode()
            h = 31 * h + (sp.getString("update_time", "") ?: "").hashCode()
            // 显隐设置
            h = 31 * h + sp.getBoolean("show_flow", true).hashCode()
            h = 31 * h + sp.getBoolean("show_signal", true).hashCode()
            h = 31 * h + sp.getBoolean("show_temp", true).hashCode()
            h = 31 * h + sp.getBoolean("show_cpu", true).hashCode()
            h = 31 * h + sp.getBoolean("show_model", true).hashCode()
            h = 31 * h + sp.getBoolean("show_time", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery", true).hashCode()
            h = 31 * h + sp.getBoolean("show_mem", true).hashCode()
            // 外观设置（主题/颜色/背景/透明度变更时必须触发重渲染）
            h = 31 * h + (sp.getString("widget_theme", "") ?: "").hashCode()
            h = 31 * h + sp.getBoolean("widget_follow_app_theme", true).hashCode()
            h = 31 * h + sp.getInt("color_theme", 0)
            h = 31 * h + sp.getInt("widget_color_theme", 0)
            h = 31 * h + sp.getInt("widget_custom_accent_light", 0)
            h = 31 * h + sp.getInt("widget_custom_accent_dark", 0)
            h = 31 * h + (sp.getString("widget_bg_image_uri", "") ?: "").hashCode()
            h = 31 * h + sp.getInt("widget_bg_opacity", 100)
            h = 31 * h + sp.getBoolean("widget_clip_to_outline", false).hashCode()
            // Android 12+ 动态配色开关（影响调色板选择，变更时必须重渲染）
            h = 31 * h + sp.getBoolean("widget_dynamic_color", true).hashCode()
            h = 31 * h + sp.getInt("widget_dynamic_contrast", 1)
            h = 31 * h + sp.getBoolean("widget_dynamic_advanced", false).hashCode()
            h = 31 * h + sp.getInt("widget_dynamic_color_source", 0)
            if (sp.getBoolean("widget_dynamic_advanced", false)) {
                h = 31 * h + sp.getInt("dyn_adv_light_bg", 97)
                h = 31 * h + sp.getInt("dyn_adv_light_txt", 12)
                h = 31 * h + sp.getInt("dyn_adv_dark_bg", 8)
                h = 31 * h + sp.getInt("dyn_adv_dark_txt", 90)
                h = 31 * h + sp.getInt("dyn_adv_sat_boost", 100)
            }
            // 各尺寸独立显隐设置
            h = 31 * h + sp.getBoolean("show_signal_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_network_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_model_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_signal_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_temp_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_time_4x1", true).hashCode()
            // 各尺寸独立字体大小
            h = 31 * h + sp.getInt("font_size_2x1", 9)
            h = 31 * h + sp.getInt("font_size_4x1", 9)
            // Worker 状态（影响 error overlay 显隐）
            h = 31 * h + com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context).hashCode()
            return h
        }

        internal fun setupClick(context: Context, rv: RemoteViews, clazz: Class<*>) {
            val intent = Intent(context, clazz).apply { action = ACTION_REFRESH }
            val pi = PendingIntent.getBroadcast(context, clazz.hashCode(), intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // 确保布局中有 id 为 widget_root 的根容器
            try { rv.setOnClickPendingIntent(R.id.widget_root, pi) } catch (_: Exception) {
                // RemoteViews: 布局中无对应 id 时抛异常，静默吞掉
            }
        }

        private fun performRender(context: Context, rv: RemoteViews) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val stopped = com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context)

            // ===== 错误状态：隐藏数据区，全屏显示连接失败提示 =====
            safeSetVisibility(rv, R.id.widget_content, !stopped)
            safeSetVisibility(rv, R.id.widget_error_overlay, stopped)
            if (stopped) {
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_router_off)
                return
            }

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
            } catch (_: Exception) {
                // 电流值解析失败（非预期格式），默认不显示充电标志
                false
            }
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

        /** 4x1 条形布局数据渲染 */
        private fun performRender4x1(context: Context, rv: RemoteViews) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val stopped = com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context)

            // 错误状态：隐藏数据区，显示连接失败
            safeSetVisibility(rv, R.id.widget_content, !stopped)
            safeSetVisibility(rv, R.id.widget_error_overlay, stopped)
            if (stopped) {
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_router_off)
                return
            }

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val signal = sp.getString("signal", "--") ?: "--"
            val temp = sp.getString("temp", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = sp.getString("net_type", "") ?: ""
            val batteryCurrent = sp.getString("battery_current", "") ?: ""
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // 路由器 + 型号
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })

            // 信号格数
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

            // 网络类型
            val networkRes = when {
                netType.contains("5G", true) -> R.drawable.ic_network_5g
                netType.contains("4G+", true) || netType.contains("LTE+", true) -> R.drawable.ic_network_4g_plus
                netType.contains("4G", true) || netType.contains("LTE", true) -> R.drawable.ic_network_4g
                netType.contains("3G", true) || netType.contains("WCDMA", true) -> R.drawable.ic_network_3g
                netType.contains("2G", true) || netType.contains("GSM", true) -> R.drawable.ic_network_2g
                else -> R.drawable.ic_network_4g
            }
            safeSetImageResource(rv, R.id.iv_network, networkRes)

            // 电量图标
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
            safeSetText(rv, R.id.tv_battery, battery)

            // 充电标志
            val isCharging = try {
                val current = batteryCurrent.replace("mA", "").replace(" ", "").toIntOrNull()
                (current != null && current > 50)
            } catch (_: Exception) { false }
            safeSetText(rv, R.id.tv_charging, if (isCharging) "⚡" else "")

            // 温度
            val tempClean = temp.replace("℃", "°C").replace("C", "°C")
            safeSetText(rv, R.id.tv_temp, tempClean)

            // 路由器图标
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)

            // 更新时间
            safeSetText(rv, R.id.tv_update_time, updateTime)

            // 4×1 独立显隐设置
            val showModel = sp.getBoolean("show_model_4x1", true)
            val showSignal = sp.getBoolean("show_signal_4x1", true)
            val showTemp = sp.getBoolean("show_temp_4x1", true)
            val showBattery = sp.getBoolean("show_battery_4x1", true)
            val showTime = sp.getBoolean("show_time_4x1", true)

            safeSetVisibility(rv, R.id.tv_model, showModel)
            safeSetVisibility(rv, R.id.iv_router, showModel)
            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.iv_network, showSignal)
            safeSetVisibility(rv, R.id.iv_temp, showTemp)
            safeSetVisibility(rv, R.id.tv_temp, showTemp)
            safeSetVisibility(rv, R.id.iv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_charging, showBattery)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /** 4x1 条形布局主题着色 */
        private fun applyWidgetTheme4x1(context: Context, rv: RemoteViews) {
            val isDark = SPUtil.isWidgetDark(context)

            val themeId = if (SPUtil.getWidgetFollowAppTheme(context)) {
                SPUtil.getColorThemeIndex(context)
            } else {
                SPUtil.getWidgetColorThemeIndex(context)
            }

            val palette = ThemeColors.getById(context, themeId, isWidget = true)

            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textColor = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            val shouldClip = SPUtil.getWidgetClipToOutline(context)
            val cornerRadiusDp = if (shouldClip) 10f else 0f

            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                           else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                               else R.drawable.bg_widget_mask_rect

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip)
                } catch (_: Exception) {
                    // setClipToOutline 反射调用可能在某些设备上失败，静默忽略
                }
            }

            val bgImageUri = SPUtil.getWidgetBgImageUri(context)
            val opacity = SPUtil.getWidgetBgOpacity(context)
            val alpha = (opacity / 100f * 255).toInt()

            val bgBitmap = getOrCreateBgBitmap(context, bgImageUri, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }

            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)

            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)

            // ── 文字色 ──
            for (id in listOf(
                R.id.tv_model,
                R.id.tv_battery, R.id.tv_charging,
                R.id.tv_temp, R.id.tv_update_time
            )) {
                safeSetTextColor(rv, id, textColor)
            }

            // ── 图标着色 ──
            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_temp
            )) {
                safeSetImageViewTint(rv, id, textColor)
            }

            // 4×1 独立字体大小
            val fontSize4x1 = SPUtil.getFontSize4x1(context).toFloat()
            val defaultBase4x1 = 9f
            if (fontSize4x1 != defaultBase4x1) {
                val ratio = fontSize4x1 / defaultBase4x1
                safeSetTextSize(rv, R.id.tv_model, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_battery, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_charging, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_temp, 9f * ratio)
                safeSetTextSize(rv, R.id.tv_update_time, 8f * ratio)
            }

            // ── 错误覆盖层着色 ──
            safeSetImageViewTint(rv, R.id.widget_error_icon, textColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
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
            } catch (_: Exception) {
                // 信号解析失败（非数字字符串如 "--"），返回 0 格
                0
            }
        }

        /** 遥控方法：setTextViewText，捕获 RemoteViews 跨进程异常 */
        private fun safeSetText(rv: RemoteViews, id: Int, text: String) {
            try { rv.setTextViewText(id, text) } catch (_: Exception) {
                // RemoteViews: 跨进程通信，id 不存在时可能抛异常，静默吞掉
            }
        }

        /** 遥控方法：setViewVisibility，捕获 RemoteViews 跨进程异常 */
        private fun safeSetVisibility(rv: RemoteViews, id: Int, visible: Boolean) {
            try { rv.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE) } catch (_: Exception) {
                // RemoteViews: 跨进程通信，id 不存在时可能抛异常，静默吞掉
            }
        }

        /** 遥控方法：setImageViewResource，捕获 RemoteViews 跨进程异常 */
        private fun safeSetImageResource(rv: RemoteViews, id: Int, resId: Int) {
            try { rv.setImageViewResource(id, resId) } catch (_: Exception) {
                // RemoteViews: 跨进程通信，id 不存在时可能抛异常，静默吞掉
            }
        }

        /** 遥控方法：setTextColor，捕获 RemoteViews 跨进程异常 */
        private fun safeSetTextColor(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setTextColor(id, color) } catch (_: Exception) {
                // RemoteViews: 跨进程通信，id 不存在时可能抛异常，静默吞掉
            }
        }

        /** 遥控方法：setTextViewTextSize，捕获 RemoteViews 跨进程异常 */
        private fun safeSetTextSize(rv: RemoteViews, id: Int, sizeSp: Float) {
            try { rv.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp) } catch (_: Exception) {
                // RemoteViews: setTextViewTextSize 可能不被所有 Launcher 支持
            }
        }

        /** 遥控方法：setColorFilter，捕获 RemoteViews 跨进程异常 */
        private fun safeSetImageViewTint(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setInt(id, "setColorFilter", color) } catch (_: Exception) {
                // RemoteViews: 跨进程通信，反射 setColorFilter 可能失败，静默吞掉
            }
        }

        /** 根据主题模式设置小组件背景和文字颜色（支持自定义背景图 + 透明度） */
        private fun applyWidgetTheme(context: Context, rv: RemoteViews) {
            // ═══ 动态配色独立路径 ═══
            // 开启动态配色后，小组件颜色完全由壁纸色调 + 系统暗色模式决定，
            // 不受"跟随应用主题"、"小组件配色"等设置影响
            if (isWidgetDynamicActive(context)) {
                applyWidgetThemeDynamic(context, rv)
                return
            }

            val isDark = SPUtil.isWidgetDark(context)
            
            // 决定颜色主题：跟随应用或使用独立设置
            val themeId = if (SPUtil.getWidgetFollowAppTheme(context)) {
                SPUtil.getColorThemeIndex(context)
            } else {
                SPUtil.getWidgetColorThemeIndex(context)
            }

            val palette = ThemeColors.getById(context, themeId, isWidget = true)

            // 根据浅/深选择色值
            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textColor = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            // ── 圆角裁剪兜底开关（用户可配置，部分国产桌面自动加圆角时可关闭）──
            // 关闭后：Bitmap 直角 + clipToOutline 关闭；开启后：全链路 10dp 圆角
            val shouldClip = SPUtil.getWidgetClipToOutline(context)
            val cornerRadiusDp = if (shouldClip) 10f else 0f

            // 根据开关选择描边/兜底 drawable（圆角 ←→ 矩形）
            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                           else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                               else R.drawable.bg_widget_mask_rect

            // clipToOutline：根布局 XML 已固定 bg_widget_mask_transparent 提供圆角轮廓
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip)
                } catch (_: Exception) {
                    // setClipToOutline 反射调用可能在某些设备上失败，静默忽略
                }
            }

            val bgImageUri = SPUtil.getWidgetBgImageUri(context)
            val opacity = SPUtil.getWidgetBgOpacity(context)
            val alpha = (opacity / 100f * 255).toInt()

            // ── 背景图生成（Bitmap 缓存：圆角半径跟随开关）──
            val bgBitmap = getOrCreateBgBitmap(context, bgImageUri, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                // 兜底：Bitmap 创建失败时使用 drawable 着色
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }
            
            // 应用透明度（作用于背景层）
            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)
            
            // 描边层
            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)

            // ── 文字色（统一）──
            for (id in listOf(
                R.id.tv_model, R.id.tv_version, R.id.tv_app_ver_code,
                R.id.tv_battery, R.id.tv_charging,
                R.id.tv_daily, R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_flow, R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_temp, R.id.tv_cpu, R.id.tv_mem,
                R.id.tv_signal_dbm, R.id.tv_update_time
            )) {
                safeSetTextColor(rv, id, textColor)
            }

            // ── 分割线 ──
            rv.setInt(R.id.divider_flow, "setBackgroundColor", divider)

            // ── 图标着色（统一）──
            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_cpu, R.id.iv_chip,
                R.id.iv_antenna, R.id.iv_temp
            )) {
                safeSetImageViewTint(rv, id, textColor)
            }

            // ── 错误覆盖层着色 ──
            safeSetImageViewTint(rv, R.id.widget_error_icon, textColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
            safeSetTextColor(rv, R.id.widget_error_hint, textColor)
        }

        // ═══════════════════════════════════════════════════════════
        //  动态配色独立渲染路径（API 31+）
        //  当动态取色开启时，小组件颜色完全由壁纸主色 + 系统暗色模式决定，
        //  不受"跟随应用主题"、"显示模式"、"小组件配色"等设置影响
        // ═══════════════════════════════════════════════════════════

        /** 判断当前设备是否启用了小组件动态配色（API 31+ 且动态取色开关开启） */
        private fun isWidgetDynamicActive(context: Context): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ThemeColors.supportsDynamicColors()
                && SPUtil.getWidgetDynamicColor(context)
        }

        /** 直接读取系统暗色模式（不受应用/小组件主题设置影响） */
        private fun isSystemDarkMode(context: Context): Boolean {
            return ThemeColors.isSystemDark(context)
        }

        /**
         * 4×2 动态配色独立着色：壁纸主色 + 系统暗色模式。
         * 文字色使用 textPrimary 保证可读性，核心数据值使用 dataHighlight
         * 增强视觉层次感。
         */
        private fun applyWidgetThemeDynamic(context: Context, rv: RemoteViews) {
            val isDark = isSystemDarkMode(context)
            val palette = ThemeColors.buildDynamicPalette(context)

            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            // 动态配色使用 textPrimary（高对比度），而非 textSecondary
            val textColor = if (isDark) palette.textPrimaryDark else palette.textPrimaryLight
            // 核心数据值使用 dataHighlight 增强辨识度
            val dataColor = if (isDark) palette.dataHighlightDark else palette.dataHighlightLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            // ── 圆角裁剪 ──
            val shouldClip = SPUtil.getWidgetClipToOutline(context)
            val cornerRadiusDp = if (shouldClip) 10f else 0f
            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                           else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                               else R.drawable.bg_widget_mask_rect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip) } catch (_: Exception) {}
            }

            // ── 背景图 ──
            val bgImageUri = SPUtil.getWidgetBgImageUri(context)
            val opacity = SPUtil.getWidgetBgOpacity(context)
            val alpha = (opacity / 100f * 255).toInt()
            val bgBitmap = getOrCreateBgBitmap(context, bgImageUri, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }
            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)

            // ── 描边层 ──
            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)

            // ── 文字色：全部使用 dataHighlight 体现动态取色 ──
            // 标签类文字
            for (id in listOf(
                R.id.tv_model, R.id.tv_version, R.id.tv_app_ver_code,
                R.id.tv_charging,
                R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_update_time
            )) {
                safeSetTextColor(rv, id, dataColor)
            }
            // 核心数据值（流量、温度、CPU、内存、电量、信号）
            for (id in listOf(
                R.id.tv_daily, R.id.tv_flow,
                R.id.tv_temp, R.id.tv_cpu, R.id.tv_mem,
                R.id.tv_battery, R.id.tv_signal_dbm
            )) {
                safeSetTextColor(rv, id, dataColor)
            }

            // ── 分割线 ──
            rv.setInt(R.id.divider_flow, "setBackgroundColor", divider)

            // ── 图标着色（使用 dataHighlight 与数据值保持一致，体现动态取色）──
            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_cpu, R.id.iv_chip,
                R.id.iv_antenna, R.id.iv_temp
            )) {
                safeSetImageViewTint(rv, id, dataColor)
            }

            // ── 错误覆盖层着色（图标用 dataHighlight，文字用 textPrimary）──
            safeSetImageViewTint(rv, R.id.widget_error_icon, dataColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
            safeSetTextColor(rv, R.id.widget_error_hint, textColor)
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
            } catch (_: Exception) {
                // 电量解析失败（非数字字符串如 "--"），返回 0 格
                0
            }
        }

        /** 2×1 迷你小组件：仅信号 + 网络类型 + 电量 */
        internal fun performRender2x1(context: Context, rv: RemoteViews) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val stopped = com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context)

            // 错误状态：隐藏数据区，显示错误图标
            safeSetVisibility(rv, R.id.widget_content, !stopped)
            safeSetVisibility(rv, R.id.widget_error_overlay, stopped)
            if (stopped) {
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_router_off)
                return
            }

            val signal = sp.getString("signal", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = sp.getString("net_type", "") ?: ""
            val batteryCurrent = sp.getString("battery_current", "") ?: ""

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
            } catch (_: Exception) {
                false
            }
            safeSetText(rv, R.id.tv_charging, if (isCharging) "⚡" else "")

            // 网络类型图标
            val networkRes = when {
                netType.contains("5G", true) -> R.drawable.ic_network_5g
                netType.contains("4G+", true) || netType.contains("LTE+", true) -> R.drawable.ic_network_4g_plus
                netType.contains("4G", true) || netType.contains("LTE", true) -> R.drawable.ic_network_4g
                netType.contains("3G", true) || netType.contains("WCDMA", true) -> R.drawable.ic_network_3g
                netType.contains("2G", true) || netType.contains("GSM", true) -> R.drawable.ic_network_2g
                else -> R.drawable.ic_network_4g
            }
            safeSetImageResource(rv, R.id.iv_network, networkRes)

            // 2×1 独立显隐设置
            val showSignal2x1 = sp.getBoolean("show_signal_2x1", true)
            val showBattery2x1 = sp.getBoolean("show_battery_2x1", true)
            val showNetwork2x1 = sp.getBoolean("show_network_2x1", true)

            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal2x1)
            safeSetVisibility(rv, R.id.iv_network, showNetwork2x1)
            safeSetVisibility(rv, R.id.iv_battery, showBattery2x1)
            safeSetVisibility(rv, R.id.tv_battery, showBattery2x1)
            safeSetVisibility(rv, R.id.tv_charging, showBattery2x1)
        }

        /** 2×1 迷你小组件主题着色 */
        internal fun applyWidgetTheme2x1(context: Context, rv: RemoteViews) {
            val isDark = SPUtil.isWidgetDark(context)

            // 决定颜色主题：跟随应用或使用独立设置
            val themeId = if (SPUtil.getWidgetFollowAppTheme(context)) {
                SPUtil.getColorThemeIndex(context)
            } else {
                SPUtil.getWidgetColorThemeIndex(context)
            }

            val palette = ThemeColors.getById(context, themeId, isWidget = true)

            // 根据浅/深选择色值
            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textColor = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            // 圆角裁剪兜底开关
            val shouldClip = SPUtil.getWidgetClipToOutline(context)
            val cornerRadiusDp = if (shouldClip) 10f else 0f

            // 根据开关选择描边/兜底 drawable
            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                           else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                               else R.drawable.bg_widget_mask_rect

            // clipToOutline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip)
                } catch (_: Exception) {
                    // setClipToOutline 反射调用可能在某些设备上失败
                }
            }

            val bgImageUri = SPUtil.getWidgetBgImageUri(context)
            val opacity = SPUtil.getWidgetBgOpacity(context)
            val alpha = (opacity / 100f * 255).toInt()

            // 背景图生成
            val bgBitmap = getOrCreateBgBitmap(context, bgImageUri, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }

            // 应用透明度
            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)

            // 描边层
            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)

            // 文字色
            for (id in listOf(R.id.tv_battery, R.id.tv_charging)) {
                safeSetTextColor(rv, id, textColor)
            }

            // 图标着色
            for (id in listOf(R.id.iv_signal_bars, R.id.iv_network, R.id.iv_battery)) {
                safeSetImageViewTint(rv, id, textColor)
            }

            // 2×1 独立字体大小
            val fontSize2x1 = SPUtil.getFontSize2x1(context).toFloat()
            val defaultBase2x1 = 9f
            if (fontSize2x1 != defaultBase2x1) {
                val ratio = fontSize2x1 / defaultBase2x1
                safeSetTextSize(rv, R.id.tv_battery, (9f * ratio))
                safeSetTextSize(rv, R.id.tv_charging, (9f * ratio))
            }

            // 错误覆盖层着色
            safeSetImageViewTint(rv, R.id.widget_error_icon, textColor)
        }

        /** 4×4 详情小组件：完整数据面板渲染 */
        internal fun performRender4x4(context: Context, rv: RemoteViews) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val stopped = com.ufi_toolswidget.worker.WifiWorker.isWorkerStopped(context)

            // ===== 错误状态：隐藏数据区，全屏显示连接失败提示 =====
            safeSetVisibility(rv, R.id.widget_content, !stopped)
            safeSetVisibility(rv, R.id.widget_error_overlay, stopped)
            if (stopped) {
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_router_off)
                return
            }

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
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // ===== Row 1: 设备头部 =====
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })
            safeSetText(rv, R.id.tv_version,
                if (firmwareVer.isNotEmpty()) "UFI v$firmwareVer" else "")
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

            // 网络类型图标
            val networkRes = when {
                netType.contains("5G", true) -> R.drawable.ic_network_5g
                netType.contains("4G+", true) || netType.contains("LTE+", true) -> R.drawable.ic_network_4g_plus
                netType.contains("4G", true) || netType.contains("LTE", true) -> R.drawable.ic_network_4g
                netType.contains("3G", true) || netType.contains("WCDMA", true) -> R.drawable.ic_network_3g
                netType.contains("2G", true) || netType.contains("GSM", true) -> R.drawable.ic_network_2g
                else -> R.drawable.ic_network_4g
            }
            safeSetImageResource(rv, R.id.iv_network, networkRes)

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

            // 充电标志
            val isCharging = try {
                val current = batteryCurrent.replace("mA", "").replace(" ", "").toIntOrNull()
                (current != null && current > 50)
            } catch (_: Exception) {
                false
            }
            safeSetText(rv, R.id.tv_charging, if (isCharging) "⚡" else "")

            // ===== Row 3: 流量大数字 =====
            safeSetText(rv, R.id.tv_daily, daily.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())

            // ===== Row 4: 温度 + CPU + RAM =====
            val tempClean = temp.replace("℃", "°C").replace("C", "°C")
            safeSetText(rv, R.id.tv_temp, tempClean)

            val cpuClean = cpu.replace("%", "").trim()
            safeSetText(rv, R.id.tv_cpu, "CPU ${cpuClean}%")

            val memClean = mem.replace("%", "").trim()
            safeSetText(rv, R.id.tv_mem, "RAM ${memClean}%")

            // ===== Row 5: 信号 dBm =====
            safeSetText(rv, R.id.tv_signal_dbm, signal)

            // ===== 路由器图标：离线时切换为 ic_router_off =====
            val routerRes = if (stopped) R.drawable.ic_router_off else R.drawable.ic_router
            safeSetImageResource(rv, R.id.iv_router, routerRes)

            // ===== Row 6: 时间戳 =====
            safeSetText(rv, R.id.tv_update_time, updateTime)

            // ===== 显隐设置 =====
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
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_daily_label, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.tv_daily_unit, showFlow)

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

        /** 4×4 详情小组件主题着色 */
        internal fun applyWidgetTheme4x4(context: Context, rv: RemoteViews) {
            val isDark = SPUtil.isWidgetDark(context)

            // 决定颜色主题：跟随应用或使用独立设置
            val themeId = if (SPUtil.getWidgetFollowAppTheme(context)) {
                SPUtil.getColorThemeIndex(context)
            } else {
                SPUtil.getWidgetColorThemeIndex(context)
            }

            val palette = ThemeColors.getById(context, themeId, isWidget = true)

            // 根据浅/深选择色值
            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textColor = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            // 圆角裁剪兜底开关
            val shouldClip = SPUtil.getWidgetClipToOutline(context)
            val cornerRadiusDp = if (shouldClip) 10f else 0f

            // 根据开关选择描边/兜底 drawable
            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                           else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                               else R.drawable.bg_widget_mask_rect

            // clipToOutline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip)
                } catch (_: Exception) {
                    // setClipToOutline 反射调用可能在某些设备上失败
                }
            }

            val bgImageUri = SPUtil.getWidgetBgImageUri(context)
            val opacity = SPUtil.getWidgetBgOpacity(context)
            val alpha = (opacity / 100f * 255).toInt()

            // 背景图生成
            val bgBitmap = getOrCreateBgBitmap(context, bgImageUri, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }

            // 应用透明度
            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)

            // 描边层
            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)

            // 文字色（统一）
            for (id in listOf(
                R.id.tv_model, R.id.tv_version, R.id.tv_app_ver_code,
                R.id.tv_battery, R.id.tv_charging,
                R.id.tv_daily, R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_flow, R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_temp, R.id.tv_cpu, R.id.tv_mem,
                R.id.tv_signal_dbm, R.id.tv_update_time
            )) {
                safeSetTextColor(rv, id, textColor)
            }

            // 分割线
            rv.setInt(R.id.divider_flow, "setBackgroundColor", divider)

            // 图标着色（统一）
            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_cpu, R.id.iv_chip,
                R.id.iv_antenna, R.id.iv_temp
            )) {
                safeSetImageViewTint(rv, id, textColor)
            }

            // 错误覆盖层着色
            safeSetImageViewTint(rv, R.id.widget_error_icon, textColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
            safeSetTextColor(rv, R.id.widget_error_hint, textColor)
        }

        /**
         * 确保小组件周期性后台刷新任务已注册。
         * 使用 KEEP 策略：如果已有定时任务（被主 Activity 注册过）则不动，
         * 如果没有则创建。这样小组件完全独立于主应用进程运行。
         */
        fun ensurePeriodicWorker(context: Context) {
            try {
                val minutes = SPUtil.getRefreshInterval(context)
                if (minutes <= 0) return
                val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "wifi_crawl",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            } catch (_: Exception) {
                Log.w(TAG, "ensurePeriodicWorker failed")
            }
        }

        /**
         * 应用小组件标签隐藏设置（API 31+）。
         * 将桌面显示的 widget 名称替换为空格，实现视觉上的隐藏效果。
         */
        fun applyWidgetLabel(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val hideLabel = SPUtil.getWidgetHideLabel(context)
            val label = if (hideLabel) " " else "UFI 状态"
            for (id in ids) {
                try {
                    val options = Bundle()
                    options.putString("android.appwidget.label", label)
                    manager.updateAppWidgetOptions(id, options)
                } catch (_: Exception) {
                    // 部分桌面可能不支持 OPTION_APPWIDGET_LABEL
                }
            }
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
        // 应用标签隐藏（API 31+ 替换名称显示为空格）
        ensurePeriodicWorker(context)
        applyWidgetLabel(context, manager, ids)
        triggerWorker(context)
    }

    /** 第一个小组件被添加到桌面时确保定时刷新已注册 */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensurePeriodicWorker(context)
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

    protected fun triggerWorker(context: Context) {
        try {
            // 手动刷新 → 重置失败状态，允许 Worker 重新尝试
            WifiWorker.resetFailureState(context)
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WifiWorker>().build())
        } catch (_: Exception) {
            // WorkManager: 在小组件 onUpdate/onReceive 中调用，捕获如未初始化等异常
            Log.w(TAG, "triggerWorker failed: WorkManager may not be available")
        }
    }

}

class WifiWidget4x2 : BaseWifiWidget(R.layout.widget_4x2)

class WifiWidget2x1 : BaseWifiWidget(R.layout.widget_2x1) {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val rv = RemoteViews(context.packageName, layoutId)
            performRender2x1(context, rv)
            applyWidgetTheme2x1(context, rv)
            setupClick(context, rv, this::class.java)
            manager.updateAppWidget(id, rv)
        }
        triggerWorker(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        appendLog(context, "2×1 尺寸变化，重新渲染")
        val rv = RemoteViews(context.packageName, layoutId)
        performRender2x1(context, rv)
        applyWidgetTheme2x1(context, rv)
        setupClick(context, rv, this::class.java)
        manager.updateAppWidget(appWidgetId, rv)
    }
}

class WifiWidget4x1 : BaseWifiWidget(R.layout.widget_4x1)

class WifiWidget4x4 : BaseWifiWidget(R.layout.widget_4x4) {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val rv = RemoteViews(context.packageName, layoutId)
            performRender4x4(context, rv)
            applyWidgetTheme4x4(context, rv)
            setupClick(context, rv, this::class.java)
            manager.updateAppWidget(id, rv)
        }
        triggerWorker(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        appendLog(context, "4×4 尺寸变化，重新渲染")
        val rv = RemoteViews(context.packageName, layoutId)
        performRender4x4(context, rv)
        applyWidgetTheme4x4(context, rv)
        setupClick(context, rv, this::class.java)
        manager.updateAppWidget(appWidgetId, rv)
    }
}
