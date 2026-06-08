package com.ufi_toolswidget.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object SPUtil {

    // ── 预编译 Regex：地址解析 ──
    private val PROTOCOL_RE = Regex("^(https?)://([^:/]+)(?::(\\d+))?/?$")
    private val HOST_PORT_RE = Regex("^([^:/]+)(?::(\\d+))?$")
    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun getSp(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
    }

    /**
     * 保存 WiFi 数据到 SharedPreferences（线程安全，异步写入）。
     * 使用 @Synchronized + apply()：内存立即更新（后续读取可见），磁盘写入异步。
     * @Synchronized 避免 Worker 与前台并发调用时的写入交错。
     */
    @Synchronized
    fun saveData(ctx: Context, data: WifiEntity) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        getSp(ctx).edit()
            .putString("flow", data.flow)
            .putString("daily_flow", data.dailyFlow)
            .putString("signal", data.signal)
            .putString("temp", data.temp)
            .putString("battery", data.battery)
            .putInt("battery_percent", data.batteryPercent)
            .putString("model", data.model)
            .putString("cpu", data.cpu)
            .putString("mem", data.mem)
            .putString("net_type", data.netType)
            .putString("app_ver", data.appVer)
            .putString("app_ver_code", data.appVerCode)
            .putString("battery_current", data.batteryCurrent)
            .putString("battery_voltage", data.batteryVoltage)
            .putString("internal_storage", data.internalStorage)
            .putLong("internal_total_storage", data.internalTotalStorage)
            .putLong("internal_used_storage", data.internalUsedStorage)
            .putLong("internal_available_storage", data.internalAvailableStorage)
            .putLong("external_total_storage", data.externalTotalStorage)
            .putLong("external_used_storage", data.externalUsedStorage)
            .putLong("external_available_storage", data.externalAvailableStorage)
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

    // ==================== 各尺寸独立显隐设置 ====================
    // 2×1 迷你版（默认：信号+电池+网络类型 开启）
    fun getShowSignal2x1(ctx: Context) = getSp(ctx).getBoolean("show_signal_2x1", true)
    fun setShowSignal2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal_2x1", show).apply()
    fun getShowBattery2x1(ctx: Context) = getSp(ctx).getBoolean("show_battery_2x1", true)
    fun setShowBattery2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery_2x1", show).apply()
    fun getShowNetwork2x1(ctx: Context) = getSp(ctx).getBoolean("show_network_2x1", true)
    fun setShowNetwork2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_network_2x1", show).apply()

    // 4×1 条形版（默认：全部开启）
    fun getShowModel4x1(ctx: Context) = getSp(ctx).getBoolean("show_model_4x1", true)
    fun setShowModel4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_model_4x1", show).apply()
    fun getShowSignal4x1(ctx: Context) = getSp(ctx).getBoolean("show_signal_4x1", true)
    fun setShowSignal4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal_4x1", show).apply()
    fun getShowBattery4x1(ctx: Context) = getSp(ctx).getBoolean("show_battery_4x1", true)
    fun setShowBattery4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery_4x1", show).apply()
    fun getShowTemp4x1(ctx: Context) = getSp(ctx).getBoolean("show_temp_4x1", true)
    fun setShowTemp4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_temp_4x1", show).apply()
    fun getShowTime4x1(ctx: Context) = getSp(ctx).getBoolean("show_time_4x1", true)
    fun setShowTime4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_time_4x1", show).apply()

    // ==================== 各尺寸独立字体大小 ====================
    // 2×1 迷你版字体大小（sp，默认 9）
    fun getFontSize2x1(ctx: Context) = getSp(ctx).getInt("font_size_2x1", 9)
    fun setFontSize2x1(ctx: Context, sp: Int) = getSp(ctx).edit().putInt("font_size_2x1", sp).apply()
    // 4×1 条形版字体大小（sp，默认 9）
    fun getFontSize4x1(ctx: Context) = getSp(ctx).getInt("font_size_4x1", 9)
    fun setFontSize4x1(ctx: Context, sp: Int) = getSp(ctx).edit().putInt("font_size_4x1", sp).apply()

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

    // ==================== Worker 失败状态（线程安全读写） ====================
    /** 失败原因类型：空字符串=未失败, "network"=网络不通, "api"=端口/Token错误 */
    fun getWorkerStopReason(ctx: Context) = getSp(ctx).getString("worker_stop_reason", "") ?: ""

    /** worker 是否因连续失败被停止 */
    fun isWorkerStopped(ctx: Context) = getSp(ctx).getBoolean("worker_stopped_by_failure", false)

    /** API 连续失败计数 */
    fun getApiFailureCount(ctx: Context) = getSp(ctx).getInt("worker_api_failure_count", 0)

    /** 网络连续失败计数 */
    fun getNetworkFailureCount(ctx: Context) = getSp(ctx).getInt("worker_network_failure_count", 0)

    /** 获取失败原因汇总（供外部 UI 显示） */
    fun getWorkerFailureSummary(ctx: Context): String {
        if (!isWorkerStopped(ctx)) return ""
        return getWorkerStopReason(ctx).ifEmpty { "unknown" }
    }

    /** 原子递增网络失败计数，返回递增后的值 */
    @Synchronized
    fun incrementNetworkFailureCount(ctx: Context): Int {
        val sp = getSp(ctx)
        val count = sp.getInt("worker_network_failure_count", 0) + 1
        sp.edit().putInt("worker_network_failure_count", count).commit()
        return count
    }

    /** 原子递增 API 失败计数，返回递增后的值 */
    @Synchronized
    fun incrementApiFailureCount(ctx: Context): Int {
        val sp = getSp(ctx)
        val count = sp.getInt("worker_api_failure_count", 0) + 1
        sp.edit().putInt("worker_api_failure_count", count).commit()
        return count
    }

    /** 仅重置网络失败计数（ping 恢复时） */
    @Synchronized
    fun resetNetworkFailureCount(ctx: Context) {
        getSp(ctx).edit().putInt("worker_network_failure_count", 0).commit()
    }

    /** 重置所有失败状态（手动刷新、配置变更、Worker 启动时调用） */
    @Synchronized
    fun resetWorkerFailureState(ctx: Context) {
        val sp = getSp(ctx)
        val prevStopped = sp.getBoolean("worker_stopped_by_failure", false)
        sp.edit()
            .putInt("worker_api_failure_count", 0)
            .putInt("worker_network_failure_count", 0)
            .putBoolean("worker_stopped_by_failure", false)
            .putString("worker_stop_reason", "")
            .commit()
        DebugLogger.i("SPUtil", "resetWorkerFailureState called (prevStopped=$prevStopped)")
    }

    /** 标记网络不通导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedNetwork(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putInt("worker_api_failure_count", 0)
            .putString("worker_stop_reason", "network")
            .commit()
    }

    /** 标记 API 连续失败导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedApi(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putString("worker_stop_reason", "api")
            .commit()
    }

    // ==================== 设备连接配置 ====================
    const val DEFAULT_DEVICE_ADDRESS = "192.168.0.1:2333"

    /** 获取设备地址（单一字段，支持 IP:端口 或 域名） */
    fun getDeviceAddress(ctx: Context): String {
        return getSp(ctx).getString("device_address", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEVICE_ADDRESS
    }

    /** 保存设备地址（同时重置协议探测结果与响应缓存，下次自动重探） */
    fun setDeviceAddress(ctx: Context, address: String) {
        val v = address.trim()
        getSp(ctx).edit()
            .putString("device_address", v.ifEmpty { DEFAULT_DEVICE_ADDRESS })
            .putString("device_protocol", "auto")  // 地址变了，旧探测结果作废
            .apply()
        invalidateResponseCaches(ctx)  // 设备换了，旧响应缓存全部作废
    }

    /** 从地址中提取主机部分（IP 或域名） */
    fun getDeviceHost(ctx: Context): String {
        return parseAddress(getDeviceAddress(ctx)).host
    }

    /** 从地址中提取端口号 */
    fun getDevicePortInt(ctx: Context): Int {
        return parseAddress(getDeviceAddress(ctx)).port
    }

    // ── 协议自动探测缓存 ──

    /** 获取自动探测到的协议（\"auto\" = 未探测 / \"http\" / \"https\"） */
    fun getDeviceProtocol(ctx: Context): String =
        getSp(ctx).getString("device_protocol", "auto") ?: "auto"

    /** 保存自动探测到的协议 */
    fun setDeviceProtocol(ctx: Context, protocol: String) =
        getSp(ctx).edit().putString("device_protocol", protocol).apply()

    /** 当前地址是否需要协议探测（域名或公网IP，且未显式写协议前缀） */
    fun needsProtocolProbe(ctx: Context): Boolean {
        val raw = getDeviceAddress(ctx).trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return false
        return !isPrivateOrLocalIp(parseAddress(raw).host)
    }

    /**
     * 构建完整 Base URL：
     * - 显式协议（http:// 或 https://）→ 直接使用
     * - 私有 IP → http://
     * - 域名/公网IP → 查缓存协议；若已探测到则用对应协议，否则默认 https://
     */
    fun buildBaseUrl(ctx: Context): String {
        val raw = getDeviceAddress(ctx)
        val (parsedProtocol, host, parsedPort, protocolExplicit, portExplicit) = parseAddress(raw)

        val protocol = if (protocolExplicit) {
            parsedProtocol
        } else {
            val stored = getDeviceProtocol(ctx)
            if (stored == "auto") parsedProtocol else stored
        }

        val port = if (portExplicit) {
            parsedPort
        } else {
            when {
                protocol == "https" -> 443
                isPrivateOrLocalIp(host) -> 2333
                else -> 80
            }
        }

        return "$protocol://$host:$port/"
    }

    // ==================== API 接口高级配置 ====================
    const val DEFAULT_AT_COMMAND_PATH = "/api/AT"
    const val DEFAULT_DEVICE_INFO_PATH = "/api/baseDeviceInfo"
    const val DEFAULT_GOFORM_COMMAND_PATH = "/api/goform/goform_get_cmd_process"
    const val DEFAULT_NEED_TOKEN_PATH = "/api/need_token"
    const val DEFAULT_VERSION_INFO_PATH = "/api/version_info"
    //UFI-TOOLS文档中注明的固定秘钥：https://github.com/kanoqwq/UFI-TOOLS/blob/http-server-version/API_Doc.md
    const val DEFAULT_SECRET_KEY = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"

    fun getAtCommandPath(ctx: Context) = getSp(ctx).getString("at_command_path", DEFAULT_AT_COMMAND_PATH) ?: DEFAULT_AT_COMMAND_PATH
    fun setAtCommandPath(ctx: Context, path: String) = getSp(ctx).edit().putString("at_command_path", path.ifBlank { DEFAULT_AT_COMMAND_PATH }).apply()

    fun getDeviceInfoPath(ctx: Context) = getSp(ctx).getString("device_info_path", DEFAULT_DEVICE_INFO_PATH) ?: DEFAULT_DEVICE_INFO_PATH
    fun setDeviceInfoPath(ctx: Context, path: String) = getSp(ctx).edit().putString("device_info_path", path.ifBlank { DEFAULT_DEVICE_INFO_PATH }).apply()

    fun getGoformCommandPath(ctx: Context) = getSp(ctx).getString("goform_command_path", DEFAULT_GOFORM_COMMAND_PATH) ?: DEFAULT_GOFORM_COMMAND_PATH
    fun setGoformCommandPath(ctx: Context, path: String) = getSp(ctx).edit().putString("goform_command_path", path.ifBlank { DEFAULT_GOFORM_COMMAND_PATH }).apply()

    fun getNeedTokenPath(ctx: Context) = getSp(ctx).getString("need_token_path", DEFAULT_NEED_TOKEN_PATH) ?: DEFAULT_NEED_TOKEN_PATH
    fun setNeedTokenPath(ctx: Context, path: String) = getSp(ctx).edit().putString("need_token_path", path.ifBlank { DEFAULT_NEED_TOKEN_PATH }).apply()

    fun getVersionInfoPath(ctx: Context) = getSp(ctx).getString("version_info_path", DEFAULT_VERSION_INFO_PATH) ?: DEFAULT_VERSION_INFO_PATH
    fun setVersionInfoPath(ctx: Context, path: String) = getSp(ctx).edit().putString("version_info_path", path.ifBlank { DEFAULT_VERSION_INFO_PATH }).apply()

    fun getSecretKey(ctx: Context) = getSp(ctx).getString("secret_key", DEFAULT_SECRET_KEY) ?: DEFAULT_SECRET_KEY
    fun setSecretKey(ctx: Context, key: String) = getSp(ctx).edit().putString("secret_key", key.ifBlank { DEFAULT_SECRET_KEY }).apply()

    // ── 解析工具 ──

    /** 解析地址字符串 → (协议, 主机, 端口, 协议是否显式指定, 端口是否显式指定) */
    private fun parseAddress(raw: String): AddressParts {
        val trimmed = raw.trim()

        // 1) 带协议前缀：http://host:port 或 https://host:port
        PROTOCOL_RE.find(trimmed)?.let { m ->
            val protocol = m.groupValues[1]
            val host = m.groupValues[2]
            val portStr = m.groupValues[3]
            val portExplicit = portStr.isNotEmpty()
            val port = if (portExplicit) portStr.toInt() else (if (protocol == "https") 443 else 80)
            return AddressParts(protocol, host, port, protocolExplicit = true, portExplicit = portExplicit)
        }

        // 2) 无协议：host:port 或 host
        HOST_PORT_RE.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val explicitPort = m.groupValues[2].toIntOrNull()
            val portExplicit = explicitPort != null
            val (protocol, defaultPort) = resolveProtocol(host, explicitPort)
            return AddressParts(protocol, host, explicitPort ?: defaultPort, protocolExplicit = false, portExplicit = portExplicit)
        }

        // 解析失败 → 默认地址
        return parseAddress(DEFAULT_DEVICE_ADDRESS)
    }

    /** 根据主机类型决定协议与默认端口（仅用于未探测时的默认值）。公网域名默认 https://，协议探测不通过时自动回退 http://。 */
    private fun resolveProtocol(host: String, explicitPort: Int?): Pair<String, Int> {
        if (isPrivateOrLocalIp(host)) {
            return "http" to (explicitPort ?: 2333)
        }
        return "https" to (explicitPort ?: 443)
    }

    /** 判断是否为私有/本地 IP */
    internal fun isPrivateOrLocalIp(host: String): Boolean {
        val ipPattern = IPV4_RE
        val m = ipPattern.find(host) ?: return false
        val o1 = m.groupValues[1].toIntOrNull() ?: return false
        val o2 = m.groupValues[2].toIntOrNull() ?: return false
        return o1 == 10 ||
                o1 == 127 ||
                (o1 == 172 && o2 in 16..31) ||
                (o1 == 192 && o2 == 168)
    }

    private data class AddressParts(
        val protocol: String,
        val host: String,
        val port: Int,
        val protocolExplicit: Boolean = false,
        val portExplicit: Boolean = false
    )

    // ==================== 主题模式 ====================
    // app_theme: "system" (默认/跟随设备), "light", "dark"
    // widget_theme: "follow_app" (默认/跟随应用), "light", "dark"

    fun getAppTheme(ctx: Context) = getSp(ctx).getString("app_theme", "system") ?: "system"
    fun setAppTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("app_theme", mode).apply()

    fun getWidgetTheme(ctx: Context) = getSp(ctx).getString("widget_theme", "follow_app") ?: "follow_app"
    fun setWidgetTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("widget_theme", mode).apply()

    /** 获取小组件是否跟随应用主题 */
    fun getWidgetFollowAppTheme(ctx: Context) = getSp(ctx).getBoolean("widget_follow_app_theme", true)
    fun setWidgetFollowAppTheme(ctx: Context, follow: Boolean) = getSp(ctx).edit().putBoolean("widget_follow_app_theme", follow).apply()

    /** 判断小组件当前是否应使用暗色模式 */
    fun isWidgetDark(ctx: Context): Boolean {
        if (!getWidgetFollowAppTheme(ctx)) {
            // 如果不跟随应用主题，则根据小组件自身的主题设置决定
            return when (getWidgetTheme(ctx)) {
                "light" -> false
                "dark" -> true
                else -> { // 如果设为了 follow_app 但 follow 开关关了，强制走系统识别
                    val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    nightMode == Configuration.UI_MODE_NIGHT_YES
                }
            }
        }
        // 原有逻辑：跟随应用主题
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

    /** 获取小组件独立颜色主题索引 */
    fun getWidgetColorThemeIndex(ctx: Context) = getSp(ctx).getInt("widget_color_theme", 0)
    fun setWidgetColorThemeIndex(ctx: Context, index: Int) = getSp(ctx).edit().putInt("widget_color_theme", index).apply()

    fun getWidgetCustomAccentLight(ctx: Context) = getSp(ctx).getInt("widget_custom_accent_light", 0xFF222222.toInt())
    fun setWidgetCustomAccentLight(ctx: Context, color: Int) = getSp(ctx).edit().putInt("widget_custom_accent_light", color).apply()
    fun getWidgetCustomAccentDark(ctx: Context) = getSp(ctx).getInt("widget_custom_accent_dark", 0xFFCCCCCC.toInt())
    fun setWidgetCustomAccentDark(ctx: Context, color: Int) = getSp(ctx).edit().putInt("widget_custom_accent_dark", color).apply()

    // ==================== 崩溃信息 ====================
    /** 上次崩溃时间戳（0 = 无崩溃记录） */
    fun getLastCrashTime(ctx: Context) = getSp(ctx).getLong("last_crash_time", 0L)
    fun setLastCrashTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("last_crash_time", time).apply()

    /** 保存崩溃信息摘要（时间戳 + 异常类名 + 脱敏后的 message） */
    fun setLastCrashInfo(ctx: Context, crashInfo: String) {
        val summary = crashInfo.lines().firstOrNull { it.contains("异常堆栈") || it.contains("Exception") }
            ?: crashInfo.take(200)
        getSp(ctx).edit()
            .putLong("last_crash_time", System.currentTimeMillis())
            .putString("last_crash_summary", summary.take(500))
            .apply()
    }

    fun setLastCrashSummary(ctx: Context, summary: String) = getSp(ctx).edit().putString("last_crash_summary", summary).apply()

    /** 获取上次崩溃摘要 */
    fun getLastCrashSummary(ctx: Context) = getSp(ctx).getString("last_crash_summary", "") ?: ""
    /** 清除崩溃标志（用户已查看或忽略） */
    fun clearCrashInfo(ctx: Context) = getSp(ctx).edit()
        .putLong("last_crash_time", 0L)
        .remove("last_crash_summary")
        .apply()

    // ==================== 调试模式 ====================
    fun getDebugEnabled(ctx: Context) = getSp(ctx).getBoolean("debug_enabled", false)
    fun setDebugEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("debug_enabled", enabled).apply()

    // ==================== 更新镜像源与自动检查 ====================
    // 0 = GitHub 官方，1 = 国内镜像 (gh-proxy)
    fun getUpdateMirror(ctx: Context) = getSp(ctx).getInt("update_mirror", 0)
    fun setUpdateMirror(ctx: Context, mirror: Int) = getSp(ctx).edit().putInt("update_mirror", mirror).commit()

    /** 获取是否开启自动检测更新 */
    fun getAutoCheckUpdate(ctx: Context) = getSp(ctx).getBoolean("auto_check_update", true)
    
    /** 设置是否开启自动检测更新 */
    fun setAutoCheckUpdate(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("auto_check_update", enabled).apply()

    /** 获取上次自动检查更新的时间戳 */
    fun getLastUpdateCheckTime(ctx: Context) = getSp(ctx).getLong("last_update_check_time", 0L)

    /** 保存本次自动检查更新的时间戳 */
    fun setLastUpdateCheckTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("last_update_check_time", time).apply()

    // ==================== 自定义背景图片 ====================
    /** 获取自定义背景图片 URI（空字符串=未设置） */
    fun getBgImageUri(ctx: Context) = getSp(ctx).getString("bg_image_uri", "") ?: ""

    /** 保存自定义背景图片 URI */
    fun setBgImageUri(ctx: Context, uri: String) = getSp(ctx).edit().putString("bg_image_uri", uri).apply()

    /** 清除自定义背景图片 */
    fun clearBgImageUri(ctx: Context) = getSp(ctx).edit().remove("bg_image_uri").apply()

    // ==================== 小组件自定义背景 ====================
    /** 获取小组件自定义背景图片 URI（空字符串=未设置） */
    fun getWidgetBgImageUri(ctx: Context) = getSp(ctx).getString("widget_bg_image_uri", "") ?: ""

    /** 保存小组件自定义背景图片 URI */
    fun setWidgetBgImageUri(ctx: Context, uri: String) = getSp(ctx).edit().putString("widget_bg_image_uri", uri).apply()

    /** 清除小组件自定义背景图片 */
    fun clearWidgetBgImageUri(ctx: Context) = getSp(ctx).edit().remove("widget_bg_image_uri").apply()

    // ==================== 设备平台缓存（AT+CGMI 探测结果） ====================
    // "spreadtrum" | "quectel" | "" (未探测)
    fun getCachedPlatform(ctx: Context) = getSp(ctx).getString("device_platform", "") ?: ""
    fun setCachedPlatform(ctx: Context, platform: String) = getSp(ctx).edit().putString("device_platform", platform).apply()

    /** 获取小组件背景透明度（0-100，默认 100 = 完全不透明） */
    fun getWidgetBgOpacity(ctx: Context) = getSp(ctx).getInt("widget_bg_opacity", 100)

    /** 保存小组件背景透明度 */
    fun setWidgetBgOpacity(ctx: Context, opacity: Int) = getSp(ctx).edit().putInt("widget_bg_opacity", opacity).apply()

    // ==================== 小组件圆角裁剪兜底开关 ====================
    /** 是否启用小组件圆角处理（默认关闭）。
     *  开启后 Bitmap 裁剪 + clipToOutline + 圆角 drawable 全链路圆角，
     *  适配原生/国际版桌面。国产桌面通常已自带圆角，无需开启。 */
    fun getWidgetClipToOutline(ctx: Context) = getSp(ctx).getBoolean("widget_clip_to_outline", false)
    fun setWidgetClipToOutline(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_clip_to_outline", enabled).apply()

    // ==================== Android 12+ 动态配色（Material You）====================
    /** 是否启用小组件动态配色（Material You，仅 Android 12+ 生效）。
     *  开启后小组件背景/文字颜色自动跟随系统壁纸色调，无需手动选择配色。
     *  默认 false：用户需在实验功能页手动开启。 */
    fun getWidgetDynamicColor(ctx: Context) = getSp(ctx).getBoolean("widget_dynamic_color", false)
    fun setWidgetDynamicColor(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_dynamic_color", enabled).apply()

    /** 动态配色对比度级别：0=柔和, 1=标准(默认), 2=强烈 */
    fun getWidgetDynamicContrast(ctx: Context) = getSp(ctx).getInt("widget_dynamic_contrast", 1)
    fun setWidgetDynamicContrast(ctx: Context, level: Int) = getSp(ctx).edit().putInt("widget_dynamic_contrast", level).apply()

    /** 动态配色高级设置开关 */
    fun getWidgetDynamicAdvanced(ctx: Context) = getSp(ctx).getBoolean("widget_dynamic_advanced", false)
    fun setWidgetDynamicAdvanced(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_dynamic_advanced", enabled).apply()
    
    /** 动态配色色源选择：0=Primary, 1=Secondary, 2=Tertiary, 3=Neutral, 4=NeutralVariant */
    fun getWidgetDynamicColorSource(ctx: Context) = getSp(ctx).getInt("widget_dynamic_color_source", 0)
    fun setWidgetDynamicColorSource(ctx: Context, source: Int) = getSp(ctx).edit().putInt("widget_dynamic_color_source", source).apply()
    /** 高级：浅色背景亮度 (85-99, 默认 97) */
    fun getDynAdvLightBg(ctx: Context) = getSp(ctx).getInt("dyn_adv_light_bg", 97)
    fun setDynAdvLightBg(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_light_bg", v).apply()
    /** 高级：浅色文字亮度 (5-40, 默认 12) */
    fun getDynAdvLightTxt(ctx: Context) = getSp(ctx).getInt("dyn_adv_light_txt", 12)
    fun setDynAdvLightTxt(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_light_txt", v).apply()
    /** 高级：深色背景亮度 (3-20, 默认 8) */
    fun getDynAdvDarkBg(ctx: Context) = getSp(ctx).getInt("dyn_adv_dark_bg", 8)
    fun setDynAdvDarkBg(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_dark_bg", v).apply()
    /** 高级：深色文字亮度 (75-98, 默认 90) */
    fun getDynAdvDarkTxt(ctx: Context) = getSp(ctx).getInt("dyn_adv_dark_txt", 90)
    fun setDynAdvDarkTxt(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_dark_txt", v).apply()
    /** 高级：饱和度增强 (50-150, 默认 100 = 无增强) */
    fun getDynAdvSatBoost(ctx: Context) = getSp(ctx).getInt("dyn_adv_sat_boost", 100)
    fun setDynAdvSatBoost(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_sat_boost", v).apply()

    // ==================== 小组件兼容性设置 ====================
    /** 是否隐藏小组件名称（桌面显示的名称替换为空格，实现视觉隐藏） */
    fun getWidgetHideLabel(ctx: Context) = getSp(ctx).getBoolean("widget_hide_label", false)
    fun setWidgetHideLabel(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_hide_label", enabled).apply()

    // ==================== 响应缓存策略 ====================
    // 低频变化的 API 响应缓存到 SP，减少重复请求

    /** 响应缓存默认 TTL：1 小时（毫秒） */
    const val CACHE_TTL_HOUR_MS = 3_600_000L

    // ── version_info 缓存 ──
    fun getCachedVersionInfoJson(ctx: Context) = getSp(ctx).getString("cache_version_info_json", "") ?: ""
    fun setCachedVersionInfoJson(ctx: Context, json: String) = getSp(ctx).edit().putString("cache_version_info_json", json).apply()
    fun getVersionInfoCacheTime(ctx: Context) = getSp(ctx).getLong("cache_version_info_time", 0L)
    fun setVersionInfoCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_version_info_time", time).apply()
    fun isVersionInfoCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getVersionInfoCacheTime(ctx) < CACHE_TTL_HOUR_MS

    // ── need_token 缓存 ──
    fun getCachedNeedTokenJson(ctx: Context) = getSp(ctx).getString("cache_need_token_json", "") ?: ""
    fun setCachedNeedTokenJson(ctx: Context, json: String) = getSp(ctx).edit().putString("cache_need_token_json", json).apply()
    fun getNeedTokenCacheTime(ctx: Context) = getSp(ctx).getLong("cache_need_token_time", 0L)
    fun setNeedTokenCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_need_token_time", time).apply()
    fun isNeedTokenCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getNeedTokenCacheTime(ctx) < CACHE_TTL_HOUR_MS

    // ── AT 静态字段缓存（CGMM 模块型号 / CGMR 固件版本 / CGSN IMEI）──
    fun getCachedModuleModel(ctx: Context) = getSp(ctx).getString("cache_at_cgmm", "") ?: ""
    fun setCachedModuleModel(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgmm", value).apply()
    fun getCachedFirmwareDetail(ctx: Context) = getSp(ctx).getString("cache_at_cgmr", "") ?: ""
    fun setCachedFirmwareDetail(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgmr", value).apply()
    fun getCachedImeiFromAt(ctx: Context) = getSp(ctx).getString("cache_at_cgsn", "") ?: ""
    fun setCachedImeiFromAt(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgsn", value).apply()
    fun getAtStaticCacheTime(ctx: Context) = getSp(ctx).getLong("cache_at_static_time", 0L)
    fun setAtStaticCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_at_static_time", time).apply()
    fun isAtStaticCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getAtStaticCacheTime(ctx) < CACHE_TTL_HOUR_MS

    /** 清除所有响应缓存（设备地址变更、Token 变更时调用，强制下轮全量刷新） */
    fun invalidateResponseCaches(ctx: Context) {
        getSp(ctx).edit()
            .putLong("cache_version_info_time", 0L)
            .putLong("cache_need_token_time", 0L)
            .putLong("cache_at_static_time", 0L)
            .putString("device_platform", "")  // 平台探测也一并清除
            .apply()
        DebugLogger.i("SPUtil", "invalidateResponseCaches: all response caches cleared")
    }

}
