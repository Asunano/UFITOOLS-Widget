package com.ufi_toolswidget

import android.app.Dialog
import android.content.BroadcastReceiver
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.AnimationUtil
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
        initSettingItem(R.id.item_basic_config, R.drawable.ic_router, "基础连接",
            showSubtitle = false,
            onClick = ::showBasicConfigDialog)

        // 高级配置：无副标题，点击先弹出警告确认
        initSettingItem(R.id.item_advanced_config, R.drawable.ic_chip, "高级配置",
            showSubtitle = false,
            onClick = ::showAdvancedConfigDialog)
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

    // ==================== 高级配置（警告确认 → 编辑弹窗） ====================

    private fun showAdvancedConfigDialog() {
        com.ufi_toolswidget.util.PopupViewUtil.showConfirmDialog(
            this,
            title = "警告",
            message = "正常情况切勿修改高级配置\n错误配置将导致设备功能异常\n\n确认要继续修改吗？",
            isWarning = true,
            primaryBtnText = "继续修改",
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

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(icon)

        com.ufi_toolswidget.util.PopupViewUtil.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val cornerRadius = 10f * resources.displayMetrics.density
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
            val etInput = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setHint(field.hint)
                setText(field.currentValue)
                this.inputType = field.inputType
                maxLines = 1
                textSize = 14f
                setTextColor(textPrimary)
                setHintTextColor(ThemeColors.textSecondary(this@ConfigModifyActivity))
                setPadding(dp2px(14), dp2px(14), dp2px(14), dp2px(14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(cardBg)
                    this.cornerRadius = cornerRadius
                    setStroke(1, if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES)
                        0x30FFFFFF.toInt() else 0x20000000)
                }

                // 针对平台选择特殊处理：禁止手动输入，点击弹出选择列表
                if (field.label.contains("设备平台")) {
                    isFocusable = false
                    isClickable = true
                    isCursorVisible = false
                    setOnClickListener {
                        val options = arrayOf("auto (自动探测)", "spreadtrum (展讯)", "quectel (移远)")
                        val values = arrayOf("auto", "spreadtrum", "quectel")
                        val currentVal = this.text.toString().lowercase()
                        val currentIdx = values.indexOf(currentVal).coerceAtLeast(0)

                        com.ufi_toolswidget.util.PopupViewUtil.showDropDownMenu(
                            it,
                            options = options,
                            currentIndex = currentIdx,
                            onSelect = { which ->
                                this.setText(values[which])
                            }
                        )
                    }
                }
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
                Toast.makeText(this@ConfigModifyActivity, "$title 已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        // 恢复默认按钮（仅在高级配置等场景显示，使用与取消按钮一致的 Outlined 样式）
        if (onRestoreDefaults != null) {
            val secondaryColor = ThemeColors.textSecondary(this@ConfigModifyActivity)
            val textPrimary = ThemeColors.textPrimary(this@ConfigModifyActivity)

            val btnRestore = MaterialButton(this@ConfigModifyActivity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp2px(48)
                ).apply {
                    topMargin = dp2px(10)
                }
                text = "恢复默认"
                textSize = 14f
                insetTop = 0
                insetBottom = 0
                setTextColor(textPrimary)
                strokeColor = android.content.res.ColorStateList.valueOf(secondaryColor)
                strokeWidth = dp2px(1)
                @Suppress("DEPRECATION")
                setCornerRadius(dp2px(12))
                setOnClickListener {
                    onRestoreDefaults()
                    refreshAllSubtitles()
                    Toast.makeText(this@ConfigModifyActivity, "已恢复为默认配置", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            btnContainer.addView(btnRestore)
        }

        com.ufi_toolswidget.util.PopupViewUtil.setupDialogWindow(this, dialog)
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

    // ==================== 设置项绑定 ====================

    private fun initSettingItem(
        itemId: Int, iconRes: Int, title: String,
        showSubtitle: Boolean = true,
        subtitleProvider: (() -> String)? = null,
        onClick: () -> Unit
    ) {
        try {
            findInItem<ImageView>(itemId, R.id.common_item_icon)?.setImageResource(iconRes)
            findInItem<TextView>(itemId, R.id.common_item_title)?.text = title
            val subtitle = findInItem<TextView>(itemId, R.id.common_item_subtitle)
            if (showSubtitle && subtitleProvider != null) {
                subtitle?.visibility = View.VISIBLE
                subtitle?.text = subtitleProvider()
            } else {
                subtitle?.visibility = View.GONE
            }
        } catch (_: Exception) {}
        findViewById<View>(itemId).setOnClickListener { onClick() }
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

    private fun <T : View> findInItem(itemId: Int, childId: Int): T? {
        return findViewById<View>(itemId)?.findViewById(childId)
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
