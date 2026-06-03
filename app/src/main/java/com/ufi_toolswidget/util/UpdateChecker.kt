package com.ufi_toolswidget.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 通过静态 version.json 文件检查应用更新
 *
 * 优势：无请求频率限制，响应快，可控性强
 */
object UpdateChecker {

    // ====== version.json 地址 ======
    // 方式 1：raw.githubusercontent.com（推荐）
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/Asunano/UFITOOLS-Widget/main/version.json"
    // 方式 2：GitHub Pages（需先开启）
    // private const val VERSION_URL =
    //     "https://asunano.github.io/UFITOOLS-Widget/version.json"

    /**
     * 更新信息
     */
    data class UpdateInfo(
        val versionName: String,   // "1.2.0"
        val versionCode: Int,      // 2
        val tagName: String,       // "v1.2.0"
        val apkUrl: String,        // 下载链接
        val apkSize: Long,         // 文件大小（字节）
        val publishedAt: String,   // 发布时间
        val changelog: String      // 更新日志（用 | 分隔多条）
    )

    /**
     * 获取本地 versionCode
     */
    fun getLocalVersionCode(context: Context): Int {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode.toInt()
        } catch (_: PackageManager.NameNotFoundException) { 0 }
    }

    /**
     * 获取本地 versionName
     */
    fun getLocalVersionName(context: Context): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0"
        } catch (_: PackageManager.NameNotFoundException) { "0" }
    }

    /**
     * 解析 version.json
     *
     * JSON 结构示例：
     * {
     *   "versionName": "1.2.0",
     *   "versionCode": 2,
     *   "tagName": "v1.2.0",
     *   "apkUrl": "https://...",
     *   "apkSize": 5242880,
     *   "publishedAt": "2024-01-15T08:00:00Z",
     *   "changelog": "- 修复xx|新增xx|- 优化xx"
     * }
     */
    fun parseVersionJson(json: String): UpdateInfo? {
        return try {
            val obj = JSONObject(json)
            UpdateInfo(
                versionName = obj.getString("versionName"),
                versionCode = obj.optInt("versionCode", 0),
                tagName = obj.optString("tagName", ""),
                apkUrl = obj.optString("apkUrl", ""),
                apkSize = obj.optLong("apkSize", 0),
                publishedAt = obj.optString("publishedAt", ""),
                changelog = obj.optString("changelog", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 语义版本号比较
     *
     * 逐段比较数字：1.2.0 vs 1.1.5
     *   第1段 1 == 1 → 继续
     *   第2段 2 > 1  → 返回正数（remote 更新）
     *
     * @return > 0 表示本地更新, 0 相同, < 0 表示远程有新版本
     */
    fun compareVersions(local: String, remote: String): Int {
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(l.size, r.size)
        for (i in 0 until maxLen) {
            val lv = l.getOrElse(i) { 0 }
            val rv = r.getOrElse(i) { 0 }
            if (lv != rv) return lv - rv
        }
        return 0
    }

    /**
     * 异步检查更新，回调在主线程
     *
     * @param onResult 回调：
     *   - (UpdateInfo, null) → 有新版本可用
     *   - (null, null)       → 已是最新版本
     *   - (null, errorMsg)   → 网络错误或解析失败
     */
    fun checkUpdate(context: Context, onResult: (UpdateInfo?, String?) -> Unit) {
        val request = Request.Builder()
            .url(VERSION_URL)
            .cacheControl(CacheControl.FORCE_NETWORK) // 不走缓存，拿最新的
            .build()

        NetUtil.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post {
                    onResult(null, "网络请求失败: ${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Handler(Looper.getMainLooper()).post {
                    if (!response.isSuccessful || body == null) {
                        onResult(null, "服务器返回错误: ${response.code}")
                        return@post
                    }

                    val info = parseVersionJson(body)
                    if (info == null) {
                        onResult(null, "解析版本信息失败")
                        return@post
                    }

                    val localVersion = getLocalVersionName(context)
                    if (compareVersions(localVersion, info.versionName) < 0) {
                        onResult(info, null)   // 有新版本
                    } else {
                        onResult(null, null)   // 已是最新
                    }
                }
            }
        })
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * 格式化更新日志
     * 将 "|" 分隔的字符串转为带换行的文本
     */
    fun formatChangelog(changelog: String): String {
        return changelog.split("|").joinToString("\n") { it.trim() }
    }

    /**
     * 格式化 ISO 日期为易读格式
     * "2026-06-03T12:00:00Z" → "2026-06-03"
     */
    fun formatDate(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return try {
            val t = isoDate.indexOf('T')
            if (t > 0) isoDate.substring(0, t) else isoDate
        } catch (_: Exception) { isoDate }
    }
}
