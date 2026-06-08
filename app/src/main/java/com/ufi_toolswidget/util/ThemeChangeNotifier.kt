package com.ufi_toolswidget.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * 主题变更通知机制。
 *
 * 当 [AppSettingsActivity] 中切换深浅色或主题配色后，通过本地广播通知所有
 * 在后台栈中的 Activity **直接刷新 UI**（不走 [Activity.recreate]，无闪烁）。
 * 小组件通过 [BaseWifiWidget.renderAllWidgets] 同步更新。
 *
 * 发送方（AppSettingsActivity）本身不注册接收器，避免自身重复刷新。
 */
object ThemeChangeNotifier {

    /** 主题变更广播 Action（包内私有） */
    const val ACTION_THEME_CHANGED = "com.ufi_toolswidget.THEME_CHANGED"

    /**
     * 通知所有界面主题已变更。
     * 在 AppSettingsActivity 保存主题设置后调用。
     */
    fun notifyThemeChanged(context: Context) {
        val intent = Intent(ACTION_THEME_CHANGED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /**
     * 为 Activity 注册主题变更接收器。
     * 收到广播后直接执行 [onChanged] 回调（主线程），不走 [Activity.recreate]。
     *
     * @param onChanged 收到主题变更后执行的回调（在主线程中调用）
     * @return 注册的 [BroadcastReceiver]，需在 [Activity.onPause] 中解注册。
     */
    fun register(activity: Activity, onChanged: Runnable): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_THEME_CHANGED
                    && !activity.isFinishing
                    && !activity.isDestroyed
                ) {
                    Handler(Looper.getMainLooper()).post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onChanged.run()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_THEME_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        return receiver
    }

    /** 解注册主题变更接收器 */
    fun unregister(activity: Activity, receiver: BroadcastReceiver?) {
        try {
            receiver?.let { activity.unregisterReceiver(it) }
        } catch (e: Exception) { DebugLogger.w("ThemeChangeNotifier", "notifyThemeChanged failed: ${e.message}") }
    }
}
