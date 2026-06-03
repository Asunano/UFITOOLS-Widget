package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WifiCrawl {
    
    private const val TAG = "WifiCrawl"
    
    var lastRawResponse: String = ""
    var lastError: String = ""

    suspend fun getWifiData(context: Context): WifiEntity? = withContext(Dispatchers.IO) {
        try {
            lastError = ""
            val t = System.currentTimeMillis()
            val auth = SPUtil.getAuthToken(context)

            // 1. 获取系统全量信息 (需认证) - 它是后续解析的基础，通常先获取
            val baseInfo = fetchApi("/api/baseDeviceInfo", t, auth, context) ?: return@withContext null
            
            // 2, 3, 4 并行获取以提高速度
            val signalDeferred = async { 
                val signalPath = "/api/goform/goform_get_cmd_process?is_all=true&cmd=lte_rsrp,Z5g_rsrp,network_type,rssi"
                fetchApi(signalPath, t, auth, context)
            }
            val versionDeferred = async { fetchApiNoAuth("/api/version_info", t, context) }
            val tokenDeferred = async { fetchApiNoAuth("/api/need_token", t, context) }

            val signalInfo = signalDeferred.await()
            val versionInfo = versionDeferred.await()
            val tokenInfo = tokenDeferred.await()

            // --- 精准解析 ---
            val model = baseInfo.optString("model", "F50")
            val batteryPercent = baseInfo.optInt("battery", -1)
            val cpuUsage = baseInfo.optDouble("cpu_usage", 0.0)
            val memUsage = baseInfo.optDouble("mem_usage", 0.0)
            
            // 日流量 (daily_data 来自 baseDeviceInfo，可能是多种字段名)
            val dailyRaw = extractTrafficBytes(baseInfo, "daily_data", "daily_tx_bytes", "daily_rx_bytes", "day_data")

            // 月流量：从 baseDeviceInfo 中尝试提取（优先级最高）
            var monthlyRaw = extractTrafficBytes(baseInfo, "monthly_data", "monthly_tx_bytes", "monthly_rx_bytes", "month_data", "total_data")
            
            // 如果 baseDeviceInfo 中没有月流量，尝试从 goform 代理获取
            if (monthlyRaw <= 0) {
                val monthlyInfo = fetchApi("/api/goform/goform_get_cmd_process?is_all=true&cmd=monthly_data", t, auth, context)
                val monthlyFromGoform = extractTrafficBytes(monthlyInfo, "monthly_data", "monthly_tx_bytes", "monthly_rx_bytes", "month_data")
                if (monthlyFromGoform > 0) {
                    monthlyRaw = monthlyFromGoform
                }
            }
            
            // 如果 API 都没返回月流量，用缓存
            if (monthlyRaw <= 0) {
                monthlyRaw = SPUtil.getCachedMonthlyData(context)
            } else {
                SPUtil.setCachedMonthlyData(context, monthlyRaw)
            }
            
            Log.d(TAG, "dailyRaw=$dailyRaw, monthlyRaw=$monthlyRaw")
            
            // 温度 (60620 -> 60.6)
            val tempRaw = baseInfo.optDouble("cpu_temp", 0.0)

            // === /api/baseDeviceInfo 新增字段 ===
            val appVer = baseInfo.optString("app_ver", "")
            val appVerCode = baseInfo.optString("app_ver_code", "")
            val currentNow = baseInfo.optInt("current_now", -1)        // 微安 µA
            val voltageNow = baseInfo.optInt("voltage_now", -1)        // 微伏 µV
            val internalTotal = baseInfo.optLong("internal_total_storage", -1L)
            val internalUsed = baseInfo.optLong("internal_used_storage", -1L)
            val clientIp = baseInfo.optString("client_ip", "")

            // === /api/version_info 字段 ===
            val deviceModel = versionInfo?.optString("model", "") ?: ""
            val firmwareVer = versionInfo?.optString("app_ver", "") 
                ?: versionInfo?.optString("version", "") ?: ""

            // === /api/need_token 字段 ===
            val needToken = tokenInfo?.optBoolean("need_token", false) ?: false

            // 信号解析逻辑
            val netType = signalInfo?.optString("network_type") ?: ""
            val signalVal = when {
                netType.contains("5G") -> signalInfo?.optString("Z5g_rsrp")
                else -> signalInfo?.optString("lte_rsrp")
            } ?: signalInfo?.optString("rssi") ?: "--"

            WifiEntity(
                model = model,
                flow = formatFlow(monthlyRaw),
                dailyFlow = formatFlow(dailyRaw),
                signal = if (signalVal == "" || signalVal == "null") "--" else "${signalVal}dBm",
                temp = formatTemp(tempRaw),
                battery = if (batteryPercent >= 0) "${batteryPercent}%" else "--",
                cpu = String.format(Locale.getDefault(), "%.1f%%", cpuUsage),
                mem = String.format(Locale.getDefault(), "%.1f%%", memUsage),
                netType = netType,
                appVer = appVer,
                appVerCode = appVerCode,
                batteryCurrent = formatCurrent(currentNow),
                batteryVoltage = formatVoltage(voltageNow),
                internalStorage = formatStorage(internalTotal, internalUsed),
                clientIp = clientIp,
                deviceModel = deviceModel.ifEmpty { model },
                firmwareVer = firmwareVer,
                needToken = needToken
            )
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "Parse Error: ${e.message}"
            null
        }
    }

    /**
     * 非阻塞网络请求：使用 OkHttp 异步 enqueue + suspendCancellableCoroutine，
     * 不阻塞 IO 线程，且支持协程取消。
     */
    private suspend fun executeRequest(req: Request): Response? =
        suspendCancellableCoroutine { continuation ->
            val call = NetUtil.client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }
            })
        }

    private suspend fun fetchApi(path: String, t: Long, auth: String, context: Context): JSONObject? {
        val purePath = if (path.contains("?")) path.substringBefore("?") else path
        val sign = NetUtil.generateKanoSign("GET", purePath, t)
        
        val baseUrl = SPUtil.getBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .addHeader("Authorization", auth)
            .build()
            
        val resp = executeRequest(req) ?: run {
            lastError = "Network error"
            return null
        }
        return resp.use { response ->
            val content = response.body?.string() ?: ""
            lastRawResponse = content
            if (response.isSuccessful && content.isNotEmpty()) {
                JSONObject(content)
            } else {
                lastError = "HTTP ${response.code} on $purePath"
                null
            }
        }
    }

    /** 无需认证的 API 请求 (version_info / need_token) */
    private suspend fun fetchApiNoAuth(path: String, t: Long, context: Context): JSONObject? {
        val sign = NetUtil.generateKanoSign("GET", path, t)
        val baseUrl = SPUtil.getBaseUrl(context).trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("kano-t", t.toString())
            .addHeader("kano-sign", sign)
            .build()

        val resp = executeRequest(req) ?: return null
        return resp.use { response ->
            val content = response.body?.string() ?: ""
            if (response.isSuccessful && content.isNotEmpty()) {
                JSONObject(content)
            } else {
                null
            }
        }
    }

    /**
     * 从 JSON 中提取流量字节数，自动适配不同单位和字段名。
     * 
     * 字段名含 "bytes" 的（如 daily_tx_bytes）直接当作 Bytes，不做单位推断。
     * 其他字段通过数值大小推断单位：
     *   >= 100 MB 量级 → Bytes（最常见）
     *   >= 100 KB 量级 → KB
     *   >= 100 B  量级 → MB
     *         更小      → GB
     * 
     * 阈值采用 100 倍阶跃，避免字节值落入相邻区间导致误判。
     */
    private fun extractTrafficBytes(json: JSONObject?, vararg keys: String): Long {
        if (json == null) return 0L
        
        for (key in keys) {
            // 字段名含 "bytes" 或以下划线 "_data" 结尾（如 daily_data/monthly_data）
            // 均直接视作 Bytes 单位，不做数值量级推断
            val keyLower = key.lowercase()
            val forceBytes = keyLower.contains("bytes") || keyLower.endsWith("_data")
            
            // 尝试数字类型字段
            val longVal = json.optLong(key, -1L)
            if (longVal > 0) {
                if (forceBytes) {
                    Log.d(TAG, "  $key=$longVal -> 字段名含bytes，直接作为 Bytes")
                    return longVal
                }
                return when {
                    longVal >= 100_000_000L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 Bytes")
                        longVal
                    }
                    longVal >= 100_000L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 KB，转为 Bytes")
                        longVal * 1024L
                    }
                    longVal >= 100L -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 MB，转为 Bytes")
                        longVal * 1024L * 1024L
                    }
                    else -> {
                        Log.d(TAG, "  $key=$longVal -> 判断为 GB，转为 Bytes")
                        longVal * 1024L * 1024L * 1024L
                    }
                }
            }
            
            // 尝试字符串类型字段（有些固件返回字符串数字，如 "1.74"）
            val strVal = json.optString(key, "")
            if (strVal.isNotEmpty() && strVal != "null") {
                val parsed = strVal.toDoubleOrNull()
                if (parsed != null && parsed > 0) {
                    if (forceBytes) {
                        Log.d(TAG, "  $key=$strVal(String) -> 字段名含bytes，直接作为 Bytes")
                        return parsed.toLong()
                    }
                    return when {
                        parsed >= 100_000_000.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 Bytes")
                            parsed.toLong()
                        }
                        parsed >= 100_000.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 KB，转为 Bytes")
                            (parsed * 1024.0).toLong()
                        }
                        parsed >= 100.0 -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 MB，转为 Bytes")
                            (parsed * 1024.0 * 1024.0).toLong()
                        }
                        else -> {
                            Log.d(TAG, "  $key=$strVal(String) -> 判断为 GB，转为 Bytes")
                            (parsed * 1024.0 * 1024.0 * 1024.0).toLong()
                        }
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
}

data class WifiEntity(
    val model: String,
    val flow: String,
    val dailyFlow: String,
    val signal: String,
    val temp: String,
    val battery: String,
    val cpu: String,
    val mem: String,
    val netType: String,
    // === /api/baseDeviceInfo 新增字段 ===
    val appVer: String,           // UFI-TOOLS 版本号
    val appVerCode: String,       // UFI-TOOLS 版本代码 (如 20260601)
    val batteryCurrent: String,   // 电池电流 (mA)
    val batteryVoltage: String,   // 电池电压 (V)
    val internalStorage: String,  // 内部存储 已用/总容量
    val clientIp: String,         // 设备 IP 地址
    // === /api/version_info 字段 ===
    val deviceModel: String,      // 设备硬件型号 (如 U30 Air)
    val firmwareVer: String,      // 固件版本
    // === /api/need_token 字段 ===
    val needToken: Boolean        // 是否需要登录验证
)
