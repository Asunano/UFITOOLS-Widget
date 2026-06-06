package com.ufi_toolswidget

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.ThemeUtil

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)

        // 返回
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        // ===== 软件设置 → AppSettingsActivity =====
        findViewById<android.view.View>(R.id.card_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        // ===== 配置修改 → ConfigModifyActivity =====
        findViewById<android.view.View>(R.id.card_config_modify).setOnClickListener {
            startActivity(Intent(this, ConfigModifyActivity::class.java))
        }

        // ===== 小组件设置 → WidgetSettingsActivity =====
        findViewById<android.view.View>(R.id.card_widget_settings).setOnClickListener {
            startActivity(Intent(this, WidgetSettingsActivity::class.java))
        }

        // ===== 关于 → AboutActivity =====
        findViewById<android.view.View>(R.id.card_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
    }
}
