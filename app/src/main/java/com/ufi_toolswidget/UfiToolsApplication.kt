package com.ufi_toolswidget

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.ufi_toolswidget.service.BackgroundMonitorService
import com.ufi_toolswidget.util.CrashHandler
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.NotificationHelper
import com.ufi_toolswidget.util.NotificationMonitor

/**
 * 全局 Application 入口。
 *
 * 在 onCreate 中调用 [DynamicColors.applyToActivitiesIfAvailable]，
 * 为所有 Activity 注册 Material You 动态配色（Android 12+ / API 31）。
 * 这使得 [DynamicColors.wrapContextIfAvailable] 能正确返回壁纸派生色调，
 * 供小组件 Palette 构建使用。
 *
 * 低于 API 31 的设备不受影响，方法内部自动跳过。
 */
class UfiToolsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化全局崩溃捕获（必须在所有其他初始化之前，以捕获后续步骤的异常）
        CrashHandler.init(this)

        // 仅设置 DebugLogger 的 context 引用（轻量操作），使 CrashHandler 崩溃时
        // flushToFile() 可以工作。完整的 init()（含文件读取和系统信息采集）延迟到
        // Activity.onCreate() 中执行，避免阻塞 Application 启动。
        DebugLogger.setContextOnly(this)

        // 启用 Material You 动态配色：为所有 Activity 叠加动态色彩主题覆盖层。
        // 仅在 Android 12+ 且 OEM 提供动态配色时生效；低版本设备静默忽略。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // 初始化通知渠道（Android 8.0+ 需要在应用启动时创建）
        NotificationHelper.init(this)

        // 启动后台通知监控器：独立协程定时轻量检查阈值，
        // 不依赖任何 Activity，应用存活期间持续运行
        NotificationMonitor.start(this)

        // 同步前台保活服务状态：根据 SP 开关决定是否启动常驻服务
        try {
            BackgroundMonitorService.syncState(this)
        } catch (e: Exception) {
            DebugLogger.w("UfiToolsApp", "BackgroundMonitorService syncState failed: ${e.message}")
        }
    }
}
