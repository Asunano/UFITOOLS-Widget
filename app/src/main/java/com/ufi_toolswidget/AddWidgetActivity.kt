package com.ufi_toolswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.widget.WifiWidget4x2

class AddWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetSize = intent.getStringExtra("widget_size") ?: "4x2"
        Log.d("AddWidget", "Requesting size: $widgetSize")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pinned = tryPinWidget(widgetSize)
            if (!pinned) {
                tryPinShortcut(widgetSize)
            }
        }

        window.decorView.postDelayed({ finish() }, 800)
    }

    /** 尝试钉选小组件（现代 API，无需权限） */
    private fun tryPinWidget(widgetSize: String): Boolean {
        return try {
            val appWidgetManager = getSystemService(AppWidgetManager::class.java)
            if (appWidgetManager?.isRequestPinAppWidgetSupported != true) return false

            val widgetClass = WifiWidget4x2::class.java
            val intent = Intent(this, WidgetAddedReceiver::class.java).apply {
                putExtra("widget_size", widgetSize)
            }
            val callback = PendingIntent.getBroadcast(
                this, widgetSize.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetManager.requestPinAppWidget(ComponentName(this, widgetClass), null, callback)
            Toast.makeText(this, "正在请求添加 $widgetSize 小组件", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e("AddWidget", "Pin widget failed", e)
            false
        }
    }

    /** 尝试钉选桌面快捷方式（ShortcutManager 现代 API，无需权限） */
    private fun tryPinShortcut(widgetSize: String): Boolean {
        return try {
            val shortcutManager = getSystemService(ShortcutManager::class.java)
            if (shortcutManager?.isRequestPinShortcutSupported != true) return false

            val shortcutInfo = ShortcutInfo.Builder(this, "add_widget_$widgetSize")
                .setShortLabel("UFI 小组件")
                .setLongLabel("添加 UFI 工具小组件")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                })
                .build()

            shortcutManager.requestPinShortcut(shortcutInfo, null)
            Toast.makeText(this, "正在请求添加桌面快捷方式", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e("AddWidget", "Pin shortcut failed", e)
            false
        }
    }
}
