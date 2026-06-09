package com.ufi_toolswidget.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 多维度调试诊断系统。
 *
 * 分类：
 * - [API/IO]   网络请求、API 调用、数据解析
 * - [UI]       视图状态、渲染事件、主题更新
 * - [SYS]      系统信息、内存、进程状态
 * - [LIFECYCLE] Activity/Fragment 生命周期
 * - [EXCEPTION] 异常捕获、崩溃处理
 * - 快照       系统状态、视图层级一键导出
 */
object DebugLogger {

    private const val TAG = "DebugLogger"
    private const val MAX_ENTRIES = 800
    private const val FLUSH_THRESHOLD = 20  // 待写入条目超过此数量时自动落盘

    // ── 预编译脱敏 Regex ──
    private val IP_MASK_RE = Regex("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    private val IMEI_MASK_RE = Regex("\\b\\d{15,17}\\b")
    private val TOKEN_MASK_RE = Regex("\"(token|password|auth_token|imei)\"\\s*:\\s*\"[^\"]+\"")
    private val AUTH_MASK_RE = Regex("Authorization: [^\\s]+")

    /** 日志分类 */
    enum class Category(val label: String, val colorTag: String) {
        API("API/数据", "API"),
        UI("UI渲染", "UI"),
        SYS("系统", "SYS"),
        LIFECYCLE("生命周期", "LIFE"),
        EXCEPTION("异常", "EXC"),
        GENERAL("通用", "GEN"),
    }

    /** 所有日志条目（线程安全，synchronized ArrayDeque） */
    private val entriesLock = Any()
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    /** 待写入文件队列（线程安全，无锁 ConcurrentLinkedQueue）。
     *  log() 中入队，force 日志或条目达到阈值时自动 flush，onPause() 时手动 flush。 */
    private val pendingEntries = ConcurrentLinkedQueue<Entry>()

    /** 是否启用调试模式 (通过 SP 持久化) */
    var enabled = false
        set(value) {
            field = value
            contextRef?.get()?.let { SPUtil.setDebugEnabled(it, value) }
        }

    private var contextRef: java.lang.ref.WeakReference<Context>? = null

    /** 是否已完成完整初始化（文件加载 + 系统信息采集） */
    private var fullyInitialized = false

    /** 系统信息缓存（init 时生成） */
    private val systemInfoCache = StringBuilder()

    /** UI 诊断快照（最近一次 captureUiSnapshot 的内容） */
    private var lastUiSnapshot = ""

    /** 渲染事件计数器 */
    private var renderEventCount = 0
    private var lastRenderEventTime = 0L

    data class Entry(
        val time: Long,
        val level: String,
        val category: Category,
        val tag: String,
        val message: String,
    ) {
        fun formatted(): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            return "[${sdf.format(Date(time))}] [${category.colorTag}] [$level] [$tag] $message"
        }
    }

    // ==================== 初始化 ====================

    /**
     * 轻量初始化：仅设置 context 引用和 enabled 状态。
     * 用于 Application.onCreate()，不阻塞主线程。
     * 使 CrashHandler 崩溃时 flushToFile() 可以工作。
     */
    fun setContextOnly(context: Context) {
        if (contextRef?.get() != null) return  // 已初始化则跳过
        contextRef = java.lang.ref.WeakReference(context.applicationContext)
        enabled = SPUtil.getDebugEnabled(context)
    }

    /** 初始化，载入持久化配置并采集系统信息（含文件 I/O，应在 Activity 中调用） */
    fun init(context: Context) {
        contextRef = java.lang.ref.WeakReference(context.applicationContext)
        enabled = SPUtil.getDebugEnabled(context)
        if (!fullyInitialized) {
            fullyInitialized = true
            loadPreviousLogs()
            captureSystemInfo(context)
        }
        log(Category.SYS, "DebugLogger", "DebugLogger initialized, enabled=$enabled")
    }

