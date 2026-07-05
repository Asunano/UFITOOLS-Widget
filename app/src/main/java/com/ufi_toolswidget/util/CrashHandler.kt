package com.ufi_toolswidget.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.ufi_toolswidget.DebugLogActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * 全局异常捕获，用于抓取闪退日志。
 */
class CrashHandler private constructor(val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_LOG_FILE = "last_crash.log"

        @Volatile
        private var instance: CrashHandler? = null

        /** 重入保护：防止 DebugLogActivity 自身崩溃导致无限循环 */
        @Volatile
        private var isCrashHandling = false

        fun init(context: Context) {
            if (instance == null) {
                synchronized(CrashHandler::class.java) {
                    if (instance == null) {
                        val handler = CrashHandler(context.applicationContext)
                        Thread.setDefaultUncaughtExceptionHandler(handler)
                        instance = handler
                    }
                }
            }
        }

        /** 读取保存的崩溃日志 */
        fun readCrashLog(context: Context): String {
            return try {
                val file = File(context.cacheDir, CRASH_LOG_FILE)
                if (file.exists()) file.readText() else ""
            } catch (_: Exception) { "" }
        }

        /** 清除崩溃日志 */
        fun clearCrashLog(context: Context) {
            try {
                val file = File(context.cacheDir, CRASH_LOG_FILE)
                if (file.exists()) file.delete()
            } catch (e: Exception) { DebugLogger.w("CrashHandler", "clearCrashLog failed: ${e.message}") }
        }
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        // 重入保护：DebugLogActivity 自身崩溃时避免无限循环
        if (isCrashHandling) {
            Process.killProcess(Process.myPid())
            exitProcess(10)
            return
        }
        isCrashHandling = true
        try {
            // 1. 记录崩溃摘要到 SP (供主界面检测)
            // 使用 commit() 而非 apply()：进程即将被杀，apply() 的异步写入可能丢失数据
            val summary = "${ex::class.java.simpleName}: ${ex.message}"
            SPUtil.getSp(context).edit()
                .putString("last_crash_summary", summary)
                .putLong("last_crash_time", System.currentTimeMillis())
                .commit()

            // 2. 将完整堆栈写入私有缓存文件
            val sw = StringWriter()
            ex.printStackTrace(PrintWriter(sw))
            val fullLog = sw.toString()
            
            try {
                File(context.cacheDir, CRASH_LOG_FILE).writeText(fullLog)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash log file", e)
            }

            // 3. 记录崩溃日志到 DebugLogger (如果还能跑的话)
            DebugLogger.logCrash(ex)
            
            // 4. 通过 AlarmManager 延迟启动日志页面，避免在崩溃进程内触发 ANR 或二次崩溃
            try {
                val intent = Intent(context, DebugLogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(DebugLogActivity.EXTRA_CRASH_MODE, true)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 500,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule crash log activity", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // 5. 委托给系统默认处理器（显示系统崩溃对话框），然后杀死进程
            try {
                defaultHandler?.uncaughtException(thread, ex)
            } catch (_: Exception) {}
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
