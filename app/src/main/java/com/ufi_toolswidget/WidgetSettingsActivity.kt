package com.ufi_toolswidget

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.checkbox.MaterialCheckBox
import android.content.BroadcastReceiver
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.CommonSettingsItemHelper
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import java.util.concurrent.TimeUnit

class WidgetSettingsActivity : AppCompatActivity() {

    /** 后台刷新间隔预设选项（分钟），0=关闭 */
    private val presetWidgetIntervals = listOf(15, 30, 60, 120, 0)
    private var widgetIntervalMinutes: Int = 15

    // 小组件主题
    private var widgetTheme: String = "follow_app"
    private var widgetColorThemeIndex: Int = 0

    // 显示信息开关状态
    private var showTemp = true
    private var showModel = true
    private var showSignal = true
    private var showBattery = true
    private var showCpu = true
    private var showMem = true
    private var showTime = true

    // 活跃弹窗引用
    private var activeWidgetThemeDialog: Dialog? = null
    private var activeDisplayInfoDialog: Dialog? = null
    private var activeWidgetIntervalDialog: Dialog? = null
    private var activeBgImageDialog: Dialog? = null
    private var activeBgOpacityDialog: Dialog? = null
    private var activeWidgetColorDialog: Dialog? = null

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    // 小组件背景
    private var widgetBgImageUri: String = ""
    private var widgetBgOpacity: Int = 100
    /** 图片选择器（为小组件背景选图） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handlePickedWidgetBgImage(uri)
        }
    }

    /** 图片裁切启动器 */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val croppedUriStr = result.data?.getStringExtra("cropped_uri")
            if (!croppedUriStr.isNullOrBlank()) {
                applyWidgetBgImage(Uri.parse(croppedUriStr))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.WIDGET_SETTINGS)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@WidgetSettingsActivity, ThemeUtil.PageType.WIDGET_SETTINGS)
            updateWidgetThemeSubtitle()
            updateDisplayInfoSubtitle()
            updateWidgetIntervalSubtitle()
            updateWidgetBgImageSubtitle()
            updateWidgetBgOpacitySubtitle()
        }
        setContentView(R.layout.activity_widget_settings)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initFollowAppThemeItem()
        initWidgetThemeItem()
        initWidgetColorThemeItem()
        initDisplayInfoItem()
        initWidgetIntervalItem()
        initWidgetBgImageItem()
        initWidgetBgOpacityItem()
        initWidgetClipToOutlineItem()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.WIDGET_SETTINGS)
        
        val isFollow = SPUtil.getWidgetFollowAppTheme(this)
        updateFollowAppThemeSubtitle()
        updateWidgetThemeItemState(isFollow)
        
        updateWidgetThemeSubtitle()
        updateWidgetColorThemeSubtitle()
        updateDisplayInfoSubtitle()
        updateWidgetIntervalSubtitle()
        updateWidgetBgImageSubtitle()
        updateWidgetBgOpacitySubtitle()
        updateWidgetClipToOutlineSwitch()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 0. 跟随应用主题（开关） ====================
    private fun initFollowAppThemeItem() {
        val isFollow = SPUtil.getWidgetFollowAppTheme(this)
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_widget_follow_theme),
            iconRes = R.drawable.ic_sun_moon,
            label = "跟随应用主题",
            initialChecked = isFollow
        ) { isChecked ->
            SPUtil.setWidgetFollowAppTheme(this, isChecked)
            updateFollowAppThemeSubtitle()
            BaseWifiWidget.renderAllWidgets(this)
            updateWidgetThemeItemState(isChecked, animate = true)
        }
        updateFollowAppThemeSubtitle()
        updateWidgetThemeItemState(isFollow)
    }

    private fun updateFollowAppThemeSubtitle() {
        // Switch layout doesn't usually have a subtitle in the common_switch layout, 
        // but we can add one if we want or just keep it simple.
    }

    private fun updateWidgetThemeItemState(isFollow: Boolean, animate: Boolean = false) {
        val themeItem = findViewById<View>(R.id.item_widget_theme)
        val colorItem = findViewById<View>(R.id.item_widget_color_theme)
        
        val targetVisibility = if (isFollow) View.GONE else View.VISIBLE
        
        applyVisibility(themeItem, targetVisibility, animate)
        applyVisibility(colorItem, targetVisibility, animate)
    }

    /** 设置 View 可见性：animate=true 时带渐入/渐出+位移效果，否则直接生效 */
    private fun applyVisibility(view: View, targetVisibility: Int, animate: Boolean) {
        if (view.visibility == targetVisibility) return

        if (animate) {
            // 取消已有动画，避免动画冲突
            view.animate().cancel()
            val slideDistance = (12f * resources.displayMetrics.density)

            if (targetVisibility == View.VISIBLE) {
                // 渐入：从下方微微上浮 + 透明变不透明
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationY = slideDistance
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .setListener(null)
                    .withLayer()
            } else {
                // 渐出：向下沉 + 不透明变透明
                view.animate()
                    .alpha(0f)
                    .translationY(slideDistance)
                    .setDuration(350)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
                    .withLayer()
                    .setListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            view.visibility = View.GONE
                            view.translationY = 0f // 复位避免影响下次显示
                        }
                    })
            }
        } else {
            // 非动画：直接设置最终状态
            view.animate().cancel()
            view.visibility = targetVisibility
            view.alpha = if (targetVisibility == View.VISIBLE) 1f else 0f
            view.translationY = 0f
        }
    }

    // ==================== 1. 小组件主题（弹窗选择） ====================
    private fun initWidgetThemeItem() {
        widgetTheme = SPUtil.getWidgetTheme(this)

        try {
            findInItem<ImageView>(R.id.item_widget_theme, R.id.common_item_icon)?.setImageResource(getWidgetThemeIcon())
            findInItem<TextView>(R.id.item_widget_theme, R.id.common_item_title)?.text = "小组件主题"
        } catch (_: Exception) {}
        updateWidgetThemeSubtitle()

        findViewById<View>(R.id.item_widget_theme).setOnClickListener {
            showWidgetThemeDialog()
        }
    }

    private fun getWidgetThemeIcon(): Int = when (widgetTheme) {
        "light" -> R.drawable.ic_sun
        "dark" -> R.drawable.ic_moon
        else -> R.drawable.ic_sun_moon
    }

    private fun updateWidgetThemeSubtitle() {
        val modeName = when (widgetTheme) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "浅色" // 默认为浅色，如果主开关关闭
        }
        try {
            findInItem<TextView>(R.id.item_widget_theme, R.id.common_item_subtitle)?.text = modeName
            findInItem<ImageView>(R.id.item_widget_theme, R.id.common_item_icon)?.setImageResource(getWidgetThemeIcon())
        } catch (_: Exception) {}
    }

    private fun showWidgetThemeDialog() {
        activeWidgetThemeDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetThemeDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件主题"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_sun_moon)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val options = listOf(
            "light" to "浅色",
            "dark" to "深色"
        )
        options.forEach { (key, label) ->
            val isSelected = key == widgetTheme
            content.addView(buildDialogOptionView(label, textPrimary,
                selectedBg, unselectedBg) {
                widgetTheme = key
                SPUtil.setWidgetTheme(this, key)
                updateWidgetThemeSubtitle()
                BaseWifiWidget.renderAllWidgets(this)
                dialog.dismiss()
            }.apply {
                if (isSelected) {
                    background = selectedBg
                    setTextColor(0xFFFFFFFF.toInt())
                }
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetThemeDialog = dialog
        dialog.show()
    }

    // ==================== 1.1 小组件颜色主题（弹窗选择） ====================
    private fun initWidgetColorThemeItem() {
        widgetColorThemeIndex = SPUtil.getWidgetColorThemeIndex(this)
        try {
            findInItem<ImageView>(R.id.item_widget_color_theme, R.id.common_item_icon)?.setImageResource(R.drawable.ic_palette)
            findInItem<TextView>(R.id.item_widget_color_theme, R.id.common_item_title)?.text = "小组件配色"
        } catch (_: Exception) {}
        updateWidgetColorThemeSubtitle()

        findViewById<View>(R.id.item_widget_color_theme).setOnClickListener {
            showWidgetColorThemeDialog()
        }
    }

    private fun updateWidgetColorThemeSubtitle() {
        val palette = ThemeColors.getById(this, widgetColorThemeIndex, isWidget = true)
        try {
            findInItem<TextView>(R.id.item_widget_color_theme, R.id.common_item_subtitle)?.text = palette.name
        } catch (_: Exception) {}
    }

    private fun showWidgetColorThemeDialog() {
        activeWidgetColorDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetColorDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件配色"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_palette)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val chipRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, chipRadius)
        val unselectedBg = makeUnselectedBg(chipRadius)

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildWidgetColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, cardBg, selectedBg, unselectedBg, content, dialog))
        }
        
        // 自定义选项
        val customAccent = SPUtil.getWidgetCustomAccentLight(this)
        grid.addView(buildWidgetColorOption(-1, "自定义", customAccent,
            textPrimary, cardBg, selectedBg, unselectedBg, content, dialog))

        // 自定义面板
        val customPanel = createCustomWidgetColorPanel(dialog, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (widgetColorThemeIndex == -1) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetColorDialog = dialog
        dialog.show()
    }

    private fun buildWidgetColorOption(
        index: Int, name: String, dotColor: Int,
        textPrimary: Int, cardBg: Int,
        selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog
    ): View {
        val isSelected = index == widgetColorThemeIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
            
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
            }
            layoutParams = params
            
            background = if (isSelected) selectedBg else unselectedBg
            isClickable = true
            isFocusable = true
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(10), dp2px(10))
            background = makeDot(dotColor, if (isSelected) 0xFFFFFFFF.toInt() else dotColor)
        }
        row.addView(dot)

        val label = TextView(this).apply {
            text = name
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp2px(10)
            }
        }
        row.addView(label)

        row.setOnClickListener {
            if (index == -1) {
                val panel = content.findViewWithTag<View>("custom_widget_color_panel")
                panel?.visibility = if (panel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            } else {
                widgetColorThemeIndex = index
                SPUtil.setWidgetColorThemeIndex(this, index)
                updateWidgetColorThemeSubtitle()
                BaseWifiWidget.renderAllWidgets(this)
                dialog.dismiss()
            }
        }
        return row
    }


    private fun createCustomWidgetColorPanel(dialog: Dialog, textPrimary: Int, accent: Int, cardBg: Int): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_widget_color_panel"
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(12)
            }
        }

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp2px(12) }
            setBackgroundColor(textPrimary)
            alpha = 0.12f
        })

        panel.addView(TextView(this).apply {
            text = "自定义小组件强调色"
            setTextColor(textPrimary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(10)
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(40), dp2px(40))
            background = makeDot(SPUtil.getWidgetCustomAccentLight(this@WidgetSettingsActivity), 0)
        }
        inputRow.addView(swatch)

        val tvStatusTip = TextView(this).apply {
            text = "支持十六进制格式 (如 #7B61FF)"
            setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 11f
            alpha = 0.8f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(8)
            }
        }

        val etColor = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp2px(40), 1f).apply { marginStart = dp2px(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(cardBg); cornerRadius = 8f * resources.displayMetrics.density
                setStroke(1, if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) 0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            hint = "#7B61FF"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 13f
            setPadding(dp2px(12), 0, dp2px(12), 0)
            val currentCustomColor = SPUtil.getWidgetCustomAccentLight(this@WidgetSettingsActivity)
            setText(String.format("#%06X", 0xFFFFFF and currentCustomColor))

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val input = s?.toString()?.trim() ?: ""
                    if (input.isEmpty()) {
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
                        return
                    }
                    val formatted = if (input.startsWith("#")) input else "#$input"
                    try {
                        val color = android.graphics.Color.parseColor(formatted)
                        swatch.background = makeDot(color, 0)
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
                    } catch (_: Exception) {
                        tvStatusTip.text = "无效的颜色代码"
                        tvStatusTip.setTextColor(0xFFE53935.toInt())
                    }
                }
            })
        }
        inputRow.addView(etColor)

        val btnApply = TextView(this).apply {
            text = "确定"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(16), 0, dp2px(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp2px(40)).apply { marginStart = dp2px(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(accent); cornerRadius = 20f * resources.displayMetrics.density
            }
            setOnClickListener {
                val input = etColor.text.toString().trim()
                val formatted = if (input.startsWith("#")) input else "#$input"
                val color = try { android.graphics.Color.parseColor(formatted) } catch (_: Exception) { null }
                if (color != null) {
                    val darkColor = adjustBrightness(color, 0.85f)
                    SPUtil.setWidgetCustomAccentLight(this@WidgetSettingsActivity, color)
                    SPUtil.setWidgetCustomAccentDark(this@WidgetSettingsActivity, darkColor)
                    widgetColorThemeIndex = -1
                    SPUtil.setWidgetColorThemeIndex(this@WidgetSettingsActivity, -1)
                    updateWidgetColorThemeSubtitle()
                    BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity)
                    dialog.dismiss()
                    Toast.makeText(this@WidgetSettingsActivity, "自定义配色已应用", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@WidgetSettingsActivity, "颜色格式无效", Toast.LENGTH_SHORT).show()
                }
            }
        }
        inputRow.addView(btnApply)
        panel.addView(inputRow)
        panel.addView(tvStatusTip)

        return panel
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= factor
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun makeDot(color: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp2px(1), stroke)
        }
    }

    // ==================== 2. 显示信息（弹窗多选） ====================
    private fun initDisplayInfoItem() {
        showTemp = SPUtil.getShowTemp(this)
        showModel = SPUtil.getShowModel(this)
        showSignal = SPUtil.getShowSignal(this)
        showBattery = SPUtil.getShowBattery(this)
        showCpu = SPUtil.getShowCpu(this)
        showMem = SPUtil.getShowMem(this)
        showTime = SPUtil.getShowTime(this)

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_display_info),
            iconRes = R.drawable.ic_eye,
            title = "显示信息",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDisplayInfoDialog
        )
        updateDisplayInfoSubtitle()
    }

    private fun updateDisplayInfoSubtitle() {
        val enabled = listOf(showTemp, showModel, showSignal, showBattery, showCpu, showMem, showTime).count { it }
        val total = 7
        val label = if (enabled == 0) "全部关闭" else "已开启 $enabled/$total 项"
        try {
            findInItem<TextView>(R.id.item_display_info, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showDisplayInfoDialog() {
        activeDisplayInfoDialog?.takeIf { it.isShowing }?.dismiss()
        activeDisplayInfoDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "显示信息"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_eye)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 数据项定义
        val items = listOf(
            "temp" to "硬件温度",
            "model" to "设备名称",
            "signal" to "信号详情",
            "battery" to "电池状态",
            "cpu" to "CPU 占用",
            "mem" to "内存占用",
            "time" to "更新时间"
        )

        // 临时状态存储
        val tempStates = mutableMapOf(
            "temp" to showTemp,
            "model" to showModel,
            "signal" to showSignal,
            "battery" to showBattery,
            "cpu" to showCpu,
            "mem" to showMem,
            "time" to showTime
        )

        // 使用双栏网格布局
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(grid)

        items.forEach { (key, label) ->
            val switchWrapper = layoutInflater.inflate(R.layout.layout_common_switch, grid, false)
            
            // 调整网格项布局参数
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
            }
            switchWrapper.layoutParams = params
            
            // 设置文字并缩小一点以适应双栏
            switchWrapper.findViewById<TextView>(R.id.common_switch_label).apply {
                text = label
                textSize = 12f
            }
            
            ThemeUtil.setupSwitch(switchWrapper, tempStates[key]!!) { isChecked ->
                tempStates[key] = isChecked
            }
            grid.addView(switchWrapper)
        }

        // 对 content 内动态添加的视图递归着色（applyThemeToViewTree 会跳过 common_dialog_content 容器）
        CommonDialogHelper.applyThemeToViewTree(grid, this)

        // 按钮区域
        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                showTemp = tempStates["temp"]!!
                showModel = tempStates["model"]!!
                showSignal = tempStates["signal"]!!
                showBattery = tempStates["battery"]!!
                showCpu = tempStates["cpu"]!!
                showMem = tempStates["mem"]!!
                showTime = tempStates["time"]!!

                SPUtil.setShowTemp(this@WidgetSettingsActivity, showTemp)
                SPUtil.setShowModel(this@WidgetSettingsActivity, showModel)
                SPUtil.setShowSignal(this@WidgetSettingsActivity, showSignal)
                SPUtil.setShowBattery(this@WidgetSettingsActivity, showBattery)
                SPUtil.setShowCpu(this@WidgetSettingsActivity, showCpu)
                SPUtil.setShowMem(this@WidgetSettingsActivity, showMem)
                SPUtil.setShowTime(this@WidgetSettingsActivity, showTime)

                updateDisplayInfoSubtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity)
                dialog.dismiss()
            }
        }

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDisplayInfoDialog = dialog
        dialog.show()
    }

    // ==================== 3. 后台刷新频率（弹窗选择） ====================
    private fun initWidgetIntervalItem() {
        widgetIntervalMinutes = SPUtil.getRefreshInterval(this)

        try {
            findInItem<ImageView>(R.id.item_widget_interval, R.id.common_item_icon)?.setImageResource(R.drawable.ic_clock_bolt)
            findInItem<TextView>(R.id.item_widget_interval, R.id.common_item_title)?.text = "后台刷新频率"
        } catch (_: Exception) {}
        updateWidgetIntervalSubtitle()

        findViewById<View>(R.id.item_widget_interval).setOnClickListener {
            showWidgetIntervalDialog()
        }
    }

    private fun updateWidgetIntervalSubtitle() {
        val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)
        val label = when {
            widgetIntervalMinutes == 0 -> "关闭"
            isPreset -> "${widgetIntervalMinutes} 分钟"
            else -> "${widgetIntervalMinutes} 分钟（自定义）"
        }
        try {
            findInItem<TextView>(R.id.item_widget_interval, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showWidgetIntervalDialog() {
        activeWidgetIntervalDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetIntervalDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "后台刷新频率"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_clock_bolt)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)

        // 双栏 GridLayout
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        // 预设选项
        val options = listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时", 120 to "2 小时", 0 to "关闭")
        options.forEach { (mins, label) ->
            grid.addView(buildWidgetIntervalOption(mins, label,
                isPreset && mins == widgetIntervalMinutes,
                textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }

        // 自定义选项
        grid.addView(buildWidgetIntervalOption(-1,
            if (!isPreset && widgetIntervalMinutes > 0) "${widgetIntervalMinutes}分钟" else "自定义...",
            !isPreset && widgetIntervalMinutes > 0,
            textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))

        // 自定义输入面板（放在网格下方）
        val customPanel = createCustomWidgetIntervalPanel(dialog, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (!isPreset && widgetIntervalMinutes > 0) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetIntervalDialog = dialog
        dialog.show()
    }

    private fun buildWidgetIntervalOption(
        mins: Int, label: String, isSelected: Boolean,
        textPrimary: Int, selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog,
        isGrid: Boolean = false
    ): View {
        val option = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp2px(14), 0, dp2px(14))
            if (isGrid) {
                val params = android.widget.GridLayout.LayoutParams()
                params.width = 0
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                params.setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                layoutParams = params
            } else {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(8) }
            }
            background = if (isSelected) selectedBg else unselectedBg
            setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            isClickable = true
            isFocusable = true
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
        }

        option.setOnClickListener {
            if (mins == -1) {
                val panel = content.findViewWithTag<View>("custom_widget_interval_panel")
                panel?.visibility = if (panel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (panel?.visibility == View.VISIBLE) {
                    val et = panel.findViewWithTag<EditText>("custom_widget_interval_field")
                    val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)
                    if (!isPreset && widgetIntervalMinutes > 0) et.setText(widgetIntervalMinutes.toString())
                    et.requestFocus()
                }
            } else {
                widgetIntervalMinutes = mins
                SPUtil.setRefreshInterval(this, mins)
                updateWidgetIntervalSubtitle()
                updateWidgetWorker()
                refreshWidgetIntervalDialogOptions(content, dialog, textPrimary)
                val labelRes = if (mins > 0) "${mins}分钟" else "关闭"
                Toast.makeText(this, "后台刷新间隔已设为 $labelRes", Toast.LENGTH_SHORT).show()
                dismissDialogWithAnimation(dialog)
            }
        }
        return option
    }

    private fun refreshWidgetIntervalDialogOptions(
        content: LinearLayout,
        dialog: Dialog,
        textPrimary: Int
    ) {
        val accent = ThemeColors.accent(this)
        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        content.removeAllViews()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)
        val options = listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时", 120 to "2 小时", 0 to "关闭")
        val isPreset = presetWidgetIntervals.contains(widgetIntervalMinutes)

        options.forEach { (mins, label) ->
            grid.addView(buildWidgetIntervalOption(mins, label,
                isPreset && mins == widgetIntervalMinutes,
                textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }
        grid.addView(buildWidgetIntervalOption(-1,
            if (!isPreset && widgetIntervalMinutes > 0) "${widgetIntervalMinutes}分钟" else "自定义...",
            !isPreset && widgetIntervalMinutes > 0,
            textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))

        val customPanel = createCustomWidgetIntervalPanel(dialog, textPrimary, accent, ThemeColors.cardBg(this))
        content.addView(customPanel)
        if (!isPreset && widgetIntervalMinutes > 0) customPanel.visibility = View.VISIBLE
    }

    private fun createCustomWidgetIntervalPanel(
        dialog: Dialog,
        textPrimary: Int,
        accent: Int,
        cardBg: Int
    ): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_widget_interval_panel"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(8) }
        }

        val et = EditText(this).apply {
            tag = "custom_widget_interval_field"
            layoutParams = LinearLayout.LayoutParams(0, dp2px(40), 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke(1, if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) 0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            hint = "输入 1-1440 分钟"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 13f
            setPadding(dp2px(12), 0, dp2px(12), 0)
        }
        panel.addView(et)

        val btnConfirm = TextView(this).apply {
            text = "确定"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(14), 0, dp2px(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp2px(40)
            ).apply { marginStart = dp2px(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accent)
                cornerRadius = 20f * resources.displayMetrics.density
            }
            setOnClickListener {
                val mins = et.text.toString().toIntOrNull()
                if (mins != null && mins in 1..1440) {
                    widgetIntervalMinutes = mins
                    SPUtil.setRefreshInterval(this@WidgetSettingsActivity, mins)
                    updateWidgetIntervalSubtitle()
                    updateWidgetWorker()
                    dismissDialogWithAnimation(dialog)
                    Toast.makeText(this@WidgetSettingsActivity, "自定义间隔已设为 ${mins}分钟", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@WidgetSettingsActivity, "请输入 1-1440 之间的分钟数", Toast.LENGTH_SHORT).show()
                }
            }
        }
        panel.addView(btnConfirm)

        panel.addView(TextView(this).apply {
            text = "取消"
            textSize = 13f
            setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            alpha = 0.5f
            setPadding(dp2px(8), 0, dp2px(4), 0)
            setOnClickListener {
                panel.visibility = View.GONE
                val content = panel.parent as? LinearLayout ?: return@setOnClickListener
                refreshWidgetIntervalDialogOptions(content, dialog, textPrimary)
            }
        })

        return panel
    }

    // ==================== 4. 自定义背景图（弹窗选择） ====================
    private fun initWidgetBgImageItem() {
        widgetBgImageUri = SPUtil.getWidgetBgImageUri(this)

        try {
            findInItem<ImageView>(R.id.item_widget_bg_image, R.id.common_item_icon)?.setImageResource(R.drawable.ic_photo)
            findInItem<TextView>(R.id.item_widget_bg_image, R.id.common_item_title)?.text = "小组件背景"
        } catch (_: Exception) {}
        updateWidgetBgImageSubtitle()

        findViewById<View>(R.id.item_widget_bg_image).setOnClickListener {
            showWidgetBgImageDialog()
        }
    }

    private fun updateWidgetBgImageSubtitle() {
        val label = if (widgetBgImageUri.isNotBlank()) "已设置" else "未设置"
        try {
            findInItem<TextView>(R.id.item_widget_bg_image, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    /**
     * 处理用户选择的图片：检测尺寸是否匹配小组件4x2比例，
     * 若不匹配则自动打开裁切界面让用户调整。
     */
    private fun handlePickedWidgetBgImage(uri: Uri) {
        // 获取持久化 URI 权限
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) {}

        // 小组件4x2尺寸比例: width/height ≈ 250/110 ≈ 2.2727
        // 像素级别用 dp 换算为实际像素做比例对比
        val density = resources.displayMetrics.density
        val widgetW = (250f * density).toInt()
        val widgetH = (110f * density).toInt()
        val widgetRatio = widgetW.toFloat() / widgetH.toFloat()

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                val imgW = options.outWidth
                val imgH = options.outHeight
                if (imgW <= 0 || imgH <= 0) {
                    // 无法获取尺寸，直接应用
                    applyWidgetBgImage(uri)
                    return
                }
                val imgRatio = imgW.toFloat() / imgH.toFloat()

                // 比例差异超过 3% 或图片尺寸小于小组件推荐尺寸 → 进入裁切
                if (Math.abs(imgRatio - widgetRatio) > 0.03f || imgW < widgetW || imgH < widgetH) {
                    val intent = Intent(this, ImageCropActivity::class.java).apply {
                        data = uri
                        putExtra("targetW", widgetW)
                        putExtra("targetH", widgetH)
                    }
                    cropLauncher.launch(intent)
                } else {
                    applyWidgetBgImage(uri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            applyWidgetBgImage(uri) // 解码失败则直接应用
        }
    }

    /** 直接应用（或裁切完成后）小组件背景图片 */
    private fun applyWidgetBgImage(uri: Uri) {
        SPUtil.setWidgetBgImageUri(this, uri.toString())
        widgetBgImageUri = uri.toString()
        updateWidgetBgImageSubtitle()
        BaseWifiWidget.renderAllWidgets(this)
        Toast.makeText(this, "小组件背景图片已更新", Toast.LENGTH_SHORT).show()
    }

    private fun showWidgetBgImageDialog() {
        activeBgImageDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgImageDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件背景"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_photo)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        // 选项1：选择图片
        content.addView(buildDialogOptionView("从相册选择图片", textPrimary,
            selectedBg, unselectedBg) {
            pickImageLauncher.launch("image/*")
            dialog.dismiss()
        })

        // 选项2：清除背景（仅在有自定义背景时显示）
        if (widgetBgImageUri.isNotBlank()) {
            content.addView(buildDialogOptionView("清除背景", textPrimary,
                selectedBg, unselectedBg) {
                SPUtil.clearWidgetBgImageUri(this)
                widgetBgImageUri = ""
                updateWidgetBgImageSubtitle()
                BaseWifiWidget.renderAllWidgets(this)
                Toast.makeText(this, "小组件背景已清除", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeBgImageDialog = dialog
        dialog.show()
    }

    // ==================== 5. 背景透明度（弹窗选择） ====================
    private fun initWidgetBgOpacityItem() {
        widgetBgOpacity = SPUtil.getWidgetBgOpacity(this)

        try {
            findInItem<ImageView>(R.id.item_widget_bg_opacity, R.id.common_item_icon)?.setImageResource(R.drawable.ic_opacity)
            findInItem<TextView>(R.id.item_widget_bg_opacity, R.id.common_item_title)?.text = "背景透明度"
        } catch (_: Exception) {}
        updateWidgetBgOpacitySubtitle()

        findViewById<View>(R.id.item_widget_bg_opacity).setOnClickListener {
            showWidgetBgOpacityDialog()
        }
    }

    private fun updateWidgetBgOpacitySubtitle() {
        val label = "${widgetBgOpacity}%"
        try {
            findInItem<TextView>(R.id.item_widget_bg_opacity, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showWidgetBgOpacityDialog() {
        activeBgOpacityDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgOpacityDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "背景透明度"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_opacity)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val presets = listOf(100, 80, 60, 40, 20)
        val isPreset = presets.contains(widgetBgOpacity)

        // 双栏 GridLayout
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        presets.forEach { pct ->
            val label = if (pct == 100) "100%（不透明）" else "${pct}%"
            grid.addView(buildOpacityOption(pct, label, isPreset && pct == widgetBgOpacity,
                textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }

        // 自定义选项
        grid.addView(buildOpacityOption(-1,
            if (!isPreset) "${widgetBgOpacity}%（自定义）" else "自定义...",
            !isPreset, textPrimary, selectedBg, unselectedBg, content, dialog, isGrid = true))

        // 自定义面板（放在网格下方）
        val customPanel = createCustomOpacityPanel(dialog, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (!isPreset) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeBgOpacityDialog = dialog
        dialog.show()
    }

    private fun buildOpacityOption(
        pct: Int, label: String, isSelected: Boolean,
        textPrimary: Int, selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog,
        isGrid: Boolean = false
    ): View {
        return TextView(this).apply {
            text = label
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp2px(14), 0, dp2px(14))
            if (isGrid) {
                val params = android.widget.GridLayout.LayoutParams()
                params.width = 0
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                params.setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                layoutParams = params
            } else {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(8) }
            }
            background = if (isSelected) selectedBg else unselectedBg
            setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (pct == -1) {
                    val panel = content.findViewWithTag<View>("custom_opacity_panel")
                    panel?.visibility = if (panel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (panel?.visibility == View.VISIBLE) {
                        panel.findViewWithTag<EditText>("custom_opacity_field")?.requestFocus()
                    }
                } else {
                    widgetBgOpacity = pct
                    SPUtil.setWidgetBgOpacity(this@WidgetSettingsActivity, pct)
                    updateWidgetBgOpacitySubtitle()
                    BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity)
                    dismissDialogWithAnimation(dialog)
                }
            }
        }
    }

    private fun createCustomOpacityPanel(dialog: Dialog, textPrimary: Int, accent: Int, cardBg: Int): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_opacity_panel"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(8) }
        }

        val et = EditText(this).apply {
            tag = "custom_opacity_field"
            layoutParams = LinearLayout.LayoutParams(0, dp2px(40), 1f)
            background = makeUnselectedBg(8f * resources.displayMetrics.density)
            gravity = android.view.Gravity.CENTER
            hint = "输入 0-100"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 13f
            if (widgetBgOpacity in 0..100 && !listOf(100, 80, 60, 40, 20).contains(widgetBgOpacity)) {
                setText(widgetBgOpacity.toString())
            }
        }
        panel.addView(et)

        val btnConfirm = TextView(this).apply {
            text = "确定"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(14), 0, dp2px(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp2px(40)).apply { marginStart = dp2px(8) }
            background = makeSelectedBg(accent, 20f * resources.displayMetrics.density)
            setOnClickListener {
                val valStr = et.text.toString().trim()
                val value = valStr.toIntOrNull()
                if (value != null && value in 0..100) {
                    widgetBgOpacity = value
                    SPUtil.setWidgetBgOpacity(this@WidgetSettingsActivity, value)
                    updateWidgetBgOpacitySubtitle()
                    BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity)
                    dismissDialogWithAnimation(dialog)
                } else {
                    Toast.makeText(this@WidgetSettingsActivity, "请输入 0-100 之间的数字", Toast.LENGTH_SHORT).show()
                }
            }
        }
        panel.addView(btnConfirm)
        return panel
    }

    // ==================== 6. 圆角裁剪兜底（开关） ====================
    private fun initWidgetClipToOutlineItem() {
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_widget_clip_to_outline),
            iconRes = R.drawable.ic_rounded_corners,
            label = "兼容性小组件圆角",
            subtitle = "如果桌面小组件没有圆角效果，可开启此项强制圆角",
            initialChecked = SPUtil.getWidgetClipToOutline(this),
            onToggle = { checked ->
                SPUtil.setWidgetClipToOutline(this, checked)
                BaseWifiWidget.renderAllWidgets(this)
            }
        )
    }

    private fun updateWidgetClipToOutlineSwitch() {
        // 开关 UI 状态由 ThemeUtil.setupSwitch 在 onResume 重新初始化时同步
    }

    // ==================== Worker 更新 ====================
    private fun updateWidgetWorker() {
        if (widgetIntervalMinutes <= 0) {
            WorkManager.getInstance(this).cancelUniqueWork("wifi_crawl")
        } else {
            val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(
                widgetIntervalMinutes.toLong(), TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "wifi_crawl",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    // ==================== 工具方法 ====================

    /** 从 include 项中查找子 View */
    private fun <T : View> findInItem(itemId: Int, childId: Int): T? {
        return findViewById<View>(itemId)?.findViewById(childId)
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 带动画退场关闭弹窗：先执行模糊退场动画(260ms)，再关闭弹窗并执行回调 */
    private fun dismissDialogWithAnimation(dialog: Dialog, onComplete: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurOut(dialog) {
                try { dialog.dismiss() } catch (_: Exception) {}
                onComplete()
            }
        } else {
            dialog.dismiss()
            onComplete()
        }
    }

    private fun makeSelectedBg(accent: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            this.cornerRadius = cornerRadius
        }
    }

    private fun makeUnselectedBg(cornerRadius: Float): GradientDrawable {
        val cardBg = ThemeColors.cardBg(this)
        val borderColor = if (SPUtil.getNightMode(this) == AppCompatDelegate.MODE_NIGHT_YES)
            0x30FFFFFF.toInt() else 0x20000000
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            this.cornerRadius = cornerRadius
            setStroke(1, borderColor)
        }
    }

    /**
     * 创建统一风格的弹窗选项视图
     */
    private fun buildDialogOptionView(
        label: String,
        textPrimary: Int,
        selectedBg: GradientDrawable,
        unselectedBg: GradientDrawable,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp2px(14), 0, dp2px(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
            background = unselectedBg
            setTextColor(textPrimary)
            isClickable = true
            isFocusable = true
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
            setOnClickListener {
                background = selectedBg
                setTextColor(0xFFFFFFFF.toInt())
                postDelayed({ onClick() }, 120)
            }
        }
    }
}
