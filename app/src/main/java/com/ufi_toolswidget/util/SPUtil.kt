package com.ufi_toolswidget.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object SPUtil {

    // ── 预编译 Regex：地址解析 ──
    private val PROTOCOL_RE = Regex("^(https?)://([^:/]+)(?::(\\d+))?/?$")
    private val HOST_PORT_RE = Regex("^([^:/]+)(?::(\\d+))?$")
    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    // ── 缓存 SimpleDateFormat：避免每次 saveData 都重新创建（线程安全） ──
    private val saveTimeFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    // ── 缓存 baseUrl 构建结果：地址不变时跳过 regex 解析 + SP 读取 ──
    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedBaseUrlKey: String? = null  // address+protocol 组合作为 cache key

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
        val time = synchronized(saveTimeFormat) { saveTimeFormat.format(java.util.Date()) }
        // 计算数据字段哈希，供小组件渲染去重使用（避免每次渲染都读 14 个 SP 字段）
        val dataHash = computeWidgetDataHash(data, time)
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
            .putInt("sp_cached_data_hash", dataHash)
            .apply()
    }

    /**
     * 计算数据字段的哈希指纹（仅影响小组件渲染的数据字段，不含外观设置）。
     * 与 [computeDataHash] 中数据部分算法一致，保证相同数据产生相同哈希值。
     * 结果缓存在 SP 的 `sp_cached_data_hash` 中，供小组件渲染去重使用。
     */
    private fun computeWidgetDataHash(data: WifiEntity, time: String): Int {
        var h = 17
        h = 31 * h + data.deviceModel.hashCode()
        h = 31 * h + data.model.hashCode()
        h = 31 * h + data.firmwareVer.hashCode()
        h = 31 * h + data.flow.hashCode()
        h = 31 * h + data.dailyFlow.hashCode()
        h = 31 * h + data.signal.hashCode()
        h = 31 * h + data.temp.hashCode()
        h = 31 * h + data.battery.hashCode()
        h = 31 * h + data.batteryPercent  // 电量百分比也应参与哈希
        h = 31 * h + data.cpu.hashCode()
        h = 31 * h + data.mem.hashCode()
        h = 31 * h + data.netType.hashCode()
        h = 31 * h + data.appVerCode.hashCode()
        h = 31 * h + data.batteryCurrent.hashCode()
        h = 31 * h + data.batteryVoltage.hashCode()       // 电池电压
        h = 31 * h + data.internalStorage.hashCode()      // 内部存储
        h = 31 * h + data.clientIp.hashCode()             // 客户端 IP
        h = 31 * h + time.hashCode()
        return h
    }

    /** 读取缓存的 widget 数据哈希（由 [saveData] 写入），0 表示尚未缓存 */
    fun getCachedDataHash(ctx: Context): Int = getSp(ctx).getInt("sp_cached_data_hash", 0)

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

    // ── 小组件渲染配置批量读取（减少多次 SP 读取开销） ──

    /** 小组件显隐 + 外观配置一次性读取结果，供渲染时使用 */
    data class WidgetRenderConfig(
        val showFlow: Boolean, val showSignal: Boolean, val showTemp: Boolean,
        val showCpu: Boolean, val showModel: Boolean, val showTime: Boolean,
        val showBattery: Boolean, val showMem: Boolean,
        val isDark: Boolean, val shouldClip: Boolean,
        val bgOpacity: Int, val bgImageUri: String
    )

    /** 一次性读取所有小组件渲染配置，避免多次独立 SP 读取 */
    fun loadWidgetRenderConfig(ctx: Context): WidgetRenderConfig {
        val sp = getSp(ctx)
        return WidgetRenderConfig(
            showFlow = sp.getBoolean("show_flow", true),
            showSignal = sp.getBoolean("show_signal", true),
            showTemp = sp.getBoolean("show_temp", true),
            showCpu = sp.getBoolean("show_cpu", true),
            showModel = sp.getBoolean("show_model", true),
            showTime = sp.getBoolean("show_time", true),
            showBattery = sp.getBoolean("show_battery", true),
            showMem = sp.getBoolean("show_mem", true),
            isDark = isWidgetDark(ctx),
            shouldClip = sp.getBoolean("widget_clip_to_outline", false),
            bgOpacity = sp.getInt("widget_bg_opacity", 100),
            bgImageUri = sp.getString("widget_bg_image_uri", "") ?: ""
        )
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
        sp.edit().putInt("worker_network_failure_count", count).apply()
        return count
    }

    /** 原子递增 API 失败计数，返回递增后的值 */
    @Synchronized
    fun incrementApiFailureCount(ctx: Context): Int {
        val sp = getSp(ctx)
        val count = sp.getInt("worker_api_failure_count", 0) + 1
        sp.edit().putInt("worker_api_failure_count", count).apply()
        return count
    }

    /** 仅重置网络失败计数（ping 恢复时） */
    @Synchronized
    fun resetNetworkFailureCount(ctx: Context) {
        getSp(ctx).edit().putInt("worker_network_failure_count", 0).apply()
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
            .apply()
        DebugLogger.i("SPUtil", "resetWorkerFailureState called (prevStopped=$prevStopped)")
    }

    /** 标记网络不通导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedNetwork(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putInt("worker_api_failure_count", 0)
            .putString("worker_stop_reason", "network")
            .apply()
    }

    /** 标记 API 连续失败导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedApi(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putString("worker_stop_reason", "api")
            .apply()
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
        invalidateBaseUrlCache()  // 地址变更，清除 baseUrl 缓存
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
        val protocol = getDeviceProtocol(ctx)
        val cacheKey = "$raw|$protocol"
        // 缓存命中：地址和协议未变，直接返回上次构建结果
        if (cacheKey == cachedBaseUrlKey && cachedBaseUrl != null) {
            return cachedBaseUrl!!
        }

        val (parsedProtocol, host, parsedPort, protocolExplicit, portExplicit) = parseAddress(raw)

        val finalProtocol = if (protocolExplicit) {
            parsedProtocol
        } else {
            val stored = protocol
            if (stored == "auto") parsedProtocol else stored
        }

        val port = if (portExplicit) {
            parsedPort
        } else {
            when {
                finalProtocol == "https" -> 443
                isPrivateOrLocalIp(host) -> 2333
                else -> 80
            }
        }

        val result = "$finalProtocol://$host:$port/"
        cachedBaseUrl = result
        cachedBaseUrlKey = cacheKey
        return result
    }

    /** 清除 baseUrl 缓存（设备地址变更时调用） */
    fun invalidateBaseUrlCache() {
        cachedBaseUrl = null
        cachedBaseUrlKey = null
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
    fun setUpdateMirror(ctx: Context, mirror: Int) = getSp(ctx).edit().putInt("update_mirror", mirror).apply()

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
    /** 获取小组件自定义背景图片 URI / 文件路径（空字符串=未设置） */
    fun getWidgetBgImageUri(ctx: Context) = getSp(ctx).getString("widget_bg_image_uri", "") ?: ""

    /** 保存小组件自定义背景图片 URI / 文件路径 */
    fun setWidgetBgImageUri(ctx: Context, uri: String) = getSp(ctx).edit().putString("widget_bg_image_uri", uri).apply()

    /** 清除小组件自定义背景图片 */
    fun clearWidgetBgImageUri(ctx: Context) = getSp(ctx).edit().remove("widget_bg_image_uri").apply()

    /**
     * 将 content:// URI 拷贝到应用内部存储，返回绝对文件路径。
     * 解决 content:// URI 在 Widget 进程跨进程访问权限问题。
     */
    fun saveWidgetBgImageToInternal(ctx: Context, sourceUri: android.net.Uri): String? {
        return try {
            val dir = java.io.File(ctx.filesDir, "widget_bg")
            if (!dir.exists()) dir.mkdirs()
            // 使用时间戳文件名，避免历史记录覆盖同一物理文件
            val file = java.io.File(dir, "custom_bg_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            file.absolutePath
        } catch (e: Exception) {
            DebugLogger.w("SPUtil", "saveWidgetBgImageToInternal failed: ${e.message}")
            null
        }
    }

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

    // ==================== 小组件背景开关 & 历史 ====================
    /** 小组件自定义背景是否启用（关闭时使用默认纯色背景） */
    fun getWidgetBgImageEnabled(ctx: Context): Boolean {
        val sp = getSp(ctx)
        if (!sp.contains("widget_bg_image_enabled")) {
            // 迁移：已有背景图的用户默认启用
            val hasUri = getWidgetBgImageUri(ctx).isNotBlank()
            if (hasUri) {
                setWidgetBgImageEnabled(ctx, true)
                return true
            }
            return false
        }
        return sp.getBoolean("widget_bg_image_enabled", false)
    }
    fun setWidgetBgImageEnabled(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("widget_bg_image_enabled", enabled).apply()

    /** 获取实际应用的小组件背景 URI（考虑启用状态，禁用时返回空） */
    fun getAppliedWidgetBgImageUri(ctx: Context): String {
        return if (getWidgetBgImageEnabled(ctx)) getWidgetBgImageUri(ctx) else ""
    }

    /** 获取小组件背景历史列表（最多 3 条 URI） */
    fun getWidgetBgHistory(ctx: Context): List<String> {
        try {
            val json = getSp(ctx).getString("widget_bg_image_history", "[]") ?: "[]"
            val arr = org.json.JSONArray(json)
            return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /** 保存小组件背景历史列表 */
    fun setWidgetBgHistory(ctx: Context, history: List<String>) {
        val arr = org.json.JSONArray(history.take(MAX_BG_HISTORY))
        getSp(ctx).edit().putString("widget_bg_image_history", arr.toString()).apply()
    }

    /** 添加一条 URI 到背景历史（去重后插入首位，超出 3 条截断并清理旧文件） */
    fun addWidgetBgHistory(ctx: Context, uri: String) {
        val current = getWidgetBgHistory(ctx).toMutableList()
        current.remove(uri)          // 去重
        current.add(0, uri)          // 插入首位
        val trimmed = current.take(MAX_BG_HISTORY)
        // 删除被淘汰的历史文件
        val evicted = current.drop(MAX_BG_HISTORY)
        evicted.forEach { oldPath ->
            if (!oldPath.startsWith("content://")) {
                try { java.io.File(oldPath).delete() } catch (_: Exception) {}
            }
        }
        setWidgetBgHistory(ctx, trimmed)
    }

    /** 清除小组件背景（删除内部文件 + 清除 SP，保留历史不变） */
    fun clearWidgetBgImage(ctx: Context) {
        // 删除内部存储的背景文件
        val filePath = getWidgetBgImageUri(ctx)
        if (filePath.isNotBlank() && !filePath.startsWith("content://")) {
            try { java.io.File(filePath).delete() } catch (_: Exception) {}
        }
        getSp(ctx).edit()
            .remove("widget_bg_image_uri")
            .putBoolean("widget_bg_image_enabled", false)
            .apply()
    }

    private const val MAX_BG_HISTORY = 3

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

    // ==================== 通知提醒设置 ====================

    /** 通知总开关 */
    fun getNotificationEnabled(ctx: Context) = getSp(ctx).getBoolean("notification_enabled", false)
    fun setNotificationEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notification_enabled", enabled).apply()

    /** 各类型提醒开关 */
    fun getNotifyDailyFlow(ctx: Context) = getSp(ctx).getBoolean("notify_daily_flow", false)
    fun setNotifyDailyFlow(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_daily_flow", enabled).apply()

    fun getNotifyMonthlyFlow(ctx: Context) = getSp(ctx).getBoolean("notify_monthly_flow", false)
    fun setNotifyMonthlyFlow(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_monthly_flow", enabled).apply()

    fun getNotifyTemp(ctx: Context) = getSp(ctx).getBoolean("notify_temp", false)
    fun setNotifyTemp(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_temp", enabled).apply()

    fun getNotifyCpu(ctx: Context) = getSp(ctx).getBoolean("notify_cpu", false)
    fun setNotifyCpu(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_cpu", enabled).apply()

    fun getNotifyDeviceOnline(ctx: Context) = getSp(ctx).getBoolean("notify_device_online", false)
    fun setNotifyDeviceOnline(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_device_online", enabled).apply()

    fun getNotifyBattery(ctx: Context) = getSp(ctx).getBoolean("notify_battery", false)
    fun setNotifyBattery(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_battery", enabled).apply()

    fun getNotifyMemory(ctx: Context) = getSp(ctx).getBoolean("notify_memory", false)
    fun setNotifyMemory(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_memory", enabled).apply()

    /** 阈值设置 */
    // 今日流量阈值（字节，默认 1GB）
    fun getNotifyDailyFlowThreshold(ctx: Context) = getSp(ctx).getLong("notify_daily_flow_threshold", 1_073_741_824L)
    fun setNotifyDailyFlowThreshold(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("notify_daily_flow_threshold", bytes).apply()
    // 本月流量阈值（字节，默认 10GB）
    fun getNotifyMonthlyFlowThreshold(ctx: Context) = getSp(ctx).getLong("notify_monthly_flow_threshold", 10_737_418_240L)
    fun setNotifyMonthlyFlowThreshold(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("notify_monthly_flow_threshold", bytes).apply()
    // 温度阈值（℃，默认 70）
    fun getNotifyTempThreshold(ctx: Context) = getSp(ctx).getInt("notify_temp_threshold", 70)
    fun setNotifyTempThreshold(ctx: Context, temp: Int) = getSp(ctx).edit().putInt("notify_temp_threshold", temp).apply()
    // CPU 阈值（%，默认 80）
    fun getNotifyCpuThreshold(ctx: Context) = getSp(ctx).getInt("notify_cpu_threshold", 80)
    fun setNotifyCpuThreshold(ctx: Context, cpu: Int) = getSp(ctx).edit().putInt("notify_cpu_threshold", cpu).apply()
    // 电量阈值（%，默认 20）
    fun getNotifyBatteryThreshold(ctx: Context) = getSp(ctx).getInt("notify_battery_threshold", 20)
    fun setNotifyBatteryThreshold(ctx: Context, battery: Int) = getSp(ctx).edit().putInt("notify_battery_threshold", battery).apply()
    // 内存阈值（%，默认 90）
    fun getNotifyMemoryThreshold(ctx: Context) = getSp(ctx).getInt("notify_memory_threshold", 90)
    fun setNotifyMemoryThreshold(ctx: Context, mem: Int) = getSp(ctx).edit().putInt("notify_memory_threshold", mem).apply()

    /** 防抖时间戳 */
    fun getNotifyLastTime(ctx: Context, key: String) = getSp(ctx).getLong(key, 0L)
    fun setNotifyLastTime(ctx: Context, key: String, time: Long) = getSp(ctx).edit().putLong(key, time).apply()

    /** 设备在线状态记录（用于上下线检测） */
    fun getNotifyPrevOnline(ctx: Context) = getSp(ctx).getBoolean("notify_prev_online", true)
    fun setNotifyPrevOnline(ctx: Context, online: Boolean) = getSp(ctx).edit().putBoolean("notify_prev_online", online).apply()

    // ══════════════════════════════════════════════
    // 后台通知监控间隔
    // ══════════════════════════════════════════════

    /** 后台监控检查间隔（秒），默认 60 秒 */
    fun getMonitorIntervalSec(ctx: Context): Int = getSp(ctx).getInt("monitor_interval_sec", 60)
    fun setMonitorIntervalSec(ctx: Context, seconds: Int) = getSp(ctx).edit().putInt("monitor_interval_sec", seconds).apply()

    // ══════════════════════════════════════════════
    // 后台保活服务
    // ══════════════════════════════════════════════

    /** 后台保活前台服务开关 */
    fun getBackgroundServiceEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("background_service_enabled", false)
    fun setBackgroundServiceEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("background_service_enabled", enabled).apply()

    // ══════════════════════════════════════════════
    // 前台服务通知自定义
    // ══════════════════════════════════════════════

    /** 前台保活服务通知标题（空字符串 = 使用默认值） */
    fun getCustomNotifTitle(ctx: Context): String = getSp(ctx).getString("custom_notif_title", "").orEmpty()
    fun setCustomNotifTitle(ctx: Context, title: String) = getSp(ctx).edit().putString("custom_notif_title", title).apply()

    /** 前台保活服务通知内容（空字符串 = 使用默认值） */
    fun getCustomNotifText(ctx: Context): String = getSp(ctx).getString("custom_notif_text", "").orEmpty()
    fun setCustomNotifText(ctx: Context, text: String) = getSp(ctx).edit().putString("custom_notif_text", text).apply()

    // ══════════════════════════════════════════════
    // 保活增强功能
    // ══════════════════════════════════════════════

    /** 从最近任务中隐藏本应用 */
    fun getHideFromRecents(ctx: Context): Boolean = getSp(ctx).getBoolean("hide_from_recents", false)
    fun setHideFromRecents(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("hide_from_recents", enabled).apply()

    /** WorkManager 周期性保活任务开关 */
    fun getPeriodicWorkerEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("periodic_worker_enabled", false)
    fun setPeriodicWorkerEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("periodic_worker_enabled", enabled).apply()

    /** WorkManager 周期性保活任务间隔（分钟），默认 15，最小 15（WorkManager 限制） */
    fun getPeriodicWorkerIntervalMin(ctx: Context): Int = getSp(ctx).getInt("periodic_worker_interval_min", 15)
    fun setPeriodicWorkerIntervalMin(ctx: Context, minutes: Int) = getSp(ctx).edit().putInt("periodic_worker_interval_min", minutes.coerceAtLeast(15)).apply()

    /** 无障碍保活服务开关（记录用户意图，实际状态由系统控制） */
    fun getAccessibilityKeepAlive(ctx: Context): Boolean = getSp(ctx).getBoolean("accessibility_keep_alive", false)
    fun setAccessibilityKeepAlive(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("accessibility_keep_alive", enabled).apply()

}
