package com.ufi_toolswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_toolswidget.util.ToastUtil
import com.ufi_toolswidget.util.ToastStyle

/**
 * 接收小组件添加成功的广播回调。
 * 当用户通过 requestPinAppWidget 成功将小组件添加到桌面后触发。
 */
class WidgetAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetSize = intent.getStringExtra("widget_size") ?: ""
        val sizeLabel = when (widgetSize) {
            "4x2" -> "4×2 横向"
            else -> ""
        }
        val msg = if (sizeLabel.isNotEmpty()) "小组件 $sizeLabel 添加成功！" else "小组件添加成功！"
        ToastUtil.showDropToast(context, ToastStyle.SUCCESS, msg)
    }
}
