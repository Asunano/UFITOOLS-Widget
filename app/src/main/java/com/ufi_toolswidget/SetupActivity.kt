package com.ufi_toolswidget

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ScaleTouchListener
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
    }

    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)

        try {
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@SetupActivity, ThemeUtil.PageType.FORM)
        }
        setContentView(R.layout.activity_setup)

            // 设备地址输入框
            val itemAddress = findViewById<View>(R.id.item_device_address)
            ThemeUtil.setupInputField(itemAddress, "设备连接地址", "支持 IP:端口 或 域名", "例如 192.168.0.1:2333", android.text.InputType.TYPE_TEXT_VARIATION_URI)
            val etDeviceAddress = itemAddress.findViewById<EditText>(R.id.common_input_edit_text)

            // 认证口令输入框
            val itemToken = findViewById<View>(R.id.item_token)
            ThemeUtil.setupInputField(itemToken, "认证口令", "设备登录口令，留空则使用 admin", "输入你的登录口令", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val etToken = itemToken.findViewById<EditText>(R.id.common_input_edit_text)

            // 恢复已有配置
            val savedAddress = SPUtil.getDeviceAddress(this)
            val savedToken = SPUtil.getRawToken(this)
            if (savedToken != "admin") {
                etToken.setText(savedToken)
            }
            if (savedAddress != SPUtil.DEFAULT_DEVICE_ADDRESS) {
                etDeviceAddress.setText(savedAddress)
            }

            findViewById<View>(R.id.btn_setup_confirm).apply {
                findViewById<TextView>(R.id.common_btn_text).text = "保存并开始使用"
                setOnClickListener {
                    val address = etDeviceAddress.text.toString().trim().ifEmpty { SPUtil.DEFAULT_DEVICE_ADDRESS }
                    val token = etToken.text.toString().trim().ifEmpty { "admin" }

                    SPUtil.setDeviceAddress(this@SetupActivity, address)
                    SPUtil.saveRawToken(this@SetupActivity, token)
                    SPUtil.saveAuthToken(this@SetupActivity, NetUtil.sha256(token))
                    SPUtil.setFirstRun(this@SetupActivity, false)

                    // 初始化完成 → 重置失败状态
                    WifiWorker.resetFailureState(this@SetupActivity)

                    // 后台自动探测协议（域名填的 http 还是 https）
                    triggerProtocolProbe()

                    BaseWifiWidget.renderAllWidgets(this@SetupActivity)
                    Toast.makeText(this@SetupActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
                setOnTouchListener(ScaleTouchListener())
            }

            findViewById<View>(R.id.tv_skip).setOnClickListener {
                SPUtil.setFirstRun(this, false)
                BaseWifiWidget.renderAllWidgets(this)
                Toast.makeText(this, "已使用默认配置", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "SetupActivity onCreate crashed: ${e.message}", e)
            // 兜底：跳过配置直接用默认值
            SPUtil.setFirstRun(this, false)
            Toast.makeText(this, "配置界面异常，已使用默认配置", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    /** 后台自动探测协议（HTTPS 优先 → HTTP 回退），结果存入 SP */
    private fun triggerProtocolProbe() {
        if (!SPUtil.needsProtocolProbe(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = WifiCrawl.probeProtocol(this@SetupActivity)
            if (result != null) {
                SPUtil.setDeviceProtocol(this@SetupActivity, result)
                Log.d(TAG, "Protocol auto-detected: $result")
            } else {
                Log.d(TAG, "Protocol probe failed, using default")
            }
        }
    }
}
