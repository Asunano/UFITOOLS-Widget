package com.ufi_toolswidget.util

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.R

/**
 * 统一弹窗与菜单工具类。
 * 
 * 弹窗创建统一委托给 [CommonDialogHelper]，确保样式一致。
 */
object PopupViewUtil {

    /**
     * 显示精致的下拉菜单（基于 PopupWindow）
     */
    fun showDropDownMenu(
        anchor: View,
        options: Array<String>,
        currentIndex: Int = -1,
        onSelect: (Int) -> Unit
    ) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        
        // 1. 创建滚动容器和内容列表
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp2px(context, 4)
            setPadding(p, p, p, p)
        }
        scroll.addView(contentLayout)

        val textPrimary = ThemeColors.textPrimary(context)
        val accent = ThemeColors.accent(context)
        val cardBg = ThemeColors.cardBg(context)
        val borderColor = if (ThemeColors.isDark(context))
            0x60FFFFFF.toInt() else 0x35000000

        // 2. 预创建弹窗对象
        val popup = android.widget.PopupWindow(scroll, 
            anchor.width, // 宽度与锚点对齐
            WindowManager.LayoutParams.WRAP_CONTENT, 
            true
        )

        // 3. 填充选项
        options.forEachIndexed { index, option ->
            val itemView = inflater.inflate(R.layout.layout_dialog_list_item, contentLayout, false)
            
            // 移除外层 Margin，实现紧凑排列
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = 0
                bottomMargin = 0
            }

            val tv = itemView.findViewById<TextView>(R.id.dialog_item_text)
            tv.apply {
                text = option
                setTextColor(textPrimary)
                textSize = 14f
            }
            
            // 选中态显示逻辑
            if (index == currentIndex) {
                val bgSelected = itemView.findViewById<View>(R.id.dialog_item_bg)
                val ivCheck = itemView.findViewById<ImageView>(R.id.dialog_item_check)
                
                bgSelected.visibility = View.VISIBLE
                ivCheck.visibility = View.VISIBLE
                ivCheck.setColorFilter(accent)
                tv.setTextColor(accent)
                tv.paint.isFakeBoldText = true
                
                // 绘制选中背景
                val alphaAccent = (accent and 0x00FFFFFF) or 0x26000000 // 15% alpha
                bgSelected.background = GradientDrawable().apply {
                    setColor(alphaAccent)
                    cornerRadius = dp2px(context, 8).toFloat()
                }
            } else {
                itemView.findViewById<View>(R.id.dialog_item_bg).visibility = View.GONE
                itemView.findViewById<View>(R.id.dialog_item_check).visibility = View.GONE
            }
            
            // 菜单项高度：显著减小间隙 (14dp -> 8dp)
            val innerContent = tv.parent as View
            innerContent.setPadding(dp2px(context, 14), dp2px(context, 8), dp2px(context, 14), dp2px(context, 8))

            itemView.setOnClickListener {
                onSelect(index)
                popup.dismiss()
            }
            contentLayout.addView(itemView)
        }

        // 4. 智能高度识别：计算最大允许高度 (屏幕高度的 40%)
        val dm = context.resources.displayMetrics
        val maxAvailableHeight = (dm.heightPixels * 0.4f).toInt()

        // 测量实际所需高度
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        if (scroll.measuredHeight > maxAvailableHeight) {
            popup.height = maxAvailableHeight
        }

        // 5. 应用主题外观
        scroll.background = GradientDrawable().apply {
            setColor(cardBg)
            cornerRadius = dp2px(context, 12).toFloat()
            setStroke(2, borderColor)
        }
        
        popup.elevation = dp2px(context, 12).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.animationStyle = R.style.DialogAnimationTheme
        popup.showAsDropDown(anchor, 0, dp2px(context, 2))
    }

    /**
     * 显示通用确认/警告弹窗
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        iconRes: Int = android.R.drawable.ic_dialog_alert,
        primaryBtnText: String = "确定",
        secondaryBtnText: String = "取消",
        isWarning: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog {
        val dialog = CommonDialogHelper.createAnimatedDialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.layout_common_dialog)

        CommonDialogHelper.applyThemeToDialogRoot(context, dialog)

        val textPrimary = ThemeColors.textPrimary(context)
        val warnColor = 0xFFE53935.toInt()

        dialog.findViewById<TextView>(R.id.common_dialog_title).apply {
            text = title
            if (isWarning) setTextColor(warnColor)
        }
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).apply {
            setImageResource(iconRes)
            if (isWarning) setColorFilter(warnColor)
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(TextView(context).apply {
            text = message
            textSize = 15f
            setTextColor(textPrimary)
            setLineSpacing(0f, 1.2f)
        })

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = primaryBtnText
            if (isWarning) {
                backgroundTintList = ColorStateList.valueOf(warnColor)
                setTextColor(0xFFFFFFFF.toInt())
            }
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = secondaryBtnText
            setOnClickListener { dialog.dismiss() }
        }

        setupDialogWindow(context, dialog)
        dialog.show()
        return dialog
    }

    fun setupDialogWindow(context: Context, dialog: Dialog) {
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.08f)
            setWindowAnimations(R.style.DialogAnimationTheme)
        }
        
        // ── 动态模糊背景：API 31+ 原生模糊 ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AnimationUtil.applyDialogBlurIn(dialog)
        }

        // 填充内容后、展示前，执行智能高度适配
        autoAdjustDialogHeight(context, dialog)
    }

    /**
     * 智能识别弹窗内容高度。
     * 若超过屏幕 82%，则锁定窗口高度并启用内部滚动。
     */
    fun autoAdjustDialogHeight(context: Context, dialog: Dialog, widthRatio: Float = 0.88f) {
        val window = dialog.window ?: return
        val root = dialog.findViewById<View>(R.id.common_dialog_root) ?: return
        val scroll = dialog.findViewById<View>(R.id.common_dialog_scroll_view) ?: return
        
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * widthRatio).toInt()
        val maxHeight = (dm.heightPixels * 0.82f).toInt()

        // 测量前重置为自适应，以获取真实内容高度
        val rootLp = root.layoutParams
        rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        root.layoutParams = rootLp
        
        val scrollLp = scroll.layoutParams as LinearLayout.LayoutParams
        scrollLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        scrollLp.weight = 0f
        scroll.layoutParams = scrollLp

        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        if (root.measuredHeight > maxHeight) {
            // 内容过多 → 限制窗口总高度，并让 ScrollView 填充剩余空间
            window.setLayout(width, maxHeight)
            
            // 确保根布局也填满窗口，否则 weight 失效
            rootLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            root.layoutParams = rootLp

            val lp = scroll.layoutParams as LinearLayout.LayoutParams
            lp.height = 0
            lp.weight = 1f
            scroll.layoutParams = lp

            // ── 主题统一与唤醒：应用当前主题色并闪烁提示 ──
            val themeColor = ThemeColors.textSecondary(context)
            val thumbColor = (themeColor and 0x00FFFFFF) or 0x4D000000 // 30% 不透明度
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val scrollV = scroll as? ScrollView
                    scrollV?.verticalScrollbarThumbDrawable?.let {
                        val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(it).mutate()
                        androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, thumbColor)
                        scrollV.verticalScrollbarThumbDrawable = wrapped
                    }
                } catch (e: Exception) { DebugLogger.w("PopupViewUtil", "scrollbar tint failed: ${e.message}") }
            }

            // 强制唤醒滚动条
            scroll.postDelayed({
                try {
                    val method = View::class.java.getDeclaredMethod("awakenScrollBars")
                    method.isAccessible = true
                    method.invoke(scroll)
                } catch (_: Exception) {
                    scroll.isVerticalScrollBarEnabled = false
                    scroll.isVerticalScrollBarEnabled = true
                }
            }, 350)
        } else {
            // 内容较少 → 保持自适应
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            root.layoutParams = rootLp

            val lp = scroll.layoutParams as LinearLayout.LayoutParams
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            scroll.layoutParams = lp
        }
    }

    private fun dp2px(context: Context, dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
