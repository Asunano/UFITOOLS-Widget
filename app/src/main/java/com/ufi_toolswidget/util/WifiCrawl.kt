package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

object WifiCrawl {
    
    private const val TAG = "WifiCrawl"
    
    @Volatile var lastRawResponse: String = ""
    @Volatile var lastError: String = ""

    /** AT 命令并发限流：嵌入式设备 HTTP 服务器资源有限，最多同时 4 路请求 */
    private val atConcurrencyLimit = kotlinx.coroutines.sync.Semaphore(permits = 4)

    // ── 预编译 Regex：避免每次调用重复编译 ──
    private val PORT_RE = Regex(":(\\d+)$")
    private val COPS_RE = Regex("""\+COPS:\s*\d+\s*,\s*\d+\s*,\s*"([^"]*)",?\s*(\d+)?""")
    private val COPS_NUM_RE = Regex("""\+COPS:\s*\d+\s*,\s*\d+\s*,\s*(\d+),?\s*(\d+)?""")
    private val BAND_RE = Regex("^[nNB]\\d+[A-Za-z]?\$")
    private val C5GREG_RE = Regex("""\+C5GREG:\s*(\d+)\s*,\s*(\d+)\s*,\s*"([^",]*)"\s*,\s*"([^",]*)"\s*,\s*(\d+)(?:,\s*(\d+))?""")
    private val WHITESPACE_RE = Regex("\\s+")
    private val IMEI_RE = Regex("""(\d{15,17})""")
    private val CREG_RE = Regex("""\+CREG:\s*(\d+)\s*,\s*(\d+)\s*,"?([^"",]*)"?\s*,\s*"?([^"",]*)"?\s*,\s*(\d+)""")
    private val CGCONTRDP_RE = Regex("""\+CGCONTRDP:\s*\d+\s*,\s*\d+\s*,[\w.\-]*,"([^"]*)",[^"]*,[^"]*,[^"]*,\s*"?([^"",]*)"?\s*,\s*"?([^"",]*)"?""")
    private val CPIN_RE = Regex("""\+CPIN:\s*(.+)""")
    private val CFUN_RE = Regex("""\+CFUN:\s*(\d+)""")
    private val CPAS_RE = Regex("""\+CPAS:\s*(\d+)""")
    private val CGATT_RE = Regex("""\+CGATT:\s*(\d+)""")
    private val AT_OK_RE = Regex("(^|\n)OK$")

    // ── 预编码 AT 命令常量：避免每次调用 URLEncoder.encode ──
    private val COPS_CMD = java.net.URLEncoder.encode("AT+COPS?", "UTF-8")
    private val CESQ_CMD = java.net.URLEncoder.encode("AT+CESQ", "UTF-8")
    private val CGSN_CMD = java.net.URLEncoder.encode("AT+CGSN", "UTF-8")
    private val CGEQOS_CMD = java.net.URLEncoder.encode("AT+CGEQOSRDP=1", "UTF-8")
    private val QENG_CMD = java.net.URLEncoder.encode("AT+QENG=\"servingcell\"", "UTF-8")
    private val C5GREG_CMD = java.net.URLEncoder.encode("AT+C5GREG?", "UTF-8")
    private val CGMM_CMD = java.net.URLEncoder.encode("AT+CGMM", "UTF-8")
    private val CGMR_CMD = java.net.URLEncoder.encode("AT+CGMR", "UTF-8")
    private val CREG_CMD = java.net.URLEncoder.encode("AT+CREG?", "UTF-8")
    private val CGCONTRDP_CMD = java.net.URLEncoder.encode("AT+CGCONTRDP=1", "UTF-8")
    private val CPIN_CMD = java.net.URLEncoder.encode("AT+CPIN?", "UTF-8")
    private val CFUN_CMD = java.net.URLEncoder.encode("AT+CFUN?", "UTF-8")
    private val CPAS_CMD = java.net.URLEncoder.encode("AT+CPAS", "UTF-8")
    private val CGATT_CMD = java.net.URLEncoder.encode("AT+CGATT?", "UTF-8")

    suspend fun getWifiData(context: Context, quickStart: Boolean = false): WifiEntity? = withContext(Dispatchers.IO) {
        try {
            lastError = ""
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)
            val baseUrl = SPUtil.buildBaseUrl(context)
            
            DebugLogger.logApi(TAG, "Starting data refresh from $baseUrl" + if (quickStart) " (quick start)" else "")

            // 分批获取：先单独获取 baseDeviceInfo（含 CPU），避免并发请求拉高设备 CPU 导致读数虚高
            var baseDeviceInfo: org.json.JSONObject? = null
            var signalInfo: org.json.JSONObject? = null
            var goformDeviceInfo: org.json.JSONObject? = null
            var versionInfo: org.json.JSONObject? = null
            var tokenInfo: org.json.JSONObject? = null
            var atNetworkInfo: AtSignalInfo? = null
            // 在并发请求前预先解析的 CPU 值
            var preCpuUsage = -1.0
            var preCpuUsageMap = emptyMap<String, String>()

            coroutineScope {
                // 冷却期：快速启动时跳过，正常刷新时等待 1-2s，让设备 CPU 从上轮请求中恢复
                if (!quickStart) {
                    delay(1000L + (Math.random() * 1000).toLong())
                }

                // Step 1: 单独获取 baseDeviceInfo，避免其他请求干扰 CPU 读数
                val baseResp = fetchApi(SPUtil.getDeviceInfoPath(context), t, auth, context)
                if (baseResp == null) {
                    DebugLogger.logApiErr(TAG, "Failed to fetch baseDeviceInfo: $lastError")
                    return@coroutineScope
                }
                baseDeviceInfo = baseResp
                // 在并发请求前立即解析 CPU 占用
                preCpuUsage = baseResp.optDouble("cpu_usage", -1.0)
                val map = mutableMapOf<String, String>()
                baseResp.optJSONObject("cpuUsageInfo")?.let { usageObj ->
                    usageObj.keys().forEach { key ->
                        map[key] = usageObj.optString(key, "--")
                    }
                }
                preCpuUsageMap = map

                // 冷却期：快速启动时跳过，正常刷新时等待 1-2s 再发起后续请求，确保 CPU 读数已被捕获
                if (!quickStart) {
                    delay(1000L + (Math.random() * 1000).toLong())
                }

                // Step 2: 其余请求并发执行
                val signalDeferred = async {
                    fetchApi("${SPUtil.getGoformCommandPath(context)}?is_all=true&cmd=lte_rsrp,Z5g_rsrp,network_type,rssi", t, auth, context)
                }
                val goformDeviceDeferred = async {
                    fetchApi("${SPUtil.getGoformCommandPath(context)}?is_all=true&cmd=wan_ipaddr,ipv6_wan_ipaddr,pdp_type,imei,imsi,iccid,hardware_version,web_version,mac_address,pin_status", t, auth, context)
                }
                val versionDeferred = async {
                    if (SPUtil.isVersionInfoCacheFresh(context)) {
                        val cached = SPUtil.getCachedVersionInfoJson(context)
                        if (cached.isNotEmpty()) {
                            DebugLogger.logApi(TAG, "version_info: using cache")
                            return@async JSONObject(cached)
                        }
                    }
                    fetchApiNoAuth(SPUtil.getVersionInfoPath(context), t, context)?.also {
                        SPUtil.setCachedVersionInfoJson(context, it.toString())
                        SPUtil.setVersionInfoCacheTime(context, System.currentTimeMillis())
                    }
                }
                val tokenDeferred = async {
                    if (SPUtil.isNeedTokenCacheFresh(context)) {
                        val cached = SPUtil.getCachedNeedTokenJson(context)
                        if (cached.isNotEmpty()) {
                            DebugLogger.logApi(TAG, "need_token: using cache")
                            return@async JSONObject(cached)
                        }
                    }
                    fetchApiNoAuth(SPUtil.getNeedTokenPath(context), t, context)?.also {
                        SPUtil.setCachedNeedTokenJson(context, it.toString())
                        SPUtil.setNeedTokenCacheTime(context, System.currentTimeMillis())
                    }
                }
                val atNetworkDeferred = async { fetchAtNetworkInfo(t, auth, context) }

                signalInfo = signalDeferred.await()
                goformDeviceInfo = goformDeviceDeferred.await()
                versionInfo = versionDeferred.await()
                tokenInfo = tokenDeferred.await()
                atNetworkInfo = atNetworkDeferred.await()
            }
            if (baseDeviceInfo == null) {
                DebugLogger.flushToFile()
                return@withContext null
            }

            DebugLogger.logApi(TAG, "Goform signal check: success=${signalInfo != null}")
            DebugLogger.logApi(TAG, "Goform device info: success=${goformDeviceInfo != null}")
            DebugLogger.logApi(TAG, "Parallel tasks finished. atNetworkInfo success=${atNetworkInfo != null}")

            // --- 精准解析（CPU 已在并发前预解析） ---
            val model = baseDeviceInfo.optString("model", "F50")
            val batteryPercent = baseDeviceInfo.optInt("battery", -1)
            val cpuUsage = preCpuUsage
            val cpuUsageMap = preCpuUsageMap
            val memUsage = baseDeviceInfo.optDouble("mem_usage", 0.0)
            
            // === Goform 设备身份+网络地址 ===
            val wanIp = goformDeviceInfo?.optString("wan_ipaddr", "") ?: ""
            val wanIpv6 = goformDeviceInfo?.optString("ipv6_wan_ipaddr", "") ?: ""
            val pdpType = goformDeviceInfo?.optString("pdp_type", "") ?: ""
            val goformImei = goformDeviceInfo?.optString("imei", "") ?: ""
            val goformImsi = goformDeviceInfo?.optString("imsi", "") ?: ""
            val goformIccid = goformDeviceInfo?.optString("iccid", "") ?: ""
            val hwVersion = goformDeviceInfo?.optString("hardware_version", "") ?: ""
            val wbVersion = goformDeviceInfo?.optString("web_version", "") ?: ""
            val macAddr = goformDeviceInfo?.optString("mac_address", "") ?: ""
            val pinStatusCode = goformDeviceInfo?.optInt("pin_status", -1) ?: -1

            // ── 补全运营商名称：如果 AT 命令没拿到，尝试通过 Goform 的 IMSI 识别 ──
            if (atNetworkInfo != null && atNetworkInfo.carrier.isEmpty() && goformImsi.isNotEmpty()) {
                val derived = mapPlmnToCarrier(goformImsi)
                if (derived.isNotEmpty()) {
                    atNetworkInfo = atNetworkInfo.copy(carrier = derived)
                }
            }

            // --- 流量提取 (仅从 baseDeviceInfo) ---
            val dTx = extractTrafficBytes(baseDeviceInfo, "daily_tx_bytes")
            val dRx = extractTrafficBytes(baseDeviceInfo, "daily_rx_bytes")
            val dTotal = extractTrafficBytes(baseDeviceInfo, "daily_data", "day_data")
            val dailyRaw = if (dTotal > 0) dTotal else (dTx + dRx)

            val mTx = extractTrafficBytes(baseDeviceInfo, "monthly_tx_bytes")
            val mRx = extractTrafficBytes(baseDeviceInfo, "monthly_rx_bytes")
            val mTotal = extractTrafficBytes(baseDeviceInfo, "monthly_data", "month_data", "total_data")
            var monthlyRaw = if (mTotal > 0) mTotal else (mTx + mRx)

            // 如果 API 没返回月流量，用缓存
            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            } else {
                SPUtil.setCachedMonthlyData(context, monthlyRaw)
            }
            Log.d(TAG, "dailyRaw=$dailyRaw, monthlyRaw=$monthlyRaw (tx=$mTx, rx=$mRx)")
            
