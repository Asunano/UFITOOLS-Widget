package com.ufi_toolswidget.util

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import com.ufi_toolswidget.util.ToastUtil

/**
 * 通过静态 version.json 文件检查应用更新
 *
 * 优势：无请求频率限制，响应快，可控性强
 */
object UpdateChecker {

    // ====== version.json 地址 ======
    private const val VERSION_PATH = "Asunano/UFITOOLS-Widget/main/version.json"
    const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/"
    const val MIRROR_PROXY_BASE = "https://v4.gh-proxy.org/https://raw.githubusercontent.com/"

    /** 根据镜像设置构建 version.json URL */
    fun getVersionUrl(context: Context): String {
        val mirror = SPUtil.getUpdateMirror(context)
        return if (mirror == 1) "$MIRROR_PROXY_BASE$VERSION_PATH"
        else "$GITHUB_RAW_BASE$VERSION_PATH"
    }

    /** APK 下载链接也走镜像 */
    fun applyMirrorToUrl(context: Context, url: String): String {
        if (url.isBlank()) return url
        val mirror = SPUtil.getUpdateMirror(context)
        if (mirror == 1 && url.startsWith(GITHUB_RAW_BASE)) {
            return MIRROR_PROXY_BASE + url.removePrefix(GITHUB_RAW_BASE)
        }
        // GitHub Releases 也走镜像
        if (mirror == 1 && url.startsWith("https://github.com/")) {
            return "https://v4.gh-proxy.org/$url"
        }
        return url
    }

