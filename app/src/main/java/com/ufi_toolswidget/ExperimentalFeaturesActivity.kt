package com.ufi_toolswidget

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeUtil

/**
 * 实验功能页面。
 * 动态配色功能入口在此页面通过卡片导航跳转到 [WidgetDynamicColorActivity]。
 */
class ExperimentalFeaturesActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        setContentView(R.layout.activity_experimental_features)

        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@ExperimentalFeaturesActivity, ThemeUtil.PageType.SETTINGS_LIST)
        }

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initDynamicColorEntry()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        updateDynamicColorSubtitle()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    /** 动态配色入口（导航到子页面） */
    private fun initDynamicColorEntry() {
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_dynamic_color),
            iconRes = R.drawable.ic_dynamic_colors,
            title = "小组件动态取色",
            showSubtitle = true,
            subtitle = "从小组件背景图提取色调，自动适配文字颜色",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(Intent(this, WidgetDynamicColorActivity::class.java))
                }
            }
        )
        updateDynamicColorSubtitle()
    }

    private fun updateDynamicColorSubtitle() {
        val enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SPUtil.getWidgetDynamicColor(this)
        val label = if (enabled) "已开启" else "已关闭"
        try {
            findViewById<TextView>(R.id.item_dynamic_color)
                .findViewById<TextView>(R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("ExperimentalFeaturesActivity", "updateSubtitle failed: ${e.message}") }
    }
}
