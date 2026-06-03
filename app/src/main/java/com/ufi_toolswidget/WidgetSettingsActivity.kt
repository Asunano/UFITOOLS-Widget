package com.ufi_toolswidget

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import java.util.concurrent.TimeUnit

class WidgetSettingsActivity : AppCompatActivity() {

    private val intervalOptions = listOf(
        "15 分钟 (推荐)" to 15,
        "30 分钟" to 30,
        "1 小时" to 60,
        "2 小时" to 120,
        "仅手动刷新" to 0
    )

    /** 主界面刷新间隔选项（秒） */
    private val mainIntervalOptions = listOf(
        "5 秒 (推荐)" to 5,
        "10 秒" to 10,
        "15 秒" to 15,
        "30 秒" to 30,
        "关闭自动刷新" to 0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_settings)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToWidgetSettingsPage(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // ===== 显示项 CheckBox =====
        val cbFlow = findViewById<CheckBox>(R.id.cb_show_flow)
        val cbTemp = findViewById<CheckBox>(R.id.cb_show_temp)
        val cbModel = findViewById<CheckBox>(R.id.cb_show_model)
        val cbSignal = findViewById<CheckBox>(R.id.cb_show_signal)
        val cbBattery = findViewById<CheckBox>(R.id.cb_show_battery)
        val cbCpu = findViewById<CheckBox>(R.id.cb_show_cpu)
        val cbMem = findViewById<CheckBox>(R.id.cb_show_mem)
        val cbTime = findViewById<CheckBox>(R.id.cb_show_time)

        // 恢复显示项设置
        cbFlow.isChecked = SPUtil.getShowFlow(this)
        cbTemp.isChecked = SPUtil.getShowTemp(this)
        cbModel.isChecked = SPUtil.getShowModel(this)
        cbSignal.isChecked = SPUtil.getShowSignal(this)
        cbBattery.isChecked = SPUtil.getShowBattery(this)
        cbCpu.isChecked = SPUtil.getShowCpu(this)
        cbMem.isChecked = SPUtil.getShowMem(this)
        cbTime.isChecked = SPUtil.getShowTime(this)

        // ===== 后台刷新频率 =====
        val spInterval = findViewById<AutoCompleteTextView>(R.id.sp_interval)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, intervalOptions.map { it.first })
        spInterval.setAdapter(adapter)
        val savedInterval = SPUtil.getRefreshInterval(this)
        val defaultLabel = intervalOptions.firstOrNull { it.second == savedInterval }?.first
            ?: intervalOptions[0].first
        spInterval.setText(defaultLabel, false)

        // ===== 主界面刷新频率 =====
        val spMainInterval = findViewById<AutoCompleteTextView>(R.id.sp_main_interval)
        val mainAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mainIntervalOptions.map { it.first })
        spMainInterval.setAdapter(mainAdapter)
        val savedMainInterval = SPUtil.getMainRefreshSeconds(this)
        val defaultMainLabel = mainIntervalOptions.firstOrNull { it.second == savedMainInterval }?.first
            ?: mainIntervalOptions[0].first
        spMainInterval.setText(defaultMainLabel, false)

        // ===== 小组件主题 =====
        val toggleWidget = findViewById<MaterialButtonToggleGroup>(R.id.toggle_widget_theme)
        when (SPUtil.getWidgetTheme(this)) {
            "light" -> toggleWidget.check(R.id.btn_widget_theme_light)
            "dark" -> toggleWidget.check(R.id.btn_widget_theme_dark)
            else -> toggleWidget.check(R.id.btn_widget_theme_follow)
        }

        // ===== 保存 =====
        findViewById<View>(R.id.btn_save).setOnClickListener {
            // 保存显示项
            SPUtil.setShowFlow(this, cbFlow.isChecked)
            SPUtil.setShowTemp(this, cbTemp.isChecked)
            SPUtil.setShowModel(this, cbModel.isChecked)
            SPUtil.setShowSignal(this, cbSignal.isChecked)
            SPUtil.setShowBattery(this, cbBattery.isChecked)
            SPUtil.setShowCpu(this, cbCpu.isChecked)
            SPUtil.setShowMem(this, cbMem.isChecked)
            SPUtil.setShowTime(this, cbTime.isChecked)

            // 保存后台刷新频率
            val selectedLabel = spInterval.text.toString()
            val selectedMinutes = intervalOptions.firstOrNull { it.first == selectedLabel }?.second ?: 15
            SPUtil.setRefreshInterval(this, selectedMinutes)

            // 保存主界面刷新频率
            val selectedMainLabel = spMainInterval.text.toString()
            val selectedMainSeconds = mainIntervalOptions.firstOrNull { it.first == selectedMainLabel }?.second ?: 5
            SPUtil.setMainRefreshSeconds(this, selectedMainSeconds)

            // 保存小组件主题
            val widgetTheme = when (toggleWidget.checkedButtonId) {
                R.id.btn_widget_theme_light -> "light"
                R.id.btn_widget_theme_dark -> "dark"
                else -> "follow_app"
            }
            SPUtil.setWidgetTheme(this, widgetTheme)

            // 更新 Worker
            if (selectedMinutes <= 0) {
                WorkManager.getInstance(this).cancelUniqueWork("wifi_crawl")
            } else {
                val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(
                    selectedMinutes.toLong(), TimeUnit.MINUTES
                ).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "wifi_crawl",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }

            BaseWifiWidget.renderAllWidgets(this)
            Toast.makeText(this, "小组件设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToWidgetSettingsPage(this)
    }
}
