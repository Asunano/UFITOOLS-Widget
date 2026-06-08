package com.ufi_toolswidget

import android.app.Dialog
import android.content.BroadcastReceiver
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.NetUtil
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ufi_toolswidget.util.ToastStyle
import com.ufi_toolswidget.util.ToastUtil

class ConfigModifyActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    // ==================== 当前值缓存 ====================
    private var deviceAddress: String = ""
    private var rawToken: String = ""
    private var deviceInfoPath: String = ""
    private var atCommandPath: String = ""
    private var goformCommandPath: String = ""
    private var secretKey: String = ""

    // ==================== 活跃弹窗引用 ====================
    private var activeDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@ConfigModifyActivity, ThemeUtil.PageType.FORM)
            refreshAllSubtitles()
        }
        setContentView(R.layout.activity_config_modify)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        loadCurrentValues()
        initAllItems()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        loadCurrentValues()
        refreshAllSubtitles()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 数据加载 ====================

    private fun loadCurrentValues() {
        deviceAddress = SPUtil.getDeviceAddress(this)
        rawToken = SPUtil.getRawToken(this)
        deviceInfoPath = SPUtil.getDeviceInfoPath(this)
        atCommandPath = SPUtil.getAtCommandPath(this)
        goformCommandPath = SPUtil.getGoformCommandPath(this)
        secretKey = SPUtil.getSecretKey(this)
    }

    // ==================== 初始化设置项（仅 2 项） ====================

    private fun initAllItems() {
        // 基础连接：不显示副标题
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_basic_config),
            iconRes = R.drawable.ic_router,
            title = "基础连接",
            showSubtitle = false,
            onClick = ::showBasicConfigDialog
        )

        // 高级配置：无副标题，点击先弹出警告确认
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_advanced_config),
            iconRes = R.drawable.ic_chip,
            title = "高级配置",
            showSubtitle = false,
            onClick = ::showAdvancedConfigDialog
        )
    }

    private fun refreshAllSubtitles() {
        loadCurrentValues()
    }

    // ==================== 基础连接弹窗（地址 + 口令） ====================

    private fun showBasicConfigDialog() {
        val fields = listOf(
            DialogField(
                label = "设备连接地址",
                currentValue = deviceAddress,
                hint = "留空则不修改",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "认证口令",
                currentValue = rawToken,
                hint = "留空则不修改",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )

        showMultiEditDialog(
            title = "基础连接",
            icon = R.drawable.ic_router,
            fields = fields,
            onSave = { values ->
                var changed = false
                // 留空则不修改
                val newAddress = values[0].trim()
                if (newAddress.isNotEmpty() && newAddress != deviceAddress) {
                    deviceAddress = newAddress
                    SPUtil.setDeviceAddress(this, newAddress)
                    changed = true
                }
                val newToken = values[1].trim()
                if (newToken.isNotEmpty() && newToken != rawToken) {
                    rawToken = newToken
                    SPUtil.saveRawToken(this, newToken)
                    SPUtil.saveAuthToken(this, NetUtil.sha256(newToken))
                    changed = true
                }
                if (changed) onConfigChanged()
            }
        )
    }

    // ==================== 高级配置（红色警告弹窗 → 编辑弹窗） ====================

    private fun showAdvancedConfigDialog() {
        activeDialog?.takeIf { it.isShowing }?.dismiss()
        activeDialog = CommonDialogHelper.showWarningConfirmDialog(
            context = this,
            title = "警告",
            message = "正常情况切勿修改高级配置\n错误配置将导致设备功能异常",
            confirmText = "继续修改",
            onConfirm = ::showAdvancedConfigDialogInternal
        )
    }

    private fun showAdvancedConfigDialogInternal() {
        val fields = listOf(
            DialogField(
                label = "设备信息接口",
                currentValue = if (deviceInfoPath == SPUtil.DEFAULT_DEVICE_INFO_PATH) "" else deviceInfoPath,
                hint = "默认 ${SPUtil.DEFAULT_DEVICE_INFO_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "AT 命令接口",
                currentValue = if (atCommandPath == SPUtil.DEFAULT_AT_COMMAND_PATH) "" else atCommandPath,
                hint = "默认 ${SPUtil.DEFAULT_AT_COMMAND_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "Goform 命令接口",
                currentValue = if (goformCommandPath == SPUtil.DEFAULT_GOFORM_COMMAND_PATH) "" else goformCommandPath,
                hint = "默认 ${SPUtil.DEFAULT_GOFORM_COMMAND_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "签名密钥",
                currentValue = if (secretKey == SPUtil.DEFAULT_SECRET_KEY) "" else secretKey,
                hint = "默认 ${SPUtil.DEFAULT_SECRET_KEY}",
                inputType = InputType.TYPE_CLASS_TEXT
            ),
            DialogField(
                label = "设备平台 (AT 解析)",
                currentValue = SPUtil.getCachedPlatform(this).ifEmpty { "auto" },
                hint = "auto / spreadtrum / quectel",
                inputType = InputType.TYPE_CLASS_TEXT
            )
        )

        showMultiEditDialog(
            title = "高级配置",
            icon = R.drawable.ic_chip,
            fields = fields,
            onRestoreDefaults = {
                // 恢复所有高级字段为默认值
                deviceInfoPath = ""
                SPUtil.setDeviceInfoPath(this, "")
                atCommandPath = ""
                SPUtil.setAtCommandPath(this, "")
                goformCommandPath = ""
                SPUtil.setGoformCommandPath(this, "")
                secretKey = ""
                SPUtil.setSecretKey(this, "")
                SPUtil.setCachedPlatform(this, "")
                SPUtil.invalidateResponseCaches(this)
                onConfigChanged()
            },
            onSave = { values ->
                deviceInfoPath = values[0].trim()
                SPUtil.setDeviceInfoPath(this, deviceInfoPath)
                atCommandPath = values[1].trim()
                SPUtil.setAtCommandPath(this, atCommandPath)
                goformCommandPath = values[2].trim()
                SPUtil.setGoformCommandPath(this, goformCommandPath)
                secretKey = values[3].trim()
                SPUtil.setSecretKey(this, secretKey)
                
                // 接口路径或密钥变更，清除所有响应缓存以确保下轮使用新路径
                SPUtil.invalidateResponseCaches(this)

                val platform = values[4].trim().lowercase()
                SPUtil.setCachedPlatform(this, if (platform == "auto") "" else platform)

                onConfigChanged()
            }
        )
    }

    // ==================== 多字段 EditText 弹窗 ====================

    private data class DialogField(
        val label: String,
        val currentValue: String,
        val hint: String,
        val inputType: Int
    )

    private fun showMultiEditDialog(
        title: String,
        icon: Int,
        fields: List<DialogField>,
        onRestoreDefaults: (() -> Unit)? = null,
        onSave: (List<String>) -> Unit
    ) {
        activeDialog?.takeIf { it.isShowing }?.dismiss()
        activeDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(icon)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val editTexts = mutableListOf<EditText>()

        for ((index, field) in fields.withIndex()) {
            // 字段标签
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp2px(12)
                    bottomMargin = dp2px(4)
                }
                text = field.label
                setTextColor(textPrimary)
                textSize = 13f
            }
            content.addView(label)

            // 输入框
            val etInput = CommonSettingsItemHelper.createThemedEditText(
                this,
                hint = field.hint,
                text = field.currentValue,
                inputType = field.inputType
            )

            // 针对平台选择特殊处理：禁止手动输入，点击弹出选择列表
            if (field.label.contains("设备平台")) {
                CommonSettingsItemHelper.setupDropdownOnEditText(
                    etInput,
                    options = arrayOf("auto (自动探测)", "spreadtrum (展讯)", "quectel (移远)"),
                    values = arrayOf("auto", "spreadtrum", "quectel"),
                    currentValue = etInput.text.toString()
                )
            }

            content.addView(etInput)
            editTexts.add(etInput)
        }

        // 按钮区域
        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "保存"
            setOnClickListener {
                val values = editTexts.map { it.text.toString() }
                onSave(values)
                refreshAllSubtitles()
                ToastUtil.showDropToast(this@ConfigModifyActivity, ToastStyle.SUCCESS, "$title 已保存")
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        // 恢复默认按钮
        if (onRestoreDefaults != null) {
            val btnRestore = CommonSettingsItemHelper.createRestoreDefaultsButton(
                this@ConfigModifyActivity
            ) {
                onRestoreDefaults()
                refreshAllSubtitles()
                ToastUtil.showDropToast(this@ConfigModifyActivity, ToastStyle.SUCCESS, "已恢复为默认配置")
                dialog.dismiss()
            }
            btnContainer.addView(btnRestore)
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()

        // 自动聚焦首个输入框
        editTexts.firstOrNull()?.postDelayed({
            editTexts.first().requestFocus()
            editTexts.first().setSelection(editTexts.first().text.length)
        }, 150)
    }

    /** 配置变更后的统一处理 */
    private fun onConfigChanged() {
        WifiWorker.resetFailureState(this)
        triggerProtocolProbe()
        BaseWifiWidget.renderAllWidgets(this)
    }


    // ==================== 协议探测 ====================

    private fun triggerProtocolProbe() {
        if (!SPUtil.needsProtocolProbe(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = WifiCrawl.probeProtocol(this@ConfigModifyActivity)
            if (result != null) {
                SPUtil.setDeviceProtocol(this@ConfigModifyActivity, result)
                android.util.Log.d("ConfigModify", "Protocol auto-detected: $result")
            }
        }
    }

    // ==================== 工具方法 ====================

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
