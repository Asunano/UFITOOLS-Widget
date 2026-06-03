package com.ufi_toolswidget

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker

class ConfigModifyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_modify)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToFormPage(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val etDeviceIp = findViewById<EditText>(R.id.et_device_ip)
        val etDevicePort = findViewById<EditText>(R.id.et_device_port)
        val etToken = findViewById<EditText>(R.id.et_token)

        // 恢复已有配置
        etDeviceIp.setText(SPUtil.getDeviceIp(this))
        etDevicePort.setText(SPUtil.getDevicePort(this))
        val savedToken = SPUtil.getRawToken(this)
        etToken.setText(if (savedToken == "admin") "" else savedToken)

        findViewById<View>(R.id.btn_save).setOnClickListener {
            val ip = etDeviceIp.text.toString().trim().ifEmpty { SPUtil.DEFAULT_DEVICE_IP }
            val port = etDevicePort.text.toString().trim().ifEmpty { SPUtil.DEFAULT_DEVICE_PORT }
            val token = etToken.text.toString().trim().ifEmpty { "admin" }

            SPUtil.setDeviceIp(this, ip)
            SPUtil.setDevicePort(this, port)
            SPUtil.saveRawToken(this, token)
            SPUtil.saveAuthToken(this, NetUtil.sha256(token))

            // 配置已变更 → 重置 worker 失败状态，允许立即恢复刷新
            WifiWorker.resetFailureState(this)

            BaseWifiWidget.renderAllWidgets(this)
            Toast.makeText(this, "连接配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToFormPage(this)
    }
}
