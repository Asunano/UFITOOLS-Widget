package com.ufi_toolswidget.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object SPUtil {
    fun getSp(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
    }

    fun saveData(ctx: Context, data: WifiEntity) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        getSp(ctx).edit()
            .putString("flow", data.flow)
            .putString("daily_flow", data.dailyFlow)
            .putString("signal", data.signal)
            .putString("temp", data.temp)
            .putString("battery", data.battery)
            .putString("model", data.model)
            .putString("cpu", data.cpu)
            .putString("mem", data.mem)
            .putString("net_type", data.netType)
            .putString("app_ver", data.appVer)
            .putString("app_ver_code", data.appVerCode)
            .putString("battery_current", data.batteryCurrent)
            .putString("battery_voltage", data.batteryVoltage)
            .putString("internal_storage", data.internalStorage)
            .putString("client_ip", data.clientIp)
            .putString("device_model", data.deviceModel)
            .putString("firmware_ver", data.firmwareVer)
            .putBoolean("need_token", data.needToken)
            .putString("update_time", time)
            .apply()
    }

    // 认证与配置
    fun saveRawToken(ctx: Context, token: String) = getSp(ctx).edit().putString("raw_token", token).apply()
    fun getRawToken(ctx: Context) = getSp(ctx).getString("raw_token", "admin") ?: "admin"
    fun saveAuthToken(ctx: Context, token: String) = getSp(ctx).edit().putString("auth_token", token).apply()
    fun getAuthToken(ctx: Context) = getSp(ctx).getString("auth_token", "") ?: ""

    // 刷新频率 (单位: 分钟) — 后台 Worker 间隔
    fun setRefreshInterval(ctx: Context, minutes: Int) = getSp(ctx).edit().putInt("refresh_interval", minutes).apply()
    fun getRefreshInterval(ctx: Context) = getSp(ctx).getInt("refresh_interval", 15)

    // 主界面自动刷新间隔 (单位: 秒) — 前台轮询间隔，0 表示关闭
    fun setMainRefreshSeconds(ctx: Context, seconds: Int) = getSp(ctx).edit().putInt("main_refresh_seconds", seconds).apply()
    fun getMainRefreshSeconds(ctx: Context) = getSp(ctx).getInt("main_refresh_seconds", 5)

    // 显隐设置
    fun setWidgetSettings(ctx: Context, flow: Boolean, signal: Boolean, temp: Boolean, cpu: Boolean, model: Boolean, time: Boolean) {
        getSp(ctx).edit()
            .putBoolean("show_flow", flow)
            .putBoolean("show_signal", signal)
            .putBoolean("show_temp", temp)
            .putBoolean("show_cpu", cpu)
            .putBoolean("show_model", model)
            .putBoolean("show_time", time)
            .apply()
    }

    fun getShowFlow(ctx: Context) = getSp(ctx).getBoolean("show_flow", true)
    fun getShowSignal(ctx: Context) = getSp(ctx).getBoolean("show_signal", true)
    fun getShowTemp(ctx: Context) = getSp(ctx).getBoolean("show_temp", true)
    fun getShowCpu(ctx: Context) = getSp(ctx).getBoolean("show_cpu", true)
    fun getShowModel(ctx: Context) = getSp(ctx).getBoolean("show_model", true)
    fun getShowTime(ctx: Context) = getSp(ctx).getBoolean("show_time", true)
    fun getShowBattery(ctx: Context) = getSp(ctx).getBoolean("show_battery", true)
    fun getShowMem(ctx: Context) = getSp(ctx).getBoolean("show_mem", true)

    fun setShowFlow(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_flow", show).apply()
    fun setShowSignal(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal", show).apply()
    fun setShowTemp(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_temp", show).apply()
    fun setShowCpu(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_cpu", show).apply()
    fun setShowModel(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_model", show).apply()
    fun setShowTime(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_time", show).apply()
    fun setShowBattery(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery", show).apply()
    fun setShowMem(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_mem", show).apply()

    /** 统计已启用的显示项数量 */
    fun getEnabledCount(ctx: Context): Int {
        var count = 0
        if (getShowFlow(ctx)) count++
        if (getShowSignal(ctx)) count++
        if (getShowTemp(ctx)) count++
        if (getShowCpu(ctx)) count++
        if (getShowModel(ctx)) count++
        if (getShowTime(ctx)) count++
        if (getShowBattery(ctx)) count++
        if (getShowMem(ctx)) count++
        return count
    }
    
    fun getCachedMonthlyData(ctx: Context): Long = getSp(ctx).getLong("cached_monthly_data", 0L)
    fun setCachedMonthlyData(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("cached_monthly_data", bytes).apply()

    fun isFirstRun(ctx: Context) = getSp(ctx).getBoolean("is_first_run", true)
    fun setFirstRun(ctx: Context, value: Boolean) = getSp(ctx).edit().putBoolean("is_first_run", value).apply()

    // ==================== Worker 失败状态 ====================
    /** 失败原因类型：空字符串=未失败, "network"=网络不通, "api"=端口/Token错误 */
    fun getWorkerStopReason(ctx: Context) = getSp(ctx).getString("worker_stop_reason", "") ?: ""
    fun setWorkerStopReason(ctx: Context, reason: String) = getSp(ctx).edit().putString("worker_stop_reason", reason).apply()

    // ==================== 设备连接配置（IP + 端口分离） ====================
    const val DEFAULT_DEVICE_IP = "192.168.0.1"
    const val DEFAULT_DEVICE_PORT = "2333"

    /** 设备 IP */
    fun getDeviceIp(ctx: Context): String {
        return getSp(ctx).getString("device_ip", DEFAULT_DEVICE_IP) ?: DEFAULT_DEVICE_IP
    }
    fun setDeviceIp(ctx: Context, ip: String) {
        val v = ip.trim()
        getSp(ctx).edit().putString("device_ip", v.ifEmpty { DEFAULT_DEVICE_IP }).apply()
    }

    /** 设备端口 */
    fun getDevicePort(ctx: Context): String {
        return getSp(ctx).getString("device_port", DEFAULT_DEVICE_PORT) ?: DEFAULT_DEVICE_PORT
    }
    fun setDevicePort(ctx: Context, port: String) {
        val v = port.trim()
        getSp(ctx).edit().putString("device_port", v.ifEmpty { DEFAULT_DEVICE_PORT }).apply()
    }

    /**
     * 构建完整 Base URL：http://{ip}:{port}/
     * 兼容旧版 base_url 字段：若新字段不存在，尝试从旧字段迁移
     */
    fun buildBaseUrl(ctx: Context): String {
        val ip = getDeviceIp(ctx)
        val port = getDevicePort(ctx)
        return "http://$ip:$port/"
    }

    /** 旧版兼容：从完整 URL 字符串读取（已废弃，保留用于迁移） */
    fun getBaseUrl(ctx: Context): String {
        // 优先用新字段构建
        val ip = getSp(ctx).getString("device_ip", null)
        val port = getSp(ctx).getString("device_port", null)
        if (ip != null && port != null) {
            return buildBaseUrl(ctx)
        }
        // 兼容旧版 base_url
        val oldUrl = getSp(ctx).getString("base_url", null)
        if (oldUrl != null) {
            // 从旧 URL 解析出 IP 和端口并迁移到新字段
            migrateFromOldUrl(ctx, oldUrl)
            return buildBaseUrl(ctx)
        }
        return "http://$DEFAULT_DEVICE_IP:$DEFAULT_DEVICE_PORT/"
    }

    /** 旧版兼容：保存完整 URL（已废弃，内部转为 IP+端口存储） */
    fun setBaseUrl(ctx: Context, url: String) {
        var u = url.trim()
        if (u.isNotEmpty() && !u.endsWith("/")) u += "/"
        // 尝试解析 http://ip:port/ 格式
        val regex = Regex("^https?://([^:/]+)(?::(\\d+))?/?$")
        val match = regex.find(u)
        if (match != null) {
            setDeviceIp(ctx, match.groupValues[1])
            val port = match.groupValues[2]
            setDevicePort(ctx, port.ifEmpty { DEFAULT_DEVICE_PORT })
        }
        // 同时保留旧字段以便兼容
        getSp(ctx).edit().putString("base_url", u).apply()
    }

    /** 从旧版 base_url 迁移到新字段 */
    private fun migrateFromOldUrl(ctx: Context, url: String) {
        val regex = Regex("^https?://([^:/]+)(?::(\\d+))?/?$")
        val match = regex.find(url.trim())
        if (match != null) {
            setDeviceIp(ctx, match.groupValues[1])
            val port = match.groupValues[2]
            setDevicePort(ctx, port.ifEmpty { DEFAULT_DEVICE_PORT })
        }
    }

    // ==================== 主题模式 ====================
    // app_theme: "system" (默认/跟随设备), "light", "dark"
    // widget_theme: "follow_app" (默认/跟随应用), "light", "dark"

    fun getAppTheme(ctx: Context) = getSp(ctx).getString("app_theme", "system") ?: "system"
    fun setAppTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("app_theme", mode).apply()

    fun getWidgetTheme(ctx: Context) = getSp(ctx).getString("widget_theme", "follow_app") ?: "follow_app"
    fun setWidgetTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("widget_theme", mode).apply()

    /** 判断小组件当前是否应使用暗色模式 */
    fun isWidgetDark(ctx: Context): Boolean {
        return when (getWidgetTheme(ctx)) {
            "light" -> false
            "dark" -> true
            else -> { // follow_app
                when (getAppTheme(ctx)) {
                    "light" -> false
                    "dark" -> true
                    else -> { // system
                        val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        nightMode == Configuration.UI_MODE_NIGHT_YES
                    }
                }
            }
        }
    }

    /** 判断应用当前是否应使用暗色模式（用于 Activity 启动时设置） */
    fun getNightMode(ctx: Context): Int {
        return when (getAppTheme(ctx)) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    // ==================== 自定义颜色主题 ====================
    // color_theme = -1 表示使用自定义颜色
    fun getCustomAccentLight(ctx: Context) = getSp(ctx).getInt("custom_accent_light", 0xFF222222.toInt())
    fun setCustomAccentLight(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_light", color).apply()
    fun getCustomAccentDark(ctx: Context) = getSp(ctx).getInt("custom_accent_dark", 0xFFCCCCCC.toInt())
    fun setCustomAccentDark(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_dark", color).apply()

    /** 获取当前颜色主题索引（-1 为自定义） */
    fun getColorThemeIndex(ctx: Context) = getSp(ctx).getInt("color_theme", 0)
    fun setColorThemeIndex(ctx: Context, index: Int) = getSp(ctx).edit().putInt("color_theme", index).apply()
}