    /**
     * 更新信息
     */
    data class UpdateInfo(
        val versionName: String,   // "1.2.0"
        val versionCode: Int,      // 2
        val tagName: String,       // "v1.2.0"
        val apkUrl: String,        // 下载链接
        val apkSize: Long,         // 文件大小（字节）
        val apkSha256: String,     // APK 文件 SHA256 校验值
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
     *   "apkSha256": "abc123...",
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
                apkSha256 = obj.optString("apkSha256", ""),
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
     * 支持 v-prefix（如 "v1.2.0"）和 pre-release 后缀（如 "1.2.0-beta"）：
     * - v-prefix 会被自动剥离
     * - pre-release 后缀在数字比较时被忽略（"1.2.0-beta" 等同于 "1.2.0"）
     *
     * @return > 0 表示本地更新, 0 相同, < 0 表示远程有新版本
     */
    fun compareVersions(local: String, remote: String): Int {
        fun normalize(v: String): String = v.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore("-")  // 剥离 pre-release 后缀
        val l = normalize(local).split(".").map { it.toIntOrNull() ?: 0 }
        val r = normalize(remote).split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(l.size, r.size)
        for (i in 0 until maxLen) {
            val lv = l.getOrElse(i) { 0 }
            val rv = r.getOrElse(i) { 0 }
            if (lv != rv) return lv - rv
        }
        return 0
    }

    // ==================== 检查结果类型 ====================

    /** 检查更新的结果，避免回调中未检查 Lifecycle 导致的泄漏 */
    sealed class UpdateResult {
        /** 有新版本可用 */
        data class NewVersion(val info: UpdateInfo) : UpdateResult()
        /** 当前已是最新版本 */
        object Latest : UpdateResult()
        /** 请求或解析失败 */
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * 挂起式检查更新 — 通过 [suspendCancellableCoroutine] 绑定协程生命周期。
     *
     * 调用方使用 [lifecycleScope.launch] 包裹即可在 Activity/Fragment 销毁时自动取消请求，
     * 无需手动检查 isFinishing / isDestroyed。
     *
     * @return [UpdateResult]：
     *   - [UpdateResult.NewVersion] → 有新版本
     *   - [UpdateResult.Latest]     → 已是最新
     *   - [UpdateResult.Error]      → 网络错误或解析失败
     */
    suspend fun checkUpdate(context: Context): UpdateResult = suspendCancellableCoroutine { continuation ->
        val url = getVersionUrl(context)
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val call = NetUtil.client.newCall(request)

        // 协程取消时自动取消 HTTP 请求
        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(
                        UpdateResult.Error("网络请求失败: ${e.localizedMessage}")
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    if (continuation.isActive) {
                        continuation.resume(
                            UpdateResult.Error("服务器返回错误: ${response.code}")
                        )
                    }
                    return
                }

                val info = parseVersionJson(body)
                if (info == null) {
                    if (continuation.isActive) {
                        continuation.resume(UpdateResult.Error("解析版本信息失败"))
                    }
                    return
                }

                val localVersion = getLocalVersionName(context)
                if (continuation.isActive) {
                    if (compareVersions(localVersion, info.versionName) < 0) {
                        continuation.resume(UpdateResult.NewVersion(info))
                    } else {
                        continuation.resume(UpdateResult.Latest)
                    }
                }
            }
        })
    }

    /** 判断更新失败是否属于网络连接类错误（可用于提示切换镜像源） */
    fun isNetworkError(error: String): Boolean {
        return error.contains("网络请求失败") || error.contains("Unable to resolve")
                || error.contains("timeout") || error.contains("connect")
                || error.contains("UnknownHost") || error.contains("403")
                || error.contains("404") || error.contains("500")
                || error.contains("502") || error.contains("503")
                || error.contains("504")
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

    /**
     * 校验下载文件的 SHA256
     *
     * @param file 下载的 APK 文件
     * @param expectedSha256 version.json 中记录的 SHA256（小写十六进制）
     * @return true 表示校验通过
     */
    fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true // 未提供校验值时跳过
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过 Content URI 校验 SHA256（适配 Android 10+ Scoped Storage）。
     *
     * @param context Context
     * @param uri 下载文件的 Content URI 或 File URI
     * @param expectedSha256 version.json 中记录的 SHA256（小写十六进制）
     * @return true 表示校验通过
     */
    fun verifySha256(context: Context, uri: Uri, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    // ==================== APK 下载与安装 ====================

    /**
     * 启动 APK 下载（通过 DownloadManager）。
     *
     * @param scope 协程作用域，用于在 BroadcastReceiver 中启动安装流程（避免阻塞主线程）
     * @return 注册的 BroadcastReceiver（调用方需在 onDestroy 中注销）
     */
    fun startDownload(
        context: Context,
        url: String,
        tag: String,
        sha256: String,
        downloadIdHolder: (Long) -> Unit,
        scope: CoroutineScope
    ): BroadcastReceiver {
        val fileName = "UFITOOLS-Widget-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("UFITOOLS-Widget")
            .setDescription("正在下载 $tag 版本...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        downloadIdHolder(downloadId)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    // 切换到 scope 对应的协程上下文执行安装（含 IO 线程的 SHA256 校验）
                    scope.launch {
                        installApk(context, fileName, sha256, downloadId)
                    }
                    try { context.unregisterReceiver(this) } catch (_: Exception) { }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        return receiver
    }

    /**
     * 安装已下载的 APK 文件（含 SHA256 校验）。
     * 通过 DownloadManager.Query 获取文件 URI，适配 Android 10+ Scoped Storage。
     */
    suspend fun installApk(context: Context, fileName: String, sha256: String, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val uri = dm.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusCol >= 0) {
                    val status = cursor.getInt(statusCol)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriCol >= 0) {
                            val uriStr = cursor.getString(uriCol)
                            if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null
                        } else null
                    } else null
                } else null
            } else null
        }

        if (uri == null) {
            ToastUtil.showDropToast(context, ToastStyle.WARNING, "下载文件不存在")
            return
        }

        // SHA256 校验（移至 IO 线程，避免阻塞主线程）
        if (sha256.isNotBlank()) {
            val verified = withContext(Dispatchers.IO) {
                verifySha256(context, uri, sha256)
            }
            if (!verified) {
                dm.remove(downloadId)
                ToastUtil.showDropToast(context, ToastStyle.WARNING, "文件校验失败", "已删除损坏文件，请重新下载")
                return
            }
        }

        // 构建安装 Intent
        val installUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && uri.scheme == "file") {
            // file:// URI 需要通过 FileProvider 分享给安装器
            val file = File(uri.path!!)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            // content:// URI 可直接使用（Android 10+ Scoped Storage）
            uri
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 安装已下载的 APK 文件（直接从本地文件路径安装，无需 DownloadManager downloadId）。
     * 适用于缓存复用场景：APK 已在 Downloads 目录中存在。
     *
     * @param file 本地 APK 文件
     * @param sha256 version.json 中记录的 SHA256 校验值
     */
    suspend fun installApkFromFile(context: Context, file: File, sha256: String) {
        if (!file.exists()) {
            ToastUtil.showDropToast(context, ToastStyle.WARNING, "下载文件不存在")
            return
        }

        // SHA256 校验（IO 线程）
        if (sha256.isNotBlank()) {
            val verified = withContext(Dispatchers.IO) {
                verifySha256(file, sha256)
            }
            if (!verified) {
                // 校验失败：删除损坏文件
                try { file.delete() } catch (_: Exception) {}
                ToastUtil.showDropToast(context, ToastStyle.WARNING, "文件校验失败", "已删除损坏文件，请重新下载")
                return
            }
        }

        // 通过 FileProvider 生成 content:// URI
        val installUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 检查本地 Downloads 目录中是否存在指定 tag 的缓存 APK 文件。
     *
     * 优先通过 DownloadManager.Query 查找已完成且文件名匹配的下载记录，
     * 以兼容 Android 10+ Scoped Storage（File API 无法直接检测 DownloadManager 保存的文件）。
     * 回退至 File API 检查作为兜底。
     *
     * @param tag 版本标签，如 "v0.2"
     * @return (文件, downloadId) 二元组，downloadId 为 -1 表示通过 File API 找到（无 DM 记录）
     */
    fun getCachedApkFile(context: Context, tag: String): Pair<File, Long>? {
        val fileName = "UFITOOLS-Widget-$tag.apk"

        // 优先：通过 DownloadManager 查询已完成的下载记录
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        dm.query(query).use { cursor ->
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            if (uriCol >= 0 && idCol >= 0) {
                while (cursor.moveToNext()) {
                    val uriStr = cursor.getString(uriCol) ?: continue
                    if (uriStr.endsWith(fileName)) {
                        val file = File(Uri.parse(uriStr).path ?: continue)
                        if (file.length() > 0) {
                            return Pair(file, cursor.getLong(idCol))
                        }
                    }
                }
            }
        }

        // 兜底：File API（Android < 10 或启用了 requestLegacyExternalStorage）
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        return if (file.exists() && file.length() > 0) Pair(file, -1L) else null
    }

    /** 获取应用镜像化后的下载链接（含权限检查提示） */
    fun prepareDownload(
        activity: AppCompatActivity,
        url: String,
        tag: String,
        sha256: String,
        downloadIdHolder: (Long) -> Unit
    ): BroadcastReceiver? {
        val finalUrl = applyMirrorToUrl(activity, url)
        if (finalUrl.isBlank()) {
            ToastUtil.showDropToast(activity, ToastStyle.WARNING, "没有可下载的 APK")
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE
                )
                return null
            }
        }
        return startDownload(activity, finalUrl, tag, sha256, downloadIdHolder, activity.lifecycleScope)
    }

    /** 下载权限请求码，供 Activity 的 onRequestPermissionsResult 判断 */
    const val PERMISSION_REQUEST_CODE = 1001
}
