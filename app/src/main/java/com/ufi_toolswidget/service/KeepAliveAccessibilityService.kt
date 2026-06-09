package com.ufi_toolswidget.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ufi_toolswidget.util.DebugLogger

/**
 * 无障碍保活服务。
 *
 * 通过注册 AccessibilityService 提高进程优先级，降低被系统回收的概率。
 * 服务本身不执行任何无障碍操作，仅作为保活手段。
 *
 * 用户需在 系统设置 → 无障碍 中手动启用。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepAliveA11y"

        /** 服务是否正在运行（进程内标志） */
        @Volatile
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        DebugLogger.logSys(TAG, "Accessibility service connected, keep-alive active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理任何事件，仅作为保活机制
    }

    override fun onInterrupt() {
        DebugLogger.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        DebugLogger.w(TAG, "Accessibility service destroyed")
    }
}
