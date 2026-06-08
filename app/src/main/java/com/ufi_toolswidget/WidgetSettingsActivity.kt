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
import com.ufi_toolswidget.util.ThemedSliderUtil
import com.ufi_toolswidget.view.ThemeSlider
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import java.util.concurrent.TimeUnit

class WidgetSettingsActivity : AppCompatActivity() {

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
            updateDisplayInfo2x1Subtitle()
            updateDisplayInfo4x1Subtitle()
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
        initDisplayInfo2x1Item()
        initDisplayInfo4x1Item()
        initWidgetIntervalItem()
        initWidgetBgImageItem()
        initWidgetBgOpacityItem()
        initWidgetCompatibilityItem()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.WIDGET_SETTINGS)
        
        updateFollowAppThemeSubtitle()
        
        // ── 动态配色锁定：开启动态配色后禁用主题相关设置 ──
        applyDynamicColorLockState()
        
        updateWidgetThemeSubtitle()
        updateWidgetColorThemeSubtitle()
        updateDisplayInfoSubtitle()
        updateDisplayInfo2x1Subtitle()
        updateDisplayInfo4x1Subtitle()
        updateWidgetIntervalSubtitle()
        updateWidgetBgImageSubtitle()
        updateWidgetBgOpacitySubtitle()
        updateCompatibilitySubtitle()
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
            if (isWidgetDynamicActive()) return@setupSwitchItem // 动态配色开启时禁止操作
            SPUtil.setWidgetFollowAppTheme(this, isChecked)
            updateFollowAppThemeSubtitle()
            BaseWifiWidget.renderAllWidgets(this, force = true)
            updateWidgetThemeItemState(isChecked, animate = true)
        }
        updateFollowAppThemeSubtitle()
        applyDynamicColorLockState()
    }

    private fun updateFollowAppThemeSubtitle() {
        // Switch layout doesn't usually have a subtitle in the common_switch layout, 
        // but we can add one if we want or just keep it simple.
    }

    /** 动态配色是否激活（API 31+ 且开关开启） */
    private fun isWidgetDynamicActive(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SPUtil.getWidgetDynamicColor(this)
    }

    /**
     * 当动态配色激活时：禁用"跟随应用主题"开关 + 隐藏"小组件主题"/"小组件配色"；
     * 当动态配色关闭时：恢复开关交互，根据跟随主题状态正常显示/隐藏子项。
     */
    private fun applyDynamicColorLockState() {
        val isDynamicActive = isWidgetDynamicActive()
        val followItem = findViewById<View>(R.id.item_widget_follow_theme)
        val track = followItem.findViewById<View>(R.id.common_switch_track)
        val subtitle = followItem.findViewById<android.widget.TextView>(R.id.common_switch_subtitle)

        if (isDynamicActive) {
            subtitle?.apply {
                text = "动态配色已开启，跟随主题由系统壁纸自动控制"
                visibility = View.VISIBLE
            }
            track?.isEnabled = false
            track?.alpha = 0.5f
            // 动态配色开启时，主题/配色完全由壁纸控制，隐藏用户设置项
            findViewById<View>(R.id.item_widget_theme).visibility = View.GONE
            findViewById<View>(R.id.item_widget_color_theme).visibility = View.GONE
        } else {
            subtitle?.visibility = View.GONE
            track?.isEnabled = true
            track?.alpha = 1f
            // 恢复正常的跟随逻辑
            val isFollow = SPUtil.getWidgetFollowAppTheme(this)
            updateWidgetThemeItemState(isFollow)
        }
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

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
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
                BaseWifiWidget.renderAllWidgets(this, force = true)
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

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
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
                BaseWifiWidget.renderAllWidgets(this, force = true)
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
                    BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
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

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
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

        // 各尺寸适用范围提示
        val tipView = TextView(this).apply {
            text = "提示：以上设置对 4×2 标准版小组件生效。"
            setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 11f
            alpha = 0.8f
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(12) }
        }
        content.addView(tipView)

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
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
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

    // ==================== 2.1  2×1 迷你版独立显示项+字体 ====================
    private fun initDisplayInfo2x1Item() {
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_display_info_2x1),
            iconRes = R.drawable.ic_widget_small,
            title = "2×1 迷你版设置",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDisplayInfo2x1Dialog
        )
        updateDisplayInfo2x1Subtitle()
    }

    private fun updateDisplayInfo2x1Subtitle() {
        val items = listOf(
            SPUtil.getShowSignal2x1(this),
            SPUtil.getShowBattery2x1(this),
            SPUtil.getShowNetwork2x1(this)
        )
        val enabled = items.count { it }
        val fontSize = SPUtil.getFontSize2x1(this)
        val label = "${enabled}/3 项 · 字体 ${fontSize}sp"
        try {
            findInItem<TextView>(R.id.item_display_info_2x1, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showDisplayInfo2x1Dialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "2×1 迷你版设置"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_widget_small)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 显隐设置
        val items = listOf(
            "signal" to "信号格数",
            "battery" to "电池状态",
            "network" to "网络类型"
        )
        val tempStates = mutableMapOf(
            "signal" to SPUtil.getShowSignal2x1(this),
            "battery" to SPUtil.getShowBattery2x1(this),
            "network" to SPUtil.getShowNetwork2x1(this)
        )

        items.forEach { (key, label) ->
            val switchWrapper = layoutInflater.inflate(R.layout.layout_common_switch, content, false)
            switchWrapper.findViewById<TextView>(R.id.common_switch_label).apply {
                text = label
                textSize = 13f
            }
            ThemeUtil.setupSwitch(switchWrapper, tempStates[key]!!) { isChecked ->
                tempStates[key] = isChecked
            }
            content.addView(switchWrapper)
        }

        // 字体大小
        val fontSizeLabel = TextView(this).apply {
            text = "字体大小: ${SPUtil.getFontSize2x1(this@WidgetSettingsActivity)}sp"
            textSize = 14f
            setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(16) }
        }
        content.addView(fontSizeLabel)

        var currentFontSize = SPUtil.getFontSize2x1(this).toFloat()
        val slider = com.ufi_toolswidget.view.ThemeSlider(this).apply {
            minValue = 6f; maxValue = 14f; stepSize = 1f
            currentValue = currentFontSize
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
            onValueChange = { v ->
                currentFontSize = v
                fontSizeLabel.text = "字体大小: ${v.toInt()}sp"
            }
        }
        content.addView(slider)

        CommonDialogHelper.applyThemeToViewTree(content, this)

        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE
        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                SPUtil.setShowSignal2x1(this@WidgetSettingsActivity, tempStates["signal"]!!)
                SPUtil.setShowBattery2x1(this@WidgetSettingsActivity, tempStates["battery"]!!)
                SPUtil.setShowNetwork2x1(this@WidgetSettingsActivity, tempStates["network"]!!)
                SPUtil.setFontSize2x1(this@WidgetSettingsActivity, currentFontSize.toInt())
                updateDisplayInfo2x1Subtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
                dialog.dismiss()
            }
        }
        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE; text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
    }

    // ==================== 2.2  4×1 条形版独立显示项+字体 ====================
    private fun initDisplayInfo4x1Item() {
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_display_info_4x1),
            iconRes = R.drawable.ic_widget_large,
            title = "4×1 条形版设置",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDisplayInfo4x1Dialog
        )
        updateDisplayInfo4x1Subtitle()
    }

    private fun updateDisplayInfo4x1Subtitle() {
        val items = listOf(
            SPUtil.getShowModel4x1(this),
            SPUtil.getShowSignal4x1(this),
            SPUtil.getShowBattery4x1(this),
            SPUtil.getShowTemp4x1(this),
            SPUtil.getShowTime4x1(this)
        )
        val enabled = items.count { it }
        val fontSize = SPUtil.getFontSize4x1(this)
        val label = "${enabled}/5 项 · 字体 ${fontSize}sp"
        try {
            findInItem<TextView>(R.id.item_display_info_4x1, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showDisplayInfo4x1Dialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "4×1 条形版设置"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_widget_large)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 显隐设置
        val items = listOf(
            "model" to "设备名称",
            "signal" to "信号详情",
            "battery" to "电池状态",
            "temp" to "硬件温度",
            "time" to "更新时间"
        )
        val tempStates = mutableMapOf(
            "model" to SPUtil.getShowModel4x1(this),
            "signal" to SPUtil.getShowSignal4x1(this),
            "battery" to SPUtil.getShowBattery4x1(this),
            "temp" to SPUtil.getShowTemp4x1(this),
            "time" to SPUtil.getShowTime4x1(this)
        )

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(grid)

        items.forEach { (key, label) ->
            val switchWrapper = layoutInflater.inflate(R.layout.layout_common_switch, grid, false)
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
            }
            switchWrapper.layoutParams = params
            switchWrapper.findViewById<TextView>(R.id.common_switch_label).apply {
                text = label; textSize = 12f
            }
            ThemeUtil.setupSwitch(switchWrapper, tempStates[key]!!) { isChecked ->
                tempStates[key] = isChecked
            }
            grid.addView(switchWrapper)
        }

        CommonDialogHelper.applyThemeToViewTree(grid, this)

        // 字体大小
        val fontSizeLabel = TextView(this).apply {
            text = "字体大小: ${SPUtil.getFontSize4x1(this@WidgetSettingsActivity)}sp"
            textSize = 14f
            setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(16) }
        }
        content.addView(fontSizeLabel)

        var currentFontSize = SPUtil.getFontSize4x1(this).toFloat()
        val slider = com.ufi_toolswidget.view.ThemeSlider(this).apply {
            minValue = 6f; maxValue = 14f; stepSize = 1f
            currentValue = currentFontSize
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
            onValueChange = { v ->
                currentFontSize = v
                fontSizeLabel.text = "字体大小: ${v.toInt()}sp"
            }
        }
        content.addView(slider)

        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE
        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                SPUtil.setShowModel4x1(this@WidgetSettingsActivity, tempStates["model"]!!)
                SPUtil.setShowSignal4x1(this@WidgetSettingsActivity, tempStates["signal"]!!)
                SPUtil.setShowBattery4x1(this@WidgetSettingsActivity, tempStates["battery"]!!)
                SPUtil.setShowTemp4x1(this@WidgetSettingsActivity, tempStates["temp"]!!)
                SPUtil.setShowTime4x1(this@WidgetSettingsActivity, tempStates["time"]!!)
                SPUtil.setFontSize4x1(this@WidgetSettingsActivity, currentFontSize.toInt())
                updateDisplayInfo4x1Subtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
                dialog.dismiss()
            }
        }
        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE; text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
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
        val label = if (widgetIntervalMinutes <= 0) "关闭" else "${widgetIntervalMinutes} 分钟"
        try {
            findInItem<TextView>(R.id.item_widget_interval, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showWidgetIntervalDialog() {
        activeWidgetIntervalDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetIntervalDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "后台刷新频率"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_clock_bolt)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val valueLabel = TextView(this).apply {
            text = "${widgetIntervalMinutes} 分钟"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.accent(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val slider = com.ufi_toolswidget.view.ThemeSlider(this).apply {
            minValue = 1f
            maxValue = 120f
            stepSize = 1f
            currentValue = if (widgetIntervalMinutes > 0) widgetIntervalMinutes.toFloat().coerceIn(1f, 120f) else 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
            onValueChange = { value ->
                widgetIntervalMinutes = value.toInt()
                valueLabel.text = "${value.toInt()} 分钟"
                SPUtil.setRefreshInterval(this@WidgetSettingsActivity, value.toInt())
                updateWidgetIntervalSubtitle()
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, 30f) { "${it}分" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()} 分钟"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(15, 30, 60, 120),
            formatLabel = { "${it}分" },
            currentValue = widgetIntervalMinutes,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        slider.onValueChange = { value ->
            widgetIntervalMinutes = value.toInt()
            valueLabel.text = "${value.toInt()} 分钟"
            updatePresets(value.toInt())
            SPUtil.setRefreshInterval(this@WidgetSettingsActivity, value.toInt())
            updateWidgetIntervalSubtitle()
        }

        // 自定义输入面板
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 1-1440 分钟",
            validate = { text ->
                val mins = text.toIntOrNull()
                when {
                    mins == null -> "请输入有效数字"
                    mins !in 1..1440 -> "请输入 1-1440 之间的分钟数"
                    else -> null
                }
            },
            onConfirm = { text ->
                widgetIntervalMinutes = text.toInt()
                SPUtil.setRefreshInterval(this@WidgetSettingsActivity, text.toInt())
                updateWidgetIntervalSubtitle()
                updateWidgetWorker()
                dialog.dismiss()
                Toast.makeText(this@WidgetSettingsActivity, "自定义间隔已设为 ${text}分钟", Toast.LENGTH_SHORT).show()
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // 公共弹窗按钮
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确定"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == android.view.View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    val et = customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")
                    et?.requestFocus()
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetIntervalDialog = dialog
        dialog.show()
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
        // 裁剪目标为推荐尺寸的 2.5 倍，确保背景图清晰度（缓存目标 640x320）
        val density = resources.displayMetrics.density
        val widgetW = (625f * density).toInt()
        val widgetH = (275f * density).toInt()
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
        BaseWifiWidget.renderAllWidgets(this, force = true)
        Toast.makeText(this, "小组件背景图片已更新", Toast.LENGTH_SHORT).show()
    }

    private fun showWidgetBgImageDialog() {
        activeBgImageDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgImageDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
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
                BaseWifiWidget.renderAllWidgets(this, force = true)
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

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "背景透明度"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_opacity)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val valueLabel = TextView(this).apply {
            text = "$widgetBgOpacity%"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.accent(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val defVal = if (widgetBgOpacity in 0..100) widgetBgOpacity.toFloat() else 100f
        val slider = com.ufi_toolswidget.view.ThemeSlider(this).apply {
            minValue = 0f
            maxValue = 100f
            stepSize = 1f
            currentValue = defVal
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
            onValueChange = { value ->
                widgetBgOpacity = value.toInt()
                valueLabel.text = "${value.toInt()}%"
                SPUtil.setWidgetBgOpacity(this@WidgetSettingsActivity, value.toInt())
                updateWidgetBgOpacitySubtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, 20f) { "${it}%" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()}%"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(100, 80, 60, 40, 20),
            formatLabel = { "${it}%" },
            currentValue = widgetBgOpacity,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        slider.onValueChange = { value ->
            widgetBgOpacity = value.toInt()
            valueLabel.text = "${value.toInt()}%"
            updatePresets(value.toInt())
            SPUtil.setWidgetBgOpacity(this@WidgetSettingsActivity, value.toInt())
            updateWidgetBgOpacitySubtitle()
            BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
        }

        // 自定义输入面板
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 0-100",
            validate = { text ->
                val v = text.toIntOrNull()
                when {
                    v == null -> "请输入有效数字"
                    v !in 0..100 -> "请输入 0-100 之间的数字"
                    else -> null
                }
            },
            onConfirm = { text ->
                widgetBgOpacity = text.toInt()
                SPUtil.setWidgetBgOpacity(this@WidgetSettingsActivity, text.toInt())
                updateWidgetBgOpacitySubtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
                dialog.dismiss()
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // 公共弹窗按钮
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确定"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == android.view.View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    val et = customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")
                    et?.requestFocus()
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeBgOpacityDialog = dialog
        dialog.show()
    }

    // ==================== 6. 兼容性设置（点击弹窗） ====================
    private fun initWidgetCompatibilityItem() {
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_widget_compatibility),
            iconRes = R.drawable.ic_rounded_corners,
            title = "兼容性设置",
            subtitle = "圆角裁剪、隐藏名称等",
            onClick = ::showCompatibilityDialog
        )
    }

    private fun updateCompatibilitySubtitle() {
        val parts = mutableListOf<String>()
        if (SPUtil.getWidgetClipToOutline(this)) parts.add("圆角")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SPUtil.getWidgetHideLabel(this)) parts.add("隐藏名称")
        val subtitle = if (parts.isEmpty()) "圆角裁剪、隐藏名称等" else parts.joinToString("、")
        try {
            findViewById<TextView>(R.id.item_widget_compatibility)?.findViewById<TextView>(R.id.common_item_subtitle)?.text = subtitle
        } catch (_: Exception) {}
    }

    private fun showCompatibilityDialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_rounded_corners)
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "兼容性设置"

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 临时状态
        var tempClipToOutline = SPUtil.getWidgetClipToOutline(this)
        var tempHideLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SPUtil.getWidgetHideLabel(this) else false

        // ── 圆角裁剪兜底 ──
        val clipRow = layoutInflater.inflate(R.layout.layout_common_switch, content, false)
        clipRow.findViewById<TextView>(R.id.common_switch_label).text = "兼容性小组件圆角"
        ThemeUtil.setupSwitch(clipRow, tempClipToOutline) { tempClipToOutline = it }
        content.addView(clipRow)

        // ── 隐藏小组件名称 ──
        val hideLabelRow = layoutInflater.inflate(R.layout.layout_common_switch, content, false)
        hideLabelRow.findViewById<TextView>(R.id.common_switch_label).text = "隐藏小组件名称"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ThemeUtil.setupSwitch(hideLabelRow, tempHideLabel) { tempHideLabel = it }
        } else {
            hideLabelRow.alpha = 0.5f
            val track = hideLabelRow.findViewById<View>(R.id.common_switch_track)
            track.isClickable = false
            track.isEnabled = false
            track.setOnClickListener(null)
            // 显示为关闭状态
            val subtitle = TextView(this).apply {
                text = "需要 Android 12+ 支持"
                setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
                textSize = 11f
                alpha = 0.7f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp2px(4), 0, 0, 0) }
            }
            (hideLabelRow as ViewGroup).addView(subtitle)
        }
        content.addView(hideLabelRow)

        // 对 content 内动态添加的视图递归着色
        CommonDialogHelper.applyThemeToViewTree(content, this)

        // 按钮区域
        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                SPUtil.setWidgetClipToOutline(this@WidgetSettingsActivity, tempClipToOutline)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SPUtil.setWidgetHideLabel(this@WidgetSettingsActivity, tempHideLabel)
                }
                updateCompatibilitySubtitle()
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
                dialog.dismiss()
            }
        }

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
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
