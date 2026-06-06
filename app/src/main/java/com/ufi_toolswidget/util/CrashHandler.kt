package com.ufi_toolswidget.util

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
            } catch (_: Exception) {}
        }
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            // 1. 记录崩溃摘要到 SP (供主界面检测)
            val summary = "${ex::class.java.simpleName}: ${ex.message}"
            SPUtil.setLastCrashSummary(context, summary)
            SPUtil.setLastCrashTime(context, System.currentTimeMillis())

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
            
            // 4. 启动调试日志页面显示错误 (使用独立进程防止被干掉)
            val intent = Intent(context, DebugLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(DebugLogActivity.EXTRA_CRASH_MODE, true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // 5. 彻底杀死进程防止僵死
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