    /**
     * 从持久化文件加载上一轮的日志到内存。
     * 供 :crash_handler 等独立进程使用（这些进程不会走 init 流程）。
     */
    fun loadPreviousLogsIfAvailable(context: Context) {
        contextRef = java.lang.ref.WeakReference(context.applicationContext)
        enabled = SPUtil.getDebugEnabled(context)
        loadPreviousLogs()
    }

    // ==================== 系统信息采集 ====================

    /** 采集设备/系统/进程基础信息 */
    private fun captureSystemInfo(ctx: Context) {
        systemInfoCache.clear()
        systemInfoCache.appendLine("========== 系统信息 ==========")
        systemInfoCache.appendLine("采集时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")

        // Android & 设备
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 设备 ---")
        systemInfoCache.appendLine("品牌/型号: ${Build.BRAND} / ${Build.MODEL}")
        systemInfoCache.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        systemInfoCache.appendLine("构建: ${Build.DISPLAY}")
        systemInfoCache.appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString(",")}")

        // 屏幕
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.display?.getRealMetrics(dm)
        } else {
            wm?.defaultDisplay?.getRealMetrics(dm)
        }
        systemInfoCache.appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels}, dpi=${dm.densityDpi}, density=${dm.density}")

        // 进程
        val pm = ctx.packageManager
        val pi = try { pm.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) {
            // 诊断初始化阶段，getPackageInfo 失败不影响核心功能
            null
        }
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 应用 ---")
        systemInfoCache.appendLine("包名: ${ctx.packageName}")
        systemInfoCache.appendLine("版本: ${pi?.versionName ?: "unknown"} (${pi?.versionCode ?: 0})")
        systemInfoCache.appendLine("PID: ${Process.myPid()}")
        systemInfoCache.appendLine("进程名: ${getProcessName(ctx)}")

        // 内存
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 运行时内存 ---")
        systemInfoCache.appendLine("PSS: ${formatMem(memInfo.totalPss.toLong() * 1024)}")
        systemInfoCache.appendLine("Native: ${formatMem(memInfo.nativePss.toLong() * 1024)}")
        systemInfoCache.appendLine("Dalvik: ${formatMem(memInfo.dalvikPss.toLong() * 1024)}")
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        systemInfoCache.appendLine("系统可用: ${formatMem(mi.availMem)}")
        systemInfoCache.appendLine("低内存: ${mi.lowMemory}")

        // 存储
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 存储 ---")
        val dataDir = ctx.filesDir
        systemInfoCache.appendLine("应用数据目录: ${dataDir.absolutePath}")
        systemInfoCache.appendLine("可用空间: ${formatMem(dataDir.usableSpace)}")
        systemInfoCache.appendLine("总计: ${formatMem(dataDir.totalSpace)}")

        // 运行时环境
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 运行时 ---")
        systemInfoCache.appendLine("VM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        systemInfoCache.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    }

    /** 获取系统信息文本 */
    fun getSystemInfo() = systemInfoCache.toString()

    /** 从文件加载上一轮持久化的日志到内存 */
    private fun loadPreviousLogs() {
        val ctx = contextRef?.get() ?: return
        try {
            val file = getLogFilePath(ctx)
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.isEmpty()) return
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val categoryMap = Category.values().associateBy { it.colorTag }
            synchronized(entriesLock) {
                // 避免重复加载
                if (entries.isNotEmpty()) return
                lines.takeLast(MAX_ENTRIES).forEach { line ->
                    // 格式: [MM-dd HH:mm:ss.SSS] [TAG] [LEVEL] [srcTag] message
                    val timeMatch = Regex("\\[(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})]").find(line)
                    val tagMatch = Regex("\\] \\[(\\w+)] \\[([DEIW])]").find(line)
                    if (timeMatch != null && tagMatch != null) {
                        val time = try { sdf.parse(timeMatch.groupValues[1])?.time ?: 0L } catch (_: Exception) { 0L }
                        val category = categoryMap[tagMatch.groupValues[1]] ?: Category.GENERAL
                        val level = tagMatch.groupValues[2]
                        entries.addLast(Entry(time, level, category, "prev", line))
                    } else {
                        entries.addLast(Entry(0L, "D", Category.GENERAL, "prev", line))
                    }
                }
                while (entries.size > MAX_ENTRIES) {
                    entries.removeFirst()
                }
            }
            Log.d(TAG, "Loaded ${lines.size} previous log entries from file")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load previous logs: ${e.message}")
        }
    }

    // ==================== UI 诊断 ====================

    /** 捕获指定 View 的层级信息快照（不含敏感数据） */
    fun captureUiSnapshot(root: View?): String {
        if (root == null) return "根视图为 null"
        val sb = StringBuilder()
        sb.appendLine("========== UI 视图诊断 ==========")
        sb.appendLine("采集时间: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
        sb.appendLine("渲染事件总数: $renderEventCount")
        sb.appendLine()

        dumpViewHierarchy(root, sb, 0)
        lastUiSnapshot = sb.toString()
        return lastUiSnapshot
    }

    /** 获取上一次 UI 快照 */
    fun getLastUiSnapshot() = lastUiSnapshot.ifEmpty { "(尚未采集 UI 快照)" }

    /** 递归打印视图层级 */
    private fun dumpViewHierarchy(view: View, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val cls = view.javaClass.simpleName
        val id = if (view.id != View.NO_ID) try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "#${view.id}" } else "NO_ID"
        val visibility = when (view.visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            View.GONE -> "G"
            else -> "?"
        }
        val dims = "(${view.width}x${view.height})"

        // 额外信息
        val extras = mutableListOf<String>()
        if (view is TextView) {
            extras.add("text=\"${view.text}\"")
            extras.add("size=${view.textSize}")
            extras.add("color=#${Integer.toHexString(view.currentTextColor)}")
        }
        extras.add("vis=$visibility")
        extras.add(dims)

        sb.appendLine("$indent$cls [$id] ${extras.joinToString(" ")}")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpViewHierarchy(view.getChildAt(i), sb, depth + 1)
            }
        }
    }

    // ==================== 日志记录（分类） ====================

    /** 记录一条带分类的调试日志 */
    fun log(category: Category, tag: String, message: String, force: Boolean = false) {
        if (!enabled && !force) return

        val maskedMsg = maskSensitiveInfo(message)
        val entry = Entry(System.currentTimeMillis(), categoryLevel(category), category, tag, maskedMsg)
        synchronized(entriesLock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }

        // Logcat 输出
        val logMsg = "[${category.colorTag}] [$tag] $maskedMsg"
        when (category) {
            Category.EXCEPTION -> Log.e(tag, logMsg)
            else -> Log.d(tag, logMsg)
        }

        // 统计渲染事件
        if (category == Category.UI) {
            renderEventCount++
            lastRenderEventTime = System.currentTimeMillis()
        }

        // 只入队，批量 flush 时统一写入
        pendingEntries.add(entry)

        // force 日志立即落盘（关键错误/诊断信息不能丢）
        // 待写入条目过多时也自动落盘，防止进程被杀丢失大量日志
        if (force || pendingEntries.size >= FLUSH_THRESHOLD) {
            flushToFile()
        }
    }

    private fun categoryLevel(cat: Category) = when (cat) {
        Category.EXCEPTION -> "E"
        Category.SYS -> "I"
        else -> "D"
    }

    /** 便捷方法：按分类记录 */
    fun logApi(tag: String, msg: String) = log(Category.API, tag, msg)
    fun logApiErr(tag: String, msg: String) = log(Category.API, tag, msg)
    fun logUi(tag: String, msg: String) = log(Category.UI, tag, msg)
    fun logSys(tag: String, msg: String) = log(Category.SYS, tag, msg)
    fun logLife(tag: String, msg: String) = log(Category.LIFECYCLE, tag, msg)
    fun logExc(tag: String, msg: String) = log(Category.EXCEPTION, tag, msg)

    /** 通用记录（向后兼容） */
    fun d(tag: String, message: String) = log(Category.GENERAL, tag, message)
    fun i(tag: String, message: String) = log(Category.GENERAL, tag, message)
    fun w(tag: String, message: String, force: Boolean = false) = log(Category.GENERAL, tag, message, force)
    fun e(tag: String, message: String, force: Boolean = false) = log(Category.EXCEPTION, tag, message, force)

    /** 记录崩溃异常 */
    fun logCrash(ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        log(Category.EXCEPTION, "CrashHandler", sw.toString(), force = true)
        flushToFile() // 崩溃日志立即落盘
    }

    // ==================== 查询方法 ====================

    fun getAll(): List<Entry> = synchronized(entriesLock) { entries.toList().reversed() }
    fun getRecent(n: Int = 100): List<Entry> = synchronized(entriesLock) { entries.takeLast(n).reversed() }
    fun size(): Int = synchronized(entriesLock) { entries.size }

    /** 按分类筛选 */
    fun getByCategory(cat: Category, n: Int = 50): List<Entry> =
        synchronized(entriesLock) {
            entries.filter { it.category == cat }.takeLast(n).reversed()
        }

    /** 获取所有日志的格式化文本 */
    fun getAllText(): String = getAll().joinToString("\n") { it.formatted() }

    /** 获取分类统计 */
    fun getCategoryStats(): Map<Category, Int> =
        synchronized(entriesLock) {
            entries.groupBy { it.category }.mapValues { it.value.size }
        }

    // ==================== 全量诊断报告 ====================

    /** 生成完整诊断报告：系统信息 + UI快照 + 分类日志 + API状态 */
    fun generateFullReport(context: Context, rootView: View? = null): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.appendLine("╔══════════════════════════════════════════╗")
        sb.appendLine("║     UFITOOLS-Widget 全量诊断报告          ║")
        sb.appendLine("║     生成时间: $now               ║")
        sb.appendLine("╚══════════════════════════════════════════╝")
        sb.appendLine()

        // 1. 系统信息
        sb.appendLine(systemInfoCache.toString())
        sb.appendLine()

        // 2. UI 诊断（如有）
        if (rootView != null) {
            captureUiSnapshot(rootView)
        }
        if (lastUiSnapshot.isNotEmpty()) {
            sb.appendLine(lastUiSnapshot)
            sb.appendLine()
        }

        // 3. 日志分类统计
        val stats = getCategoryStats()
        if (stats.isNotEmpty()) {
            sb.appendLine("========== 日志统计 ==========")
            stats.toList().sortedByDescending { it.second }.forEach { (cat, count) ->
                sb.appendLine("  ${cat.label}: $count 条")
            }
            sb.appendLine("  总计: ${size()} 条")
            sb.appendLine()
        }

        // 4. 最近 50 条 API 日志
        val apiLogs = getByCategory(Category.API, 50)
        if (apiLogs.isNotEmpty()) {
            sb.appendLine("========== 最近 API 日志 (50条) ==========")
            apiLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 5. 最近 30 条 UI 日志
        val uiLogs = getByCategory(Category.UI, 30)
        if (uiLogs.isNotEmpty()) {
            sb.appendLine("========== 最近 UI 渲染日志 (30条) ==========")
            uiLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 6. 异常日志（最近 20 条）
        val excLogs = getByCategory(Category.EXCEPTION, 20)
        if (excLogs.isNotEmpty()) {
            sb.appendLine("========== 最近异常日志 (20条) ==========")
            excLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 7. API 状态快照
        sb.appendLine(dumpApiState(context))

        return sb.toString()
    }

    /** API 状态快照（连接/WiFiCrawl 最后状态） */
    fun dumpApiState(context: Context): String {
        val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.appendLine("========== API 连接状态 ==========")
        sb.appendLine("目标地址: ${desensitize(SPUtil.getDeviceAddress(context))}")
        sb.appendLine("完整 URL: ${desensitize(SPUtil.buildBaseUrl(context))}")
        sb.appendLine("认证令牌: [ENCRYPTED]")
        sb.appendLine()
        sb.appendLine("[运行状态]")
        sb.appendLine("Worker 停止: ${sp.getBoolean("worker_stopped_by_failure", false)}")
        sb.appendLine("失败计数 (API/Net): ${sp.getInt("worker_api_failure_count", 0)} / ${sp.getInt("worker_network_failure_count", 0)}")
        sb.appendLine("停止原因: ${SPUtil.getWorkerStopReason(context)}")
        sb.appendLine("探测协议: ${SPUtil.getDeviceProtocol(context)}")
        sb.appendLine()
        sb.appendLine("[最后请求]")
        sb.appendLine("错误: ${WifiCrawl.lastError}")
        sb.appendLine("响应 (脱敏): ${maskSensitiveInfo(WifiCrawl.lastRawResponse).take(1000)}")
        return sb.toString()
    }

    /** 转储当前状态（向后兼容别名） */
    fun dumpState(context: Context): String = dumpApiState(context)

    // ==================== 清理 ====================

    fun clear() {
        synchronized(entriesLock) { entries.clear() }
        lastUiSnapshot = ""
        renderEventCount = 0
        fullyInitialized = false
        contextRef?.get()?.let { ctx ->
            try {
                val file = getLogFilePath(ctx)
                if (file.exists()) file.delete()
            } catch (_: Exception) {
                // 清理日志文件失败（权限/磁盘问题），非关键错误
                Log.w(TAG, "Failed to delete debug log file")
            }
        }
        // 清空待写入队列，防止 clear 后还有旧数据落盘
        pendingEntries.clear()
    }

    /** 获取持久化的日志文件路径 */
    private fun getLogFilePath(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")

    /** 获取持久化的日志内容（用于诊断页面显示） */
    fun getPersistentLogs(ctx: Context): String {
        return try {
            val file = getLogFilePath(ctx)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            "读取持久化日志失败: ${e.message}"
        }
    }

    // ==================== 脱敏 ====================

    /** 公开脱敏方法，供 CrashHandler 等外部组件使用 */
    fun maskSensitive(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(IP_MASK_RE, "$1.***.***.***")
            .replace(IMEI_MASK_RE, "***************")
            .replace(TOKEN_MASK_RE, "\"$1\":\"***\"")
            .replace(AUTH_MASK_RE, "Authorization: [MASKED]")
    }

    /** 智能识别并脱敏敏感信息 */
    private fun maskSensitiveInfo(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(IP_MASK_RE, "$1.***.***.***")
            .replace(IMEI_MASK_RE, "***************")
            .replace(TOKEN_MASK_RE, "\"$1\":\"***\"")
            .replace(AUTH_MASK_RE, "Authorization: [MASKED]")
    }

    private fun desensitize(input: String): String {
        if (input.isEmpty()) return ""
        if (input.length <= 6) return "****"
        return input.take(3) + "****" + input.takeLast(3)
    }

    // ==================== 批量文件写入 ====================

    /** 文件写入锁，防止并发 flush 导致行交错 */
    private val fileWriteLock = Any()

    private var lastLogFile: File? = null

    /** 将队列中所有待写入条目一次性批量写入文件（仅一次 fopen/fclose） */
    fun flushToFile() {
        contextRef?.get()?.let { ctx ->
            val batch = mutableListOf<Entry>()
            while (pendingEntries.isNotEmpty()) {
                pendingEntries.poll()?.let { batch.add(it) }
            }
            if (batch.isEmpty()) return

            synchronized(fileWriteLock) {
                try {
                    val file = getLogFilePath(ctx)
                    if (lastLogFile != file) {
                        lastLogFile = file
                        file.parentFile?.mkdirs()
                    }
                    if (file.exists() && file.length() > 3 * 1024 * 1024) {
                        file.writeText("[Log truncated due to size]\n")
                    }
                    FileWriter(file, true).use { writer ->
                        batch.forEach { writer.appendLine(it.formatted()) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to flush log to file: ${e.message}")
                }
            }
        }
    }

    private fun formatMem(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun getProcessName(ctx: Context): String {
        return try {
            val pid = Process.myPid()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