            // 温度 (60620 -> 60.6)
            val tempRaw = baseDeviceInfo.optDouble("cpu_temp", 0.0)

            // === /api/baseDeviceInfo 新增字段 ===
            val appVer = baseDeviceInfo.optString("app_ver", "")
            val appVerCode = baseDeviceInfo.optString("app_ver_code", "")
            val currentNow = baseDeviceInfo.optInt("current_now", -1)        // 微安 µA
            val voltageNow = baseDeviceInfo.optInt("voltage_now", -1)        // 微伏 µV
            val internalTotal = baseDeviceInfo.optLong("internal_total_storage", -1L)
            val internalUsed = baseDeviceInfo.optLong("internal_used_storage", -1L)
            val internalAvailable = baseDeviceInfo.optLong("internal_available_storage", -1L)
            val externalTotal = baseDeviceInfo.optLong("external_total_storage", -1L)
            val externalUsed = baseDeviceInfo.optLong("external_used_storage", -1L)
            val externalAvailable = baseDeviceInfo.optLong("external_available_storage", -1L)
            val clientIp = baseDeviceInfo.optString("client_ip", "")

            // === cpu_temp_list 各模块温度 ===
            val cpuTempList = mutableListOf<CpuTempItem>()
            baseDeviceInfo.optJSONArray("cpu_temp_list")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    cpuTempList.add(CpuTempItem(
                        type = obj.optString("type", "unknown"),
                        temp = obj.optDouble("temp", 0.0)
                    ))
                }
            }

            // === cpuFreqInfo 各核心频率 ===
            val cpuFreqMap = mutableMapOf<String, CpuFreqItem>()
            baseDeviceInfo.optJSONObject("cpuFreqInfo")?.let { freqObj ->
                freqObj.keys().forEach { key ->
                    val core = freqObj.optJSONObject(key) ?: return@forEach
                    cpuFreqMap[key] = CpuFreqItem(
                        cur = core.optInt("cur", 0),
                        max = core.optInt("max", 0)
                    )
                }
            }

            // CPU 总体占用（preCpuUsage 已在并发请求前采集，避免虚高）
            val finalCpuUsage = if (preCpuUsage in 0.0..100.0) preCpuUsage
                else preCpuUsageMap["cpu"]?.toDoubleOrNull() ?: 0.0

            // === memInfo 详细内存信息 ===
            var memTotalKb = 0L
            var memAvailableKb = 0L
            var memUsedKb = 0L
            var swapTotalKb = 0L
            var swapUsedKb = 0L
            var swapFreeKb = 0L
            baseDeviceInfo.optJSONObject("memInfo")?.let { memObj ->
                memTotalKb = memObj.optLong("mem_total_kb", 0L)
                memAvailableKb = memObj.optLong("mem_available_kb", 0L)
                memUsedKb = memObj.optLong("mem_used_kb", 0L)
                swapTotalKb = memObj.optLong("swap_total_kb", 0L)
                swapUsedKb = memObj.optLong("swap_used_kb", 0L)
                swapFreeKb = memObj.optLong("swap_free_kb", 0L)
            }

            // === /api/version_info 字段 ===
            val deviceModel = versionInfo?.optString("model", "") ?: ""
            val firmwareVer = versionInfo?.optString("app_ver", "") 
                ?: versionInfo?.optString("version", "") ?: ""

            // === /api/need_token 字段 ===
            val needToken = tokenInfo?.optBoolean("need_token", false) ?: false

            // 信号解析逻辑
            // 优先使用 Goform API 的 network_type；若为空则回退到 AT 命令的 networkType
            val netType = signalInfo?.optString("network_type")?.ifBlank { null }
                ?: atNetworkInfo?.networkType?.ifBlank { null }
                ?: ""
            val goformSignalRaw = when {
                netType.contains("5G") -> signalInfo?.optString("Z5g_rsrp")
                else -> signalInfo?.optString("lte_rsrp")
            } ?: signalInfo?.optString("rssi") ?: "--"

            // AT 命令的 RSRP 更可靠（直接来自基带芯片），优先用于信号图标显示
            // goform API 作为回退（部分设备可能不返回有效 dBm 值）
            val signalStr = run {
                val atRsrp = atNetworkInfo?.rsrp
                if (atRsrp != null && atRsrp < 0 && atRsrp > Int.MIN_VALUE) {
                    DebugLogger.logApi(TAG, "Signal: using AT RSRP=${atRsrp}dBm (goform=$goformSignalRaw)")
                    "${atRsrp}dBm"
                } else {
                    // goform 回退：部分设备返回正值（绝对值），需修正为负 dBm
                    val goformInt = goformSignalRaw.toIntOrNull()
                    val s = when {
                        goformSignalRaw.isEmpty() || goformSignalRaw == "null" || goformSignalRaw == "--" -> "--"
                        goformInt != null && goformInt > 0 -> "${-goformInt}dBm"
                        else -> "${goformSignalRaw}dBm"
                    }
                    DebugLogger.logApi(TAG, "Signal: using goform=$s (AT RSRP=${atRsrp ?: "null"})")
                    s
                }
            }

            DebugLogger.flushToFile() // 本轮数据刷新完毕，批量落盘

            WifiEntity(
                model = model,
                flow = formatFlow(monthlyRaw),
                dailyFlow = formatFlow(dailyRaw),
                signal = signalStr,
                temp = formatTemp(tempRaw),
                battery = if (batteryPercent >= 0) "${batteryPercent}%" else "--",
                batteryPercent = if (batteryPercent >= 0) batteryPercent else -1,
                cpu = String.format(Locale.getDefault(), "%.1f%%", finalCpuUsage),
                mem = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                netType = netType,
                appVer = appVer,
                appVerCode = appVerCode,
                batteryCurrent = formatCurrent(currentNow),
                batteryVoltage = formatVoltage(voltageNow),
                internalStorage = formatStorage(internalTotal, internalUsed),
                internalAvailableStorage = internalAvailable,
                internalTotalStorage = internalTotal,
                internalUsedStorage = internalUsed,
                externalTotalStorage = externalTotal,
                externalUsedStorage = externalUsed,
                externalAvailableStorage = externalAvailable,
                clientIp = clientIp,
                deviceModel = deviceModel.ifEmpty { model },
                firmwareVer = firmwareVer,
                needToken = needToken,
                atNetworkInfo = atNetworkInfo,
                cpuTempList = cpuTempList,
                cpuFreqInfo = cpuFreqMap,
                cpuUsageInfo = cpuUsageMap,
                memTotalKb = memTotalKb,
                memAvailableKb = memAvailableKb,
                memUsedKb = memUsedKb,
                swapTotalKb = swapTotalKb,
                swapUsedKb = swapUsedKb,
                swapFreeKb = swapFreeKb,
                // === Goform 新增字段 ===
                wanIp = wanIp,
                wanIpv6 = wanIpv6,
                pdpTypeGoform = pdpType,
                goformImei = goformImei,
                goformImsi = goformImsi,
                goformIccid = goformIccid,
                hardwareVersion = hwVersion,
                webVersion = wbVersion,
                macAddress = macAddr,
                pinStatusCode = pinStatusCode,
                monthlyUploadBytes = mTx,
                monthlyDownloadBytes = mRx,
                dailyRawBytes = dailyRaw,
                monthlyRawBytes = monthlyRaw
            )
        } catch (e: CancellationException) {
            // 协程被取消 → 不视为错误，直接重新抛出让协程栈正确处理
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "Parse Error: ${e.message}"
            DebugLogger.flushToFile() // 异常落盘
            null
        }
    }

    /** 封装 HTTP 响应（body 已读取，连接已由 executeRequest 内部关闭） */
    private data class HttpResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String
    )

    /**
     * 非阻塞网络请求：使用 OkHttp 异步 enqueue + suspendCancellableCoroutine，
     * 不阻塞 IO 线程，且支持协程取消。
     * 内部自动关闭 Response，调用方无需手动 use{}，从根源消除连接泄漏风险。
     * 每次 resume 前检查 isActive，防止协程取消后 OkHttp 回调仍触发导致 IllegalStateException。
     */
    private suspend fun executeRequest(req: Request): HttpResponse? =
        suspendCancellableCoroutine { continuation ->
            val call = NetUtil.client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = it.body?.string() ?: ""
                            if (continuation.isActive) {
                                continuation.resume(HttpResponse(it.code, it.isSuccessful, body))
                            }
                        }
                    } catch (e: IOException) {
                        // body.string() 中途断网抛异常 → OkHttp 已回调 onResponse 不会调 onFailure
                        // 必须手动 resume，否则协程永久挂起
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
        }

    private suspend fun fetchApi(path: String, t: Long, auth: String, context: Context): JSONObject? {
        val purePath = if (path.contains("?")) path.substringBefore("?") else path
        val sign = NetUtil.generateKanoSign("GET", purePath, t, context)
        
        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .addHeader("Authorization", auth)
            .build()
            
        val resp = executeRequest(req) ?: run {
            lastError = "Network error (timeout or unreachable)"
            DebugLogger.logApiErr(TAG, "Request failed: $baseUrl$path - $lastError")
            return null
        }
        lastRawResponse = resp.body

        return if (resp.isSuccessful && resp.body.isNotEmpty()) {
            DebugLogger.logApi(TAG, "API Success [$path], code=${resp.code}, resp=${resp.body}")
            try {
                JSONObject(resp.body)
            } catch (e: org.json.JSONException) {
                // 单个端点 JSON 解析失败不应影响其他并发请求
                lastError = "JSON parse error on $purePath: ${e.message}"
                DebugLogger.logApiErr(TAG, "fetchApi JSON parse failed: ${e.message}")
                null
            }
        } else {
            lastError = "HTTP ${resp.code} on $purePath"
            DebugLogger.logApiErr(TAG, "API Error [$path], code=${resp.code}, resp=${resp.body}")
            null
        }
    }

    /** 无需认证的 API 请求 (version_info / need_token) */
    private suspend fun fetchApiNoAuth(path: String, t: Long, context: Context): JSONObject? {
        val sign = NetUtil.generateKanoSign("GET", path, t, context)
        val baseUrl = SPUtil.buildBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .build()

        val resp = executeRequest(req) ?: run {
            DebugLogger.logApiErr(TAG, "fetchApiNoAuth request failed: $path")
            return null
        }
        return if (resp.isSuccessful && resp.body.isNotEmpty()) {
            try {
                JSONObject(resp.body)
            } catch (e: org.json.JSONException) {
                DebugLogger.logApiErr(TAG, "fetchApiNoAuth JSON parse failed on $path: ${e.message}")
                null
            }
        } else {
            DebugLogger.logApiErr(TAG, "fetchApiNoAuth HTTP ${resp.code} on $path")
            null
        }
    }

    /**
     * 从 JSON 中提取流量字节数，自动适配不同单位和字段名。
     * 
     * 策略：
     * 1. 优先信任字段名：含 "bytes" 或以 "_data" 结尾的字段，始终视为 Bytes。
     * 2. 只有在字段名不明确（如 "flow"）且数值极小时，才进行单位推断。
     * 3. 增加单位推断的安全性，防止误乘导致流量显示异常。
     */
    private fun extractTrafficBytes(json: JSONObject?, vararg keys: String): Long {
        if (json == null) return 0L
        
        for (key in keys) {
            val keyLower = key.lowercase()
            // 明确定义为字节的字段名
            val isExplicitBytes = keyLower.contains("bytes") || 
                                 keyLower.endsWith("_data") || 
                                 keyLower.contains("traffic")
            
            val longVal = json.optLong(key, -1L)
            if (longVal >= 0) {
                if (isExplicitBytes || longVal > 500_000_000L) { // >500MB 极大概率是 Bytes
                    Log.d(TAG, "  $key=$longVal -> 识别为 Bytes")
                    return longVal
                }
                
                // 模糊字段名且数值较小：保守推断
                return when {
                    longVal == 0L -> 0L
                    longVal < 100L -> { // 如 1.5 或 80，推断为 GB
                        Log.d(TAG, "  $key=$longVal -> 推断为 GB")
                        longVal * 1024L * 1024L * 1024L
                    }
                    longVal < 100_000L -> { // 如 1500，推断为 MB
                        Log.d(TAG, "  $key=$longVal -> 推断为 MB")
                        longVal * 1024L * 1024L
                    }
                    else -> { // 其他情况默认 Bytes，不再乘以 1024 防止溢出
                        Log.d(TAG, "  $key=$longVal -> 默认识别为 Bytes")
                        longVal
                    }
                }
            }
            
            // 字符串处理
            val strVal = json.optString(key, "")
            if (strVal.isNotEmpty() && strVal != "null") {
                val parsed = strVal.toDoubleOrNull()
                if (parsed != null && parsed >= 0) {
                    if (isExplicitBytes || parsed > 500_000_000.0) return parsed.toLong()
                    
                    return when {
                        parsed < 100.0 -> (parsed * 1024.0 * 1024.0 * 1024.0).toLong()
                        parsed < 100_000.0 -> (parsed * 1024.0 * 1024.0).toLong()
                        else -> parsed.toLong()
                    }
                }
            }
        }
        return 0L
    }

    private fun formatFlow(bytes: Long): String {
        if (bytes <= 0) return "0.00 GB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    private fun formatTemp(raw: Double): String {
        if (raw <= 0) return "--"
        val celsius = if (raw > 1000) raw / 1000.0 else raw
        return String.format(Locale.getDefault(), "%.1f℃", celsius)
    }

    /** 电流格式化: µA → mA (微安转毫安) */
    private fun formatCurrent(currentUa: Int): String {
        if (currentUa < 0) return "--"
        val ma = currentUa / 1000.0
        return String.format(Locale.getDefault(), "%.0fmA", ma)
    }

    /** 电压格式化: µV → V (微伏转伏) */
    private fun formatVoltage(voltageUv: Int): String {
        if (voltageUv < 0) return "--"
        val v = voltageUv / 1_000_000.0
        return String.format(Locale.getDefault(), "%.2fV", v)
    }

    /** 存储格式化: Bytes → GB */
    private fun formatStorage(total: Long, used: Long): String {
        if (total <= 0) return "--"
        val usedGb = used / (1024.0 * 1024.0 * 1024.0)
        val totalGb = total / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.1f / %.1f GB", usedGb, totalGb)
    }

    // ══════════════════════════════════════════════════════════════
    // 轻量级通知监控数据获取
    // ══════════════════════════════════════════════════════════════

    /**
     * 从设备信息 API 中提取的轻量级通知监控数据。
     * 仅包含通知阈值检查所需的字段，不包含完整设备状态。
     */
    data class NotificationBaseInfo(
        val dailyFlowStr: String,
        val monthlyFlowStr: String,
        val tempStr: String,
        val cpuStr: String,
        val memStr: String,
        val batteryPercent: Int
    )

    /**
     * 轻量级数据获取，仅用于通知监控。
     *
     * 与 [getWifiData] 不同，此方法：
     * - 只请求 /api/deviceInfo 一个端点，没有冷却延迟
     * - 不发起并发请求（无 signal / goform / version / token / AT 请求）
     * - 仅解析通知阈值检查所需的字段
     *
     * 性能开销约为完整 [getWifiData] 的 1/6 ~ 1/10，
     * 适合高频调用（如 60 秒间隔）。
     */
    suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo? = withContext(Dispatchers.IO) {
        try {
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)
            val baseResp = fetchApi(SPUtil.getDeviceInfoPath(context), t, auth, context) ?: return@withContext null

            // 提取通知所需字段
            val batteryPercent = baseResp.optInt("battery", -1)
            val cpuUsage = baseResp.optDouble("cpu_usage", -1.0)
            val memUsage = baseResp.optDouble("mem_usage", 0.0)
            val tempRaw = baseResp.optDouble("cpu_temp", 0.0)

            val dTx = extractTrafficBytes(baseResp, "daily_tx_bytes")
            val dRx = extractTrafficBytes(baseResp, "daily_rx_bytes")
            val dTotal = extractTrafficBytes(baseResp, "daily_data", "day_data")
            val dailyRaw = if (dTotal > 0) dTotal else (dTx + dRx)

            val mTx = extractTrafficBytes(baseResp, "monthly_tx_bytes")
            val mRx = extractTrafficBytes(baseResp, "monthly_rx_bytes")
            val mTotal = extractTrafficBytes(baseResp, "monthly_data", "month_data", "total_data")
            var monthlyRaw = if (mTotal > 0) mTotal else (mTx + mRx)

            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            }

            val finalCpuUsage = if (cpuUsage in 0.0..100.0) cpuUsage else 0.0

            NotificationBaseInfo(
                dailyFlowStr = formatFlow(dailyRaw),
                monthlyFlowStr = formatFlow(monthlyRaw),
                tempStr = formatTemp(tempRaw),
                cpuStr = String.format(Locale.getDefault(), "%.1f%%", finalCpuUsage),
                memStr = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                batteryPercent = if (batteryPercent >= 0) batteryPercent else -1
            )
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播，不能被吞掉
        } catch (e: Exception) {
            lastError = "Notification fetch: ${e.message}"
            DebugLogger.logApiErr(TAG, "fetchNotificationBaseInfo: ${e.message}")
            null
        }
    }

    // ===== 协议自动探测 =====

    /**
     * 自动探测目标主机支持的协议（HTTPS 优先，失败回退 HTTP）。
     * 通过请求 /api/need_token（无需认证）验证连接是否可用。
     *
     * @return "https" / "http" / null（两者均不可达）
     */
    suspend fun probeProtocol(context: Context): String? = withContext(Dispatchers.IO) {
        val host = SPUtil.getDeviceHost(context)
        // 私有 IP 无需探测，始终 HTTP
        if (SPUtil.isPrivateOrLocalIp(host)) return@withContext null

        // 获取用户填写的端口（如果有）
        val raw = SPUtil.getDeviceAddress(context).trim()
        val userPort = PORT_RE.find(raw)?.groupValues?.get(1)?.toIntOrNull()


        val testPath = SPUtil.getNeedTokenPath(context)
        val t = System.currentTimeMillis()

        // 1) 先试 HTTPS
        val httpsPort = userPort ?: 443
        val httpsUrl = "https://$host:$httpsPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTPS $httpsUrl")
        if (tryProbeRequest(httpsUrl, t, context)) {
            Log.d(TAG, "probeProtocol: HTTPS OK")
            return@withContext "https"
        }

        // 2) 回退 HTTP
        val httpPort = userPort ?: 80
        val httpUrl = "http://$host:$httpPort$testPath"
        Log.d(TAG, "probeProtocol: trying HTTP $httpUrl")
        if (tryProbeRequest(httpUrl, t, context)) {
            Log.d(TAG, "probeProtocol: HTTP OK")
            return@withContext "http"
        }

        Log.d(TAG, "probeProtocol: both failed")
        null
    }

    /** 单次探测请求：成功返回 true（响应是合法 JSON） */
    private suspend fun tryProbeRequest(url: String, t: Long, context: Context): Boolean {
        return try {
            val sign = NetUtil.generateKanoSign("GET", SPUtil.getNeedTokenPath(context), t, context)
            val req = Request.Builder()
                .url(url)
                .addHeader("kano-t", t.toString())
                .addHeader("kano-sign", sign)
                .build()
            val resp = executeRequest(req) ?: return false
            if (!resp.isSuccessful) return false
            resp.body.trimStart().startsWith("{")  // 合法 JSON 对象
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播
        } catch (e: Exception) {
            false
        }
    }

    // ===== AT 指令网络详情 =====

    /**
     * 通过 /api/AT 接口获取网络信号详情。
     *
     * **平台自适应路由**：
     * - 首次调用通过 AT+CGMI 检测芯片平台（Spreadtrum / Quectel），结果缓存到磁盘
     * - **展讯平台**：用 AT+C5GREG?（NR 注册状态）+ AT+CESQ（信号质量）+ AT+COPS?（运营商）
     * - **Quectel 平台**：用 AT+QENG="servingcell"（信号详情）+ AT+COPS?（运营商）
     *
     * 并行执行 5 路 AT 请求，失败不影响主流程。
     */
    private suspend fun fetchAtNetworkInfo(t: Long, auth: String, context: Context): AtSignalInfo? {
        try {
            val atPath = SPUtil.getAtCommandPath(context)

            // ── 平台检测（缓存优先，仅首次探测）──
            var platform = SPUtil.getCachedPlatform(context)
            if (platform.isEmpty()) {
                val cgmiCmd = java.net.URLEncoder.encode("AT+CGMI", "UTF-8")
                val cgmiResp = fetchApi("$atPath?command=$cgmiCmd&slot=0", t, auth, context)
                val cgmiStr = cgmiResp?.optString("result")?.ifEmpty { cgmiResp?.optString("response") } ?: ""
                platform = when {
                    cgmiStr.contains("Spreadtrum", ignoreCase = true) -> "spreadtrum"
                    cgmiStr.contains("Quectel", ignoreCase = true) -> "quectel"
                    cgmiStr.isNotEmpty() -> "other"
                    else -> ""
                }
                if (platform.isNotEmpty()) {
                    SPUtil.setCachedPlatform(context, platform)
                }
                Log.d(TAG, "Platform detected: $platform (CGMI: $cgmiStr)")
            }

            val isSpreadtrum = platform == "spreadtrum"

            // ── 使用预编码常量（避免每次调用重复 URLEncoder.encode） ──
            val copsCmd = COPS_CMD
            val cesqCmd = CESQ_CMD
            val cgsnCmd = CGSN_CMD
            val cgeqosCmd = CGEQOS_CMD
            val qengCmd = QENG_CMD
            val c5gregCmd = C5GREG_CMD
            // 新增 AT 命令
            val cgmmCmd = CGMM_CMD
            val cgmrCmd = CGMR_CMD
            val cregCmd = CREG_CMD
            val cgcontrdpCmd = CGCONTRDP_CMD
            val cpinCmd = CPIN_CMD
            val cfunCmd = CFUN_CMD
            val cpasCmd = CPAS_CMD
            val cgattCmd = CGATT_CMD

            // ── 辅助函数（Semaphore 限流，最多 4 路并发） ──
            suspend fun atQuery(cmd: String): String = atConcurrencyLimit.withPermit {
                val resp = fetchApi("$atPath?command=$cmd&slot=0", t, auth, context)
                resp?.optString("result")?.ifEmpty { resp.optString("response") } ?: ""
            }

            val results = kotlinx.coroutines.coroutineScope {
                // 每个 async 内部独立 catch，避免单路异常导致 awaitAll 全部取消
                val copsDef = async { try { atQuery(copsCmd) } catch (_: Exception) { "" } }
                val signalDetailDef = async { try { atQuery(if (isSpreadtrum) c5gregCmd else qengCmd) } catch (_: Exception) { "" } }
                val cesqDef = async { try { atQuery(cesqCmd) } catch (_: Exception) { "" } }
                val cgeqosDef = async { try { atQuery(cgeqosCmd) } catch (_: Exception) { "" } }
                val cregDef = async { try { atQuery(cregCmd) } catch (_: Exception) { "" } }
                val cgcontrdpDef = async { try { atQuery(cgcontrdpCmd) } catch (_: Exception) { "" } }
                val cpinDef = async { try { atQuery(cpinCmd) } catch (_: Exception) { "" } }
                val cfunDef = async { try { atQuery(cfunCmd) } catch (_: Exception) { "" } }
                val cpasDef = async { try { atQuery(cpasCmd) } catch (_: Exception) { "" } }
                val cgattDef = async { try { atQuery(cgattCmd) } catch (_: Exception) { "" } }

                // AT 静态字段缓存：CGMM/CGMR/CGSN 几乎不变，缓存命中时跳过 3 路网络请求
                val atStaticFresh = SPUtil.isAtStaticCacheFresh(context)
                val cgmmDef: Deferred<String>
                val cgmrDef: Deferred<String>
                val cgsnDef: Deferred<String>
                if (atStaticFresh) {
                    val cachedCgmm = SPUtil.getCachedModuleModel(context)
                    val cachedCgmr = SPUtil.getCachedFirmwareDetail(context)
                    val cachedCgsn = SPUtil.getCachedImeiFromAt(context)
                    cgmmDef = async { cachedCgmm }
                    cgmrDef = async { cachedCgmr }
                    cgsnDef = async { cachedCgsn }
                    Log.d(TAG, "AT static cache: hit, skipping CGMM/CGMR/CGSN requests")
                } else {
                    cgmmDef = async { try { atQuery(cgmmCmd) } catch (_: Exception) { "" } }
                    cgmrDef = async { try { atQuery(cgmrCmd) } catch (_: Exception) { "" } }
                    cgsnDef = async { try { atQuery(cgsnCmd) } catch (_: Exception) { "" } }
                }

                awaitAll(copsDef, signalDetailDef, cesqDef, cgsnDef, cgeqosDef,
                    cgmmDef, cgmrDef, cregDef, cgcontrdpDef, cpinDef,
                    cfunDef, cpasDef, cgattDef)
            }

            val copsStr = results[0]
            val signalDetailStr = results[1]
            val cesqStr = results[2]
            val cgsnStr = results[3]
            val cgeqosStr = results[4]
            val cgmmStr = results[5]
            val cgmrStr = results[6]
            val cregStr = results[7]
            val cgcontrdpStr = results[8]
            val cpinStr = results[9]
            val cfunStr = results[10]
            val cpasStr = results[11]
            val cgattStr = results[12]

            // ── 更新 AT 静态字段缓存（网络获取到的非空值写入 SP，供下轮缓存命中使用） ──
            if (!SPUtil.isAtStaticCacheFresh(context)) {
                var atStaticUpdated = false
                if (cgmmStr.isNotEmpty()) { SPUtil.setCachedModuleModel(context, cgmmStr); atStaticUpdated = true }
                if (cgmrStr.isNotEmpty()) { SPUtil.setCachedFirmwareDetail(context, cgmrStr); atStaticUpdated = true }
                if (cgsnStr.isNotEmpty()) { SPUtil.setCachedImeiFromAt(context, cgsnStr); atStaticUpdated = true }
                if (atStaticUpdated) SPUtil.setAtStaticCacheTime(context, System.currentTimeMillis())
            }

            val qengStr = if (!isSpreadtrum) signalDetailStr else ""
            val c5gregStr = if (isSpreadtrum) signalDetailStr else ""

            val hasAnyData = listOf(copsStr, signalDetailStr, cesqStr, cgsnStr, cgeqosStr,
                cgmmStr, cgmrStr, cregStr, cgcontrdpStr, cpinStr,
                cfunStr, cpasStr, cgattStr
            ).any { it.isNotEmpty() }

            if (!hasAnyData) return null

            return parseAtResponses(
                copsRaw = copsStr, qengRaw = qengStr, cesqRaw = cesqStr,
                cgsnRaw = cgsnStr, cgeqosRaw = cgeqosStr, c5gregRaw = c5gregStr,
                platform = platform,
                cgmmRaw = cgmmStr, cgmrRaw = cgmrStr, cregRaw = cregStr,
                cgcontrdpRaw = cgcontrdpStr, cpinRaw = cpinStr,
                cfunRaw = cfunStr, cpasRaw = cpasStr, cgattRaw = cgattStr
            )
        } catch (e: Exception) {
            Log.w(TAG, "AT network info fetch failed: ${e.message}")
            return null
        }
    }

    /**
     * 解析 AT 响应。
     *
     * COPS → 运营商（含 PLMN 映射为真实名称）+ ACT → 网络制式
     * Quectel: QENG → 原生信号（RSRP/SINR/RSRQ/频段/PCI/EARFCN）
     * Spreadtrum: C5GREG → NR 注册状态 + CESQ → 信号质量（3GPP 换算）
     *
     * CESQ 3GPP 换算：
     *   LTE: RSRP(dBm)=rsrp-140, RSRQ(dB)=rsrq/2-19.5, SINR(dB)=sinr/5-20
     *   NR:  RSRP(dBm)=rsrp-156, RSRQ(dB)=rsrq/2-43,  SINR(dB)=sinr/2-23
     */
    private fun parseAtResponses(copsRaw: String, qengRaw: String, cesqRaw: String = "",
                                 cgsnRaw: String = "", cgeqosRaw: String = "",
                                 c5gregRaw: String = "", platform: String = "",
                                 cgmmRaw: String = "", cgmrRaw: String = "",
                                 cregRaw: String = "", cgcontrdpRaw: String = "",
                                 cpinRaw: String = "", cfunRaw: String = "",
                                 cpasRaw: String = "", cgattRaw: String = ""): AtSignalInfo? {
        // ── 解析 COPS ──
        var operator = ""
        var networkType = ""
        var actCode = -1
        val copsClean = copsRaw.replace("\r", "").replace("\nOK", "").replace("OK", "").trim()

        COPS_RE.find(copsClean)?.let { match ->
            operator = match.groupValues.getOrElse(1) { "" }
            actCode = match.groupValues.getOrElse(2) { "-1" }.toIntOrNull() ?: -1
        }
        // 也匹配无引号的数字运营商
        if (operator.isEmpty()) {
            COPS_NUM_RE.find(copsClean)?.let { match ->
                actCode = match.groupValues.getOrElse(2) { "-1" }.toIntOrNull() ?: actCode
            }
        }

        networkType = when (actCode) {
            0, 1 -> "GSM"; 2 -> "3G"; 4 -> "3G"
            6 -> "3G"; 7 -> "4G"; 13 -> "5G"
            else -> ""
        }

        // ── PLMN → 真实运营商名称映射 ──
        val carrier = mapPlmnToCarrier(operator)

        // ── 解析 QENG（优先，原生 dBm/dB 值无需换算）──
        var rsrp = Int.MIN_VALUE
        var sinr = Int.MIN_VALUE
        var rsrq = Int.MIN_VALUE
        var band = ""
        var pci = -1
        var earfcn = -1
        var tac = ""
        var cellId = ""
        var dataSource = ""  // "QENG" | "CESQ" | ""
        val qengClean = qengRaw.replace("\r", "").replace("\nOK", "").replace("OK", "").trim()

        if (qengClean.isNotEmpty() && qengClean.contains("+QENG:")) {
            // 提取 "+QENG: ..." 行并按逗号分割
            val qengLine = qengClean.substringAfter("+QENG:").trim()
            val parts = qengLine.split(",").map { it.trim().removeSurrounding("\"") }

            // 查找频段字段（B 开头 = LTE，n 开头 = NR）
            val bandIdx = parts.indexOfFirst { it.matches(BAND_RE) }

            if (bandIdx >= 0) {
                band = parts[bandIdx]
                val isNr = band.startsWith("n", ignoreCase = true)

                if (isNr && parts.size > bandIdx + 6) {
                    // NR 5G: band → arfcn → freq → bw → pci → rsrp → sinr → rsrq
                    earfcn = parts.getOrElse(bandIdx + 1) { "" }.toIntOrNull() ?: -1
                    pci    = parts.getOrElse(bandIdx + 4) { "" }.toIntOrNull() ?: -1
                    rsrp   = parts.getOrElse(bandIdx + 5) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    sinr   = parts.getOrElse(bandIdx + 6) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    rsrq   = parts.getOrElse(bandIdx + 7) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    
                    // NR SA 额外尝试解析 TAC/CI (如果有的话，通常在 mcc/mnc 后面)
                    if (parts.size >= 7) {
                        cellId = parts[5]
                        tac = parts.getOrElse(bandIdx - 1) { "" } // 简易推测
                    }
                } else if (parts.size > bandIdx + 5) {
                    // LTE 4G: "servingcell",<state>,"LTE",<is_tdd>,<mcc>,<mnc>,<cellid>,<pcid>,<earfcn>,<band>,...
                    // 需要向前查找 earfcn（band 前 2-4 个字段）
                    for (j in maxOf(0, bandIdx - 4) until bandIdx) {
                        val v = parts[j].toIntOrNull()
                        if (v != null && v in 1..300000) { earfcn = v; break }
                    }
                    
                    // 标准 Quectel LTE 偏移
                    if (parts.size >= 13) {
                        cellId = parts[6]
                        pci = parts[7].toIntOrNull() ?: pci
                        tac = parts[12]
                    }
                    
                    rsrp = parts.getOrElse(bandIdx + 1) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    rsrq = parts.getOrElse(bandIdx + 2) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                    sinr = parts.getOrElse(bandIdx + 4) { "" }.toIntOrNull() ?: Int.MIN_VALUE
                }
            } else if (parts.size >= 13) {
                // 无频段字段，用已知位置解析 LTE（适配 EC20/EC25/EG915 等不同模块格式）
                // LTE 标准位: [3]earfcn [4]band_num [6]tac [8]pci [9]rsrp [10]rsrq [12]sinr
                earfcn = parts[3].toIntOrNull() ?: -1
                tac    = parts[6]
                cellId = parts[7]
                pci    = parts[8].toIntOrNull() ?: -1
                rsrp   = parts[9].toIntOrNull() ?: Int.MIN_VALUE
                rsrq   = parts[10].toIntOrNull() ?: Int.MIN_VALUE
                sinr   = parts[12].toIntOrNull() ?: Int.MIN_VALUE
            }

            if (rsrp != Int.MIN_VALUE || sinr != Int.MIN_VALUE || rsrq != Int.MIN_VALUE) {
                dataSource = "QENG"
            }
        }

        // ── 解析 C5GREG（展讯平台 5G NR 注册状态）──
        // 格式: +C5GREG: <stat>,<tac>,<ci>,<act>[,<mode>,...]
        // 例: +C5GREG: 2,1,"835500","8350C6180",11,9
        // stat=1=已注册归属网络, act=11~13=5G
        var c5gRegistered = false
        if (c5gregRaw.isNotEmpty()) {
            val c5gClean = c5gregRaw.replace("\r", "").replace("\nOK", "").replace("OK", "").trim()
            // 匹配格式: +C5GREG: <n>,<stat>,"<tac>","<ci>",<act>,<mode>
            C5GREG_RE.find(c5gClean)?.let { m ->
                val c5gStat = m.groupValues[2].toIntOrNull() ?: -1
                tac = m.groupValues[3]
                cellId = m.groupValues[4]
                val c5gAct = m.groupValues[5].toIntOrNull() ?: -1
                val c5gMode = m.groupValues.getOrNull(6)?.toIntOrNull() ?: -1
                c5gRegistered = c5gStat == 1 || c5gStat == 5

                // C5GREG ACT 优先于 COPS 判断 5G 模式
                if (c5gAct in 11..13) {
                    if (networkType.isEmpty() || networkType == "4G") {
                        networkType = when (c5gMode) {
                            9 -> "5G NSA"
                            1 -> "5G SA"
                            else -> "5G"
                        }
                    }
                }
                Log.d(TAG, "C5GREG: stat=$c5gStat act=$c5gAct mode=$c5gMode → networkType=$networkType")
            }
        }

        // ── 解析 CESQ（Quectel fallback / 展讯主信号源，3GPP 编码值需换算）──
        if (dataSource.isEmpty()) {
            // 尝试从 qengRaw 或 cesqRaw 中提取 +CESQ / ++CESQ 行
            val searchIn = listOf(qengRaw, cesqRaw)
            var cesqClean = ""
            for (raw in searchIn) {
                if (raw.isEmpty()) continue
                // 彻底清理：去掉 \r、独立的 "OK"（单词级别）、多余空白
                // 注意：不能盲目 replace "OK"，因为 RSRQ 值中不含 "OK"
                val c = raw
                    .replace("\r\n", "\n").replace("\r", "\n")
                    .replace("\nOK", "").replace("OK\n", "")  // 行首/行尾 OK
                    .replace(" OK", " ").replace("OK ", " ")  // 单词 OK
                    .replace("\n", " ").replace("  ", " ")    // 多空白压成单空格
                    .trim()
                if (c.contains("+CESQ:", ignoreCase = true)) {
                    cesqClean = c
                    break
                }
            }

            if (cesqClean.isNotEmpty()) {
                // 提取 CESQ 数据行（处理 ++CESQ: 或 +CESQ: 前缀及可能的 AT+CESQ 命令回声）
                // 策略：找到最后一次出现的 "+CESQ:" 后的内容
                val lastCesqIdx = cesqClean.lastIndexOf("+CESQ:", ignoreCase = true)
                val afterCesq = if (lastCesqIdx >= 0) {
                    cesqClean.substring(lastCesqIdx).substringAfter("+CESQ:").trim()
                } else {
                    cesqClean.substringAfter("+CESQ:").trim()
                }
                // 分离数据（逗号前）和可能的尾缀（如 "OK" 残余）
                val dataOnly = afterCesq.split(WHITESPACE_RE).firstOrNull { it.contains(",") } ?: afterCesq
                val parts = dataOnly.split(",").map { s -> s.trim().filter { it.isDigit() || it == '-' } }

                if (parts.size >= 6) {
                    val rsrqRaw = parts[4].toIntOrNull() ?: 255
                    val rsrpRaw = parts[5].toIntOrNull() ?: 255
                    var sinrRaw = 255
                    var avgRsrpRaw = 255
                    var avgRsrqRaw = 255

                    // 扩展字段（部分模块在标准 6 字段后附加 sinr/avg_rsrp/avg_rsrq）
                    if (parts.size >= 9) {
                        sinrRaw = parts[6].toIntOrNull() ?: 255
                        avgRsrpRaw = parts[7].toIntOrNull() ?: 255
                        avgRsrqRaw = parts[8].toIntOrNull() ?: 255
                    } else if (parts.size >= 7) {
                        sinrRaw = parts[6].toIntOrNull() ?: 255
                    }

                    // 从 COPS ACT / C5GREG 注册状态 已知 RAT，或从 CESQ 取值范围推断
                    var resolvedNr = actCode == 13 || c5gRegistered
                        || networkType.contains("5G", ignoreCase = true)

                    // 取值策略：标准字段优先，若为 255/99 则 fallback 到扩展字段
                    // 先用宽松范围（同时囊括 LTE 和 NR 范围）提取原始值
                    val rawRsrp = if (rsrpRaw in 0..127) rsrpRaw else if (avgRsrpRaw in 0..127) avgRsrpRaw else -1
                    val rawRsrq = if (rsrqRaw in 0..127) rsrqRaw else if (avgRsrqRaw in 0..127) avgRsrqRaw else -1
                    val rawSinr = if (sinrRaw in 0..250) sinrRaw else -1

                    // 智能 RAT 检测：如果 COPS 没明确 ACT，则根据取值范围推断
                    if (!resolvedNr && (rawRsrp in 98..127 || rawRsrq in 35..127)) {
                        // RSRP > 97 或 RSRQ > 34 仅 NR 才有，断定设备在 NR 模式
                        resolvedNr = true
                        if (networkType.isEmpty()) networkType = "5G"
                        Log.d(TAG, "CESQ RAT auto-detected as NR (rawRsrp=$rawRsrp rawRsrq=$rawRsrq)")
                    }

                    if (rawRsrp >= 0 || rawSinr >= 0 || rawRsrq >= 0) {
                        // 根据最终确定的 RAT 执行 3GPP 换算
                        if (resolvedNr) {
                            // NR: RSRP 0-127 → -156 to -31 dBm
                            if (rawRsrp in 0..127) rsrp = rawRsrp - 156
                            // NR: SINR 0-127 → -23 to +40.5 dB
                            if (rawSinr in 0..127) sinr = Math.round((rawSinr * 0.5 - 23).toFloat())
                            // NR: RSRQ 0-127 → -43 to +20 dB
                            if (rawRsrq in 0..127) rsrq = Math.round((rawRsrq * 0.5f - 43).toFloat())
                        } else {
                            // LTE: RSRP 0-97 → -140 to -43 dBm
                            if (rawRsrp in 0..97) rsrp = rawRsrp - 140
                            // LTE: SINR 0-250 → -20 to +30 dB
                            if (rawSinr in 0..250) sinr = Math.round((rawSinr / 5.0f - 20).toFloat())
                            // LTE: RSRQ 0-34 → -19.5 to -3 dB
                            if (rawRsrq in 0..34) rsrq = Math.round((rawRsrq * 0.5f - 19.5).toFloat())
                            else if (rawRsrq in 35..127) {
                                // 保守场景：RSRQ 在 NR 范围内（可能 NR 误判为 LTE）
                                rsrq = Math.round((rawRsrq * 0.5f - 43).toFloat())
                            }
                        }
                        dataSource = "CESQ"
                        Log.d(TAG, "CESQ parsed: raw(rsrp=$rawRsrp rsrq=$rawRsrq sinr=$rawSinr) → " +
                            "converted rsrp=$rsrp sinr=$sinr rsrq=$rsrq (RAT=${if (resolvedNr) "NR" else "LTE"})")
                    }
                }
            }
        }

        // 如果 QENG 没解析到网络类型但 COPS 也没识别，从原始数据推断
        if (networkType.isEmpty()) {
            if (qengClean.contains("NR", ignoreCase = true)) {
                networkType = "5G"
            } else if (cesqRaw.contains("NR", ignoreCase = true)) {
                networkType = "5G"
            }
        }

        // ── 解析 AT+CGSN（IMEI）──
        var imei = ""
        if (cgsnRaw.isNotEmpty()) {
            val cgsnClean = cgsnRaw
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("OK", "").trim()
            // IMEI 通常是 15 位纯数字，提取第一行中的数字序列
            IMEI_RE.find(cgsnClean)?.let { match ->
                imei = match.groupValues[1]
            }
        }

        // ── 解析 AT+CGEQOSRDP=1（签约速率）──
        // 返回格式: +CGEQOSRDP: <cid>,<QCI>,<0>,<0>,<0>,<0>,<DL_kbps>,<UL_kbps>
        var subscriptionRate = ""
        if (cgeqosRaw.isNotEmpty()) {
            val cgeqosClean = cgeqosRaw
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("OK", "").trim()
            Log.d(TAG, "CGEQOSRDP raw: $cgeqosClean")

            // 提取逗号分隔字段
            val afterColon = cgeqosClean.substringAfter("+CGEQOSRDP:").trim()
            val fields = afterColon.split(",").map { it.trim().toLongOrNull() ?: 0L }

            if (fields.size >= 8) {
                val qci = fields[1]
                val dlKbps = fields[6]
                val ulKbps = fields[7]
                val dlPretty = if (dlKbps >= 1000) String.format("%.1f Mbps", dlKbps / 1000.0) else "${dlKbps} kbps"
                val ulPretty = if (ulKbps >= 1000) String.format("%.1f Mbps", ulKbps / 1000.0) else "${ulKbps} kbps"
                subscriptionRate = "QCI $qci: ↓ $dlPretty  ↑ $ulPretty"
            } else {
                subscriptionRate = cgeqosClean.take(200) // fallback
            }
        }

        // ── 解析 AT+CGMM（模块型号）──
        var moduleModel = ""
        if (cgmmRaw.isNotEmpty()) {
            moduleModel = atClean(cgmmRaw).trim()
            Log.d(TAG, "CGMM: $moduleModel")
        }

        // ── 解析 AT+CGMR（固件详细版本）──
        var firmwareDetail = ""
        if (cgmrRaw.isNotEmpty()) {
            firmwareDetail = atClean(cgmrRaw).lines()
                .filter { it.isNotBlank() }.joinToString(" | ").trim()
            Log.d(TAG, "CGMR: $firmwareDetail")
        }

        // ── 解析 AT+CREG?（网络注册状态）──
        var cregStat = -1
        var lteRegistration = ""
        if (cregRaw.isNotEmpty()) {
            val clean = atClean(cregRaw)
            CREG_RE.find(clean)?.let { m ->
                cregStat = m.groupValues[2].toIntOrNull() ?: -1
                lteRegistration = when (cregStat) {
                    0 -> "未注册/搜索中"; 1 -> "已注册(归属)"; 2 -> "搜索中"
                    3 -> "注册被拒"; 4 -> "未知"; 5 -> "已注册(漫游)"; 8 -> "已注册(归属PS)"
                    else -> "状态$cregStat"
                }
            }
        }

        // ── 解析 AT+CGCONTRDP=1（WAN IP + DNS）──
        var wanIpAt = ""
        var dnsServers = ""
        if (cgcontrdpRaw.isNotEmpty()) {
            val clean = atClean(cgcontrdpRaw)
            // +CGCONTRDP: <cid>,<bearer>,<apn>,"<ip>",null,null,null,<dns1>,<dns2>,...
            CGCONTRDP_RE.find(clean)?.let { m ->
                wanIpAt = m.groupValues[1]
                val d1 = m.groupValues[2].filter { it != '"' }
                val d2 = m.groupValues[3].filter { it != '"' }
                dnsServers = listOf(d1, d2).filter { it.isNotEmpty() }.joinToString(", ")
            }
            Log.d(TAG, "CGCONTRDP: IP=$wanIpAt DNS=$dnsServers")
        }

        // ── 解析 AT+CPIN?（PIN 状态）──
        var pinStatusAt = ""
        if (cpinRaw.isNotEmpty()) {
            val clean = atClean(cpinRaw)
            CPIN_RE.find(clean)?.let { m ->
                pinStatusAt = m.groupValues[1].trim()
            }
        }

        // ── 解析 AT+CFUN?（射频功能）──
        var rfFunc = ""
        if (cfunRaw.isNotEmpty()) {
            val clean = atClean(cfunRaw)
            CFUN_RE.find(clean)?.let { m ->
                rfFunc = when (m.groupValues[1]) {
                    "1" -> "全功能(射频开启)"
                    "0" -> "最小功能"
                    "4" -> "飞行模式(射频关闭)"
                    else -> m.groupValues[1]
                }
            }
        }

        // ── 解析 AT+CPAS（模块状态）──
        var moduleState = ""
        if (cpasRaw.isNotEmpty()) {
            val clean = atClean(cpasRaw)
            CPAS_RE.find(clean)?.let { m ->
                moduleState = when (m.groupValues[1]) {
                    "0" -> "就绪(可接受AT)"
                    "1" -> "不可用"
                    "2" -> "未知"
                    "3" -> "振铃中"
                    "4" -> "通话中"
                    "5" -> "睡眠中"
                    else -> m.groupValues[1]
                }
            }
        }

        // ── 解析 AT+CGATT?（PS 域附着）──
        var psAttached = ""
        if (cgattRaw.isNotEmpty()) {
            val clean = atClean(cgattRaw)
            CGATT_RE.find(clean)?.let { m ->
                psAttached = if (m.groupValues[1] == "1") "已附着(数据可用)" else "未附着"
            }
        }

        // 没有任何有效数据 → null
        val hasOldData = rsrp != Int.MIN_VALUE || sinr != Int.MIN_VALUE || rsrq != Int.MIN_VALUE
            || networkType.isNotEmpty() || operator.isNotEmpty() || carrier.isNotEmpty()
            || imei.isNotEmpty() || subscriptionRate.isNotEmpty()
        val hasNewData = moduleModel.isNotEmpty() || firmwareDetail.isNotEmpty()
            || cregStat >= 0 || wanIpAt.isNotEmpty() || dnsServers.isNotEmpty()
            || pinStatusAt.isNotEmpty() || rfFunc.isNotEmpty() || moduleState.isNotEmpty() || psAttached.isNotEmpty()
        if (!hasOldData && !hasNewData) {
            return null
        }

        // 防御性校验：RSRP 物理上必须为负 dBm 值
        // QENG 解析器可能在字段偏移异常时取到正值，此处统一修正
        if (rsrp > 0 && rsrp != Int.MIN_VALUE) {
            Log.w(TAG, "RSRP normalization: $rsrp → ${-rsrp} (QENG field offset suspected)")
            rsrp = -rsrp
        }

        return AtSignalInfo(
            networkType = networkType,
            operator = operator,
            carrier = carrier,
            rsrp = rsrp,
            sinr = sinr,
            rsrq = rsrq,
            band = band,
            pci = pci,
            earfcn = earfcn,
            rawQeng = qengRaw.ifEmpty { c5gregRaw.ifEmpty { cesqRaw } },
            rawCops = copsRaw,
            imei = imei,
            subscriptionRate = subscriptionRate,
            tac = tac,
            cellId = cellId,
            // 新增字段
            moduleModel = moduleModel,
            firmwareDetail = firmwareDetail,
            cregStat = cregStat,
            lteRegistration = lteRegistration,
            wanIpAt = wanIpAt,
            dnsServers = dnsServers,
            pinStatusAt = pinStatusAt,
            rfFunc = rfFunc,
            moduleState = moduleState,
            psAttached = psAttached
        )
    }

    /** AT 响应清理：去掉 \r、独立成行的 "OK" 终止符（仅行级，不触碰数据内的 "OK" 子串）。 */
    private fun atClean(raw: String): String = raw
        .replace("\r", "")
        .replace("\nOK\n", "\n")
        .replace(AT_OK_RE, "")
        .trim()

    /** 运营商识别 (MCC 460)：基于 PLMN 或 IMSI 前缀 */
    private fun mapPlmnToCarrier(input: String): String {
        if (input.length < 5) return ""
        
        // 统一取前 5 位 (MCC 3 + MNC 2) 处理中国主流运营商
        // 460 00/02/07/08 -> 移动
        // 460 01/06/09    -> 联通
        // 460 03/05/11    -> 电信
        // 460 15          -> 广电
        
        if (input.startsWith("460")) {
            return when (input.substring(3, 5)) {
                "00", "02", "07", "08" -> "中国移动"
                "01", "06", "09" -> "中国联通"
                "03", "05", "11" -> "中国电信"
                "15" -> "中国广电"
                else -> ""
            }
        }
        return ""
    }
}

