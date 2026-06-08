package com.ufi_toolswidget

import android.app.Dialog
import android.app.UiModeManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.ufi_toolswidget.util.AnimationUtil
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.CommonDialogHelper
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeChangeNotifier
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.ThemedSliderUtil
import com.ufi_toolswidget.view.ThemeSlider
import com.ufi_toolswidget.widget.BaseWifiWidget

class AppSettingsActivity : AppCompatActivity() {

    private var mainIntervalSeconds: Int = 5

    /** 图片选择器（从媒体库选一张图片作为背景） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handlePickedImage(uri)
        }
    }

    /** 图片裁切启动器 */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val croppedUriStr = result.data?.getStringExtra("cropped_uri")
            if (!croppedUriStr.isNullOrBlank()) {
                applyBgImage(croppedUriStr.toUri())
            }
        }
    }

    private fun handlePickedImage(uri: Uri) {
        // 获取持久化 URI 权限
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {}

        // 检测尺寸与设备匹配度
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val screenRatio = screenH.toFloat() / screenW.toFloat()

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                val imgW = options.outWidth
                val imgH = options.outHeight
                val imgRatio = imgH.toFloat() / imgW.toFloat()

                // 如果比例差异超过 2% 或者图片太小，建议裁切
                if (Math.abs(imgRatio - screenRatio) > 0.02f || imgW < screenW || imgH < screenH) {
                    val intent = Intent(this, ImageCropActivity::class.java).apply {
                        data = uri
                        putExtra("targetW", screenW)
                        putExtra("targetH", screenH)
                    }
                    cropLauncher.launch(intent)
                } else {
                    applyBgImage(uri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            applyBgImage(uri) // 失败则直接应用
        }
    }

    private fun applyBgImage(uri: Uri) {
        BackgroundUtil.clearCache() // 强制清理，确保加载新图
        SPUtil.setBgImageUri(this, uri.toString())
        updateBgImageSubtitle()
        BackgroundUtil.applyWindowBackground(this)
        Toast.makeText(this, "背景图片已更新", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. 设置内容
        setContentView(R.layout.activity_app_settings)
        
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initDisplayModeItem()
        initThemeColorItem()
        initRefreshIntervalItem()
        // initBgImageItem() // 暂时隐藏入口

        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        updateDisplayModeSubtitle()
        updateThemeColorSubtitle()
        updateRefreshIntervalSubtitle()
        // updateBgImageSubtitle() // 暂时隐藏入口
    }

    // ==================== 显示模式（弹窗选择） ====================
    private var currentAppTheme: String = "system"
    private var activeThemeDialog: Dialog? = null

    private fun initDisplayModeItem() {
        currentAppTheme = SPUtil.getAppTheme(this)

        // 设置通用项的内容
        try {
            findInItem<ImageView>(R.id.item_display_mode, R.id.common_item_icon)?.setImageResource(getDisplayModeIcon())
            findInItem<TextView>(R.id.item_display_mode, R.id.common_item_title)?.text = "显示模式"
        } catch (_: Exception) {}
        updateDisplayModeSubtitle()

        findViewById<View>(R.id.item_display_mode).setOnClickListener {
            showDisplayModeDialog()
        }
    }

    /** 根据当前主题模式返回对应图标 */
    private fun getDisplayModeIcon(): Int = when (currentAppTheme) {
        "light" -> R.drawable.ic_sun
        "dark" -> R.drawable.ic_moon
        else -> R.drawable.ic_sun_moon
    }

    /** 更新设置项副标题为当前模式名称，并同步更新图标 */
    private fun updateDisplayModeSubtitle() {
        val modeName = when (currentAppTheme) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        try {
            findInItem<TextView>(R.id.item_display_mode, R.id.common_item_subtitle)?.text = modeName
            findInItem<ImageView>(R.id.item_display_mode, R.id.common_item_icon)?.setImageResource(getDisplayModeIcon())
        } catch (_: Exception) {}
    }

    /** 显示模式选择弹窗 */
    private fun showDisplayModeDialog() {
        activeThemeDialog?.takeIf { it.isShowing }?.dismiss()
        activeThemeDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        // ...

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cornerRadius = 12f * resources.displayMetrics.density

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "显示模式"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_sun_moon)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val options = listOf(
            "system" to "跟随系统",
            "light" to "浅色",
            "dark" to "深色"
        )
        options.forEach { (key, label) ->
            val isSelected = key == currentAppTheme
            content.addView(buildDialogOptionView(label, textPrimary, accent,
                selectedBg, unselectedBg, cornerRadius) {
                currentAppTheme = key
                updateDisplayModeSubtitle()
                dismissDialogWithAnimation(dialog) {
                    applyThemeModeChange(key)
                }
            }.apply {
                // 初始选中态
                if (isSelected) {
                    background = selectedBg
                    setTextColor(0xFFFFFFFF.toInt())
                }
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeThemeDialog = dialog
        dialog.show()
    }



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

    /** 应用显示模式切换：复用主题配色的原位圆形揭露动画 */
    private fun applyThemeModeChange(theme: String) {
        val oldStored = SPUtil.getAppTheme(this)
        if (oldStored == theme) return

        // 用 UiModeManager 读取真实系统深色模式（不受 AppCompat 覆盖影响）
        val uiModeMgr = getSystemService(UiModeManager::class.java)!!
        val isSystemDark = uiModeMgr.nightMode == UiModeManager.MODE_NIGHT_YES

        // 将 SP 存储值解析为当前有效暗色模式：
        //   "light" → 强制浅色  /  "dark" → 强制深色  /  其他(跟随系统) → 自动读取系统状态
        fun resolveIsDark(stored: String) = when (stored) {
            "light" -> false
            "dark" -> true
            else -> isSystemDark
        }

        val wasDark = resolveIsDark(oldStored)
        val willBeDark = resolveIsDark(theme)

        if (wasDark == willBeDark) {
            // 有效视觉模式不变（切换前后均为浅色或均为深色）→ 静默应用，跳过动画
            SPUtil.setAppTheme(this, theme)
            currentAppTheme = theme
            updateDisplayModeSubtitle()
            ThemeChangeNotifier.notifyThemeChanged(this)
            return
        }

        AnimationUtil.applyCircleRevealPulse(this) {
            SPUtil.setAppTheme(this, theme)
            currentAppTheme = theme

            BackgroundUtil.initActivity(this)
            ThemeUtil.applyToAppSettingsActivity(this)
            updateDisplayModeSubtitle()
            updateThemeColorSubtitle()
            updateRefreshIntervalSubtitle()
            updateBgImageSubtitle()
            BaseWifiWidget.renderAllWidgets(this, force = true)
            ThemeChangeNotifier.notifyThemeChanged(this)
        }
    }

    /** 从 include 项中查找子 View（避免同 ID 冲突） */
    private fun <T : View> findInItem(itemId: Int, childId: Int): T? {
        return findViewById<View>(itemId)?.findViewById(childId)
    }

    // ==================== 主题配色（弹窗选择） ====================
    private var currentThemeIndex: Int = 0
    private var activeColorDialog: Dialog? = null

    private fun initThemeColorItem() {
        currentThemeIndex = SPUtil.getColorThemeIndex(this)
        try {
            findInItem<ImageView>(R.id.item_theme_color, R.id.common_item_icon)?.setImageResource(R.drawable.ic_palette)
            findInItem<TextView>(R.id.item_theme_color, R.id.common_item_title)?.text = "主题配色"
        } catch (_: Exception) {}
        updateThemeColorSubtitle()

        findViewById<View>(R.id.item_theme_color).setOnClickListener {
            showThemeColorDialog()
        }
    }

    private fun updateThemeColorSubtitle() {
        val palette = ThemeColors.getById(this, currentThemeIndex)
        try {
            findInItem<TextView>(R.id.item_theme_color, R.id.common_item_subtitle)?.text = palette.name
        } catch (_: Exception) {}
    }

    private fun showThemeColorDialog() {
        activeColorDialog?.takeIf { it.isShowing }?.dismiss()
        activeColorDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "主题配色"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_palette)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val chipRadius = 12f * resources.displayMetrics.density

        val selectedBg = makeSelectedBg(accent, chipRadius)
        val unselectedBg = makeUnselectedBg(chipRadius)

        // 核心优化：使用 GridLayout 实现双栏显示
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        // 5 个预设主题
        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }

        // 自定义选项
        val customAccent = SPUtil.getCustomAccentLight(this)
        grid.addView(buildColorOption(-1, "自定义", customAccent,
            textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))

        // 自定义颜色编辑面板（放在网格下方）
        val customPanel = createCustomColorPanel(dialog, content, textPrimary, accent, cardBg)
        content.addView(customPanel)

        // 如果当前选中自定义，显示编辑面板
        if (currentThemeIndex == -1) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeColorDialog = dialog
        dialog.show()
    }

    /** 构建单个颜色选项行（增加 isGrid 模式适配） */
    private fun buildColorOption(
        index: Int, name: String, dotColor: Int,
        textPrimary: Int, accent: Int, cardBg: Int,
        selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog,
        isGrid: Boolean = false
    ): View {
        val isSelected = index == currentThemeIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
            
            if (isGrid) {
                // 网格模式：平分宽度
                val params = android.widget.GridLayout.LayoutParams()
                params.width = 0
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                params.setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                layoutParams = params
            } else {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp2px(8)
                }
            }
            
            background = if (isSelected) selectedBg else unselectedBg
            isClickable = true
            isFocusable = true
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
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
                val panel = content.findViewWithTag<View>("custom_color_panel")
                panel?.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (panel.visibility == View.VISIBLE) {
                    currentThemeIndex = -1
                    refreshColorDialogOptions(content, dialog, textPrimary, accent, cardBg)
                }
            } else {
                dismissDialogWithAnimation(dialog) {
                    selectColorTheme(index, dialog)
                }
            }
        }
        return row
    }

    /** 刷新弹窗内容（保持双栏结构） */
    private fun refreshColorDialogOptions(content: LinearLayout, dialog: Dialog, textPrimary: Int, accent: Int, cardBg: Int) {
        val chipRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, chipRadius)
        val unselectedBg = makeUnselectedBg(chipRadius)

        content.removeAllViews()
        
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }
        val customAccent = SPUtil.getCustomAccentLight(this)
        grid.addView(buildColorOption(-1, "自定义", customAccent,
            textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))

        val customPanel = createCustomColorPanel(dialog, content, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (currentThemeIndex == -1) customPanel.visibility = View.VISIBLE
    }

    /** 选择并应用颜色主题 */
    private fun selectColorTheme(index: Int, dialog: Dialog) {
        if (currentThemeIndex == index && index != -1) return
        currentThemeIndex = index
        updateThemeColorSubtitle()

        // 仅在非自定义确认时直接触发 Pulse 动画
        if (index != -1) {
            applyColorThemeChange(index)
        }
    }

    private fun applyColorThemeChange(index: Int) {
        // 使用专门的圆形揭露动画处理原位颜色切换
        // 注意：SP 写入必须在 onMutation 内部，否则 ThemeColors.pageBg() 会在动画截图前读到新值
        AnimationUtil.applyCircleRevealPulse(this) {
            SPUtil.setColorThemeIndex(this, index)

            // 1. 刷新 Activity 自身的颜色
            BackgroundUtil.initActivity(this)
            ThemeUtil.applyToAppSettingsActivity(this)
            
            // 2. 手动刷新当前显示的配色弹窗 UI
            activeColorDialog?.let { dialog ->
                if (dialog.isShowing) {
                    CommonDialogHelper.applyThemeToDialogRoot(this, dialog)
                    val textPrimary = ThemeColors.textPrimary(this)
                    val accent = ThemeColors.accent(this)
                    val cardBg = ThemeColors.cardBg(this)
                    val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
                    refreshColorDialogOptions(content, dialog, textPrimary, accent, cardBg)
                }
            }

            updateDisplayModeSubtitle()
            updateRefreshIntervalSubtitle()
            BaseWifiWidget.renderAllWidgets(this, force = true)
            ThemeChangeNotifier.notifyThemeChanged(this)
        }
    }

    /** 创建自定义颜色编辑面板 */
    private fun createCustomColorPanel(dialog: Dialog, content: LinearLayout, textPrimary: Int, accent: Int, cardBg: Int): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_color_panel"
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(12)
            }
        }

        // 分割线
        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp2px(12) }
            setBackgroundColor(textPrimary)
            alpha = 0.12f
        })

        // 标题
        panel.addView(TextView(this).apply {
            text = "自定义强调色"
            setTextColor(textPrimary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // 色块 + 输入框 + 应用按钮行
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(10)
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(40), dp2px(40))
            background = makeDot(SPUtil.getCustomAccentLight(this@AppSettingsActivity))
        }
        inputRow.addView(swatch)

        val tvStatusTip = TextView(this).apply {
            text = "支持十六进制格式 (如 #7B61FF)"
            setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
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
                setStroke(1, if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) 0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            hint = "#7B61FF"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
            textSize = 13f
            setPadding(dp2px(12), 0, dp2px(12), 0)
            setText(String.format("#%06X", 0xFFFFFF and SPUtil.getCustomAccentLight(this@AppSettingsActivity)))

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val input = s?.toString()?.trim() ?: ""
                    if (input.isEmpty()) {
                        tvStatusTip.text = "请输入颜色代码"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
                        return
                    }
                    val formatted = if (input.startsWith("#")) input else "#$input"
                    try {
                        val color = android.graphics.Color.parseColor(formatted)
                        swatch.background = makeDot(color)
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
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
                    SPUtil.setCustomAccentLight(this@AppSettingsActivity, color)
                    SPUtil.setCustomAccentDark(this@AppSettingsActivity, darkColor)
                    
                    // 标记当前选中自定义并刷新副标题
                    currentThemeIndex = -1
                    SPUtil.setColorThemeIndex(this@AppSettingsActivity, -1)
                    updateThemeColorSubtitle()
                    
                    // 先关闭弹窗，模糊退场动画结束后应用颜色以避免截图污染
                    dismissDialogWithAnimation(dialog) {
                        applyColorThemeChange(-1)
                    }

                    android.widget.Toast.makeText(this@AppSettingsActivity, "自定义颜色已应用", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this@AppSettingsActivity, "颜色格式无效", Toast.LENGTH_SHORT).show()
                }
            }
        }
        inputRow.addView(btnApply)
        panel.addView(inputRow)
        panel.addView(tvStatusTip)


        return panel
    }

    // ==================== 主界面刷新频率（滑块选择） ====================
    private var activeIntervalDialog: Dialog? = null

    private fun initRefreshIntervalItem() {
        mainIntervalSeconds = SPUtil.getMainRefreshSeconds(this)
        try {
            findInItem<ImageView>(R.id.item_refresh_interval, R.id.common_item_icon)?.setImageResource(R.drawable.ic_clock_bolt)
            findInItem<TextView>(R.id.item_refresh_interval, R.id.common_item_title)?.text = "主界面刷新频率"
        } catch (_: Exception) {}
        updateRefreshIntervalSubtitle()

        findViewById<View>(R.id.item_refresh_interval).setOnClickListener {
            showRefreshIntervalDialog()
        }
    }

    private fun updateRefreshIntervalSubtitle() {
        val label = if (mainIntervalSeconds == 0) "关闭" else "${mainIntervalSeconds} 秒"
        try {
            findInItem<TextView>(R.id.item_refresh_interval, R.id.common_item_subtitle)?.text = label
        } catch (_: Exception) {}
    }

    private fun showRefreshIntervalDialog() {
        activeIntervalDialog?.takeIf { it.isShowing }?.dismiss()
        activeIntervalDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "主界面刷新频率"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_clock_bolt)

        // 先应用主题着色，再配置按钮
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        // --- 滑块与标签 ---
        val valueLabel = TextView(this).apply {
            text = "${mainIntervalSeconds} 秒"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.accent(this@AppSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val slider = ThemeSlider(this).apply {
            minValue = 5f
            maxValue = 120f
            stepSize = 1f
            currentValue = if (mainIntervalSeconds > 0) mainIntervalSeconds.toFloat().coerceIn(5f, 120f) else 5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44)
            ).apply { topMargin = dp2px(8) }
            onValueChange = { value ->
                mainIntervalSeconds = value.toInt()
                valueLabel.text = "${value.toInt()} 秒"
                SPUtil.setMainRefreshSeconds(this@AppSettingsActivity, value.toInt())
                updateRefreshIntervalSubtitle()
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, 30f) { "${it}秒" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()} 秒"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(5, 10, 15, 30, 60),
            formatLabel = { "${it}秒" },
            currentValue = mainIntervalSeconds,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        slider.onValueChange = { value ->
            mainIntervalSeconds = value.toInt()
            valueLabel.text = "${value.toInt()} 秒"
            updatePresets(value.toInt())
            SPUtil.setMainRefreshSeconds(this@AppSettingsActivity, value.toInt())
            updateRefreshIntervalSubtitle()
        }

        // --- 自定义输入面板（默认隐藏） ---
        val customPanel = createCustomPanel(dialog, textPrimary)
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // --- 按钮区域：使用公共弹窗按钮，由 applyThemeToDialogRoot 自动着色 ---
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确定"
        btnPrimary.setOnClickListener { dismissDialogWithAnimation(dialog) }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == android.view.View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    val et = customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")
                    et?.let {
                        if (mainIntervalSeconds > 0 && mainIntervalSeconds !in 5..120) {
                            it.setText(mainIntervalSeconds.toString())
                        }
                        it.requestFocus()
                    }
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeIntervalDialog = dialog
        dialog.show()
    }

    private fun createCustomPanel(dialog: Dialog, textPrimary: Int): View {
        return CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 5-3600 秒",
            validate = { text ->
                val secs = text.toIntOrNull()
                when {
                    secs == null -> "请输入有效数字"
                    secs !in 5..3600 -> "请输入 5-3600 之间的秒数"
                    else -> null
                }
            },
            onConfirm = { text ->
                val secs = text.toInt()
                mainIntervalSeconds = secs
                SPUtil.setMainRefreshSeconds(this@AppSettingsActivity, secs)
                updateRefreshIntervalSubtitle()
                dismissDialogWithAnimation(dialog)
            }
        )
    }

    // ==================== 自定义背景图片 ====================
    private var activeBgDialog: Dialog? = null

    private fun initBgImageItem() {
        // 暂时隐藏入口
    }

    private fun updateBgImageSubtitle() {
        // 暂时隐藏入口
    }

    private fun showBgImageDialog() {
        activeBgDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgDialog = null

        val dialog = CommonDialogHelper.createDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "自定义背景"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_photo)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val cornerRadius = 12f * resources.displayMetrics.density

        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        // 选项1：选择图片
        content.addView(buildDialogOptionView("从相册选择图片", textPrimary, accent,
            selectedBg, unselectedBg, cornerRadius) {
            pickImageLauncher.launch("image/*")
            dismissDialogWithAnimation(dialog)
        })

        // 选项2：清除背景（仅在有自定义背景时显示）
        val hasBg = SPUtil.getBgImageUri(this).isNotBlank()
        if (hasBg) {
            content.addView(buildDialogOptionView("清除背景", textPrimary, accent,
                selectedBg, unselectedBg, cornerRadius) {
                SPUtil.clearBgImageUri(this)
                BackgroundUtil.clearCache()
                updateBgImageSubtitle()
                BackgroundUtil.applyWindowBackground(this)
                Toast.makeText(this, "背景已清除", Toast.LENGTH_SHORT).show()
                dismissDialogWithAnimation(dialog)
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)

        activeBgDialog = dialog
        dialog.show()
    }

    // ==================== 统一弹窗选项构建 ====================

    /**
     * 创建统一风格的弹窗选项视图 — 匹配 main.xml / settings 的 AppCard 组件风格：
     * - 未选中：cardBg + 细描边 + 12dp 圆角（即 bg_widget_card 风格）
     * - 选中：accent 实色填充
     * - 点击涟漪：?attr/selectableItemBackground
     */
    private fun buildDialogOptionView(
        label: String,
        textPrimary: Int,
        accent: Int,
        selectedBg: GradientDrawable,
        unselectedBg: GradientDrawable,
        cornerRadius: Float,
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
            // 涟漪效果 — 匹配 AppCard 的 ?attr/selectableItemBackground
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
            setOnClickListener {
                // 选中态高亮 → 回调
                background = selectedBg
                setTextColor(0xFFFFFFFF.toInt())
                postDelayed({ onClick() }, 120)
            }
        }
    }

    /** 构建选中态背景：accent 实色 + 圆角（匹配 bg_btn_primary 风格） */
    private fun makeSelectedBg(accent: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            this.cornerRadius = cornerRadius
        }
    }

    /** 构建未选中态背景：cardBg + 细描边 + 圆角（匹配 bg_widget_card 风格） */
    private fun makeUnselectedBg(cornerRadius: Float): GradientDrawable {
        val cardBg = ThemeColors.cardBg(this@AppSettingsActivity)
        val borderColor = if (SPUtil.getNightMode(this@AppSettingsActivity) == AppCompatDelegate.MODE_NIGHT_YES)
            0x30FFFFFF.toInt() else 0x20000000
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            this.cornerRadius = cornerRadius
            setStroke(1, borderColor)
        }
    }

    // ==================== 工具方法 ====================
    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun parseColor(str: String): Int? {
        return try { Color.parseColor(str) } catch (e: Exception) { null }
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = (color and 0xFF)
        val nr = (r * factor).toInt().coerceIn(0, 255)
        val ng = (g * factor).toInt().coerceIn(0, 255)
        val nb = (b * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /** 创建纯色小圆点 */
    private fun makeDot(color: Int, displayColor: Int = color): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(displayColor)
        }
    }

}