data class WifiEntity(
    val model: String,
    val flow: String,
    val dailyFlow: String,
    val signal: String,
    val temp: String,
    val battery: String,
    val batteryPercent: Int,        // 原始电池百分比（-1 为无数据）
    val cpu: String,
    val mem: String,
    val netType: String,
    // === /api/baseDeviceInfo 新增字段 ===
    val appVer: String,           // UFI-TOOLS 版本号
    val appVerCode: String,       // UFI-TOOLS 版本代码 (如 20260601)
    val batteryCurrent: String,   // 电池电流 (mA)
    val batteryVoltage: String,   // 电池电压 (V)
    val internalStorage: String,  // 内部存储 已用/总容量 (格式化)
    val internalAvailableStorage: Long,  // 内部存储可用 (Bytes)
    val internalTotalStorage: Long,      // 内部存储总量 (Bytes)
    val internalUsedStorage: Long,       // 内部存储已用 (Bytes)
    val externalTotalStorage: Long,      // 外部存储总量 (Bytes)，0 表示无外部存储
    val externalUsedStorage: Long,       // 外部存储已用 (Bytes)
    val externalAvailableStorage: Long,  // 外部存储可用 (Bytes)
    val clientIp: String,         // 设备 IP 地址
    // === /api/version_info 字段 ===
    val deviceModel: String,      // 设备硬件型号 (如 U30 Air)
    val firmwareVer: String,      // 固件版本
    // === /api/need_token 字段 ===
    val needToken: Boolean,       // 是否需要登录验证
    // === AT 指令获取的网络详情（/api/AT）===
    val atNetworkInfo: AtSignalInfo?,  // AT+QENG / AT+COPS 解析结果
    // === 详细硬件信息（用于主界面弹窗详情）===
    val cpuTempList: List<CpuTempItem>,     // cpu_temp_list 各模块温度
    val cpuFreqInfo: Map<String, CpuFreqItem>, // cpuFreqInfo 各核心频率
    val cpuUsageInfo: Map<String, String>,  // cpuUsageInfo 各核心使用率
    val memTotalKb: Long,         // 内存总量 KB
    val memAvailableKb: Long,     // 内存可用 KB
    val memUsedKb: Long,          // 内存已用 KB
    val swapTotalKb: Long,        // Swap 总量 KB
    val swapUsedKb: Long,         // Swap 已用 KB
    val swapFreeKb: Long,         // Swap 空闲 KB
    // === Goform 设备身份+网络（新增）===
    val wanIp: String,            // WAN IPv4 地址
    val wanIpv6: String,          // WAN IPv6 地址
    val pdpTypeGoform: String,    // PDP 承载类型（IPv4/IPv6/IPv4v6）
    val goformImei: String,       // IMEI（Goform 来源）
    val goformImsi: String,       // IMSI（Goform 来源）
    val goformIccid: String,      // ICCID（Goform 来源）
    val hardwareVersion: String,  // 硬件版本号（Goform 来源）
    val webVersion: String,       // Web/固件版本号（Goform 来源）
    val macAddress: String,       // 设备 MAC 地址
    val pinStatusCode: Int,       // SIM PIN 状态：0=已解锁，1=需PIN，2=PUK锁定，-1=无数据
    val monthlyUploadBytes: Long, // 当月上行流量 (Bytes)
    val monthlyDownloadBytes: Long, // 当月下行流量 (Bytes)
    val dailyRawBytes: Long,       // 日流量原始字节数
    val monthlyRawBytes: Long,     // 月流量原始字节数
)

data class AtSignalInfo(
    val networkType: String,   // "5G SA" / "4G LTE" / "NR" / "LTE"
    val operator: String,      // 运营商原始值（如 "46001" / "CHN-UNICOM"）
    val carrier: String,       // PLMN 映射的真实运营商（如 "中国联通 CUCC"）
    val rsrp: Int,             // RSRP (dBm)，负值，如 -95
    val sinr: Int,             // SINR (dB)，如 15
    val rsrq: Int,             // RSRQ (dB)，如 -10
    val band: String,          // 频段，如 "n78" / "B3"
    val pci: Int,              // Physical Cell ID
    val earfcn: Int,           // EARFCN / NR-ARFCN
    val rawQeng: String,       // 信号原始响应（调试用）
    val rawCops: String,       // AT+COPS 原始响应（调试用）
    val imei: String,          // AT+CGSN 获取的 IMEI
    val subscriptionRate: String, // AT+CGEQOSRDP=1 获取的签约速率
    val tac: String,           // Tracking Area Code (LTE) / NR TAC
    val cellId: String,        // Cell ID (LTE) / NR CI
    // === AT 新增字段 ===
    val moduleModel: String,       // AT+CGMM 模块型号
    val firmwareDetail: String,    // AT+CGMR 固件详细版本
    val cregStat: Int,             // AT+CREG? 网络注册状态码
    val lteRegistration: String,   // AT+CREG? 注册状态文本
    val wanIpAt: String,           // AT+CGCONTRDP=1 获取的 WAN IP
    val dnsServers: String,        // AT+CGCONTRDP=1 获取的 DNS 服务器
    val pinStatusAt: String,        // AT+CPIN? PIN 状态 (READY/SIM PIN/SIM PUK)
    val rfFunc: String,            // AT+CFUN? 射频功能状态
    val moduleState: String,       // AT+CPAS 模块活动状态
    val psAttached: String,        // AT+CGATT? PS 域附着
)

data class CpuTempItem(
    val type: String,   // 模块名称 (如 pa-thmzone, gpu-thmzone)
    val temp: Double     // 原始温度值 (>1000 时需 /1000)
)

data class CpuFreqItem(
    val cur: Int,   // 当前频率 MHz
    val max: Int    // 最大频率 MHz
)
