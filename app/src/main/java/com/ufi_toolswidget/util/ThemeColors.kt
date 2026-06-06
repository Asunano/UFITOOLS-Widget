package com.ufi_toolswidget.util

import android.app.UiModeManager
import android.content.Context

/**
 * 配色主题系统。
 * 索引 0 = 默认白，1-4 = 四套预设主题。
 */
object ThemeColors {

    data class Palette(
        val id: Int,
        val name: String,
        // 浅色模式
        val pageBgLight: Int,
        val cardBgLight: Int,
        val textPrimaryLight: Int,
        val textSecondaryLight: Int,
        val dividerLight: Int,
        val accentLight: Int,
        val accentSecondaryLight: Int,
        val btnBgLight: Int,
        val iconTintLight: Int,
        // 深色模式
        val pageBgDark: Int,
        val cardBgDark: Int,
        val textPrimaryDark: Int,
        val textSecondaryDark: Int,
        val dividerDark: Int,
        val accentDark: Int,
        val accentSecondaryDark: Int,
        val btnBgDark: Int,
        val iconTintDark: Int,
        // 核心数据高亮色 (设备名、流量数值专用，独立于交互强调色)
        val dataHighlightLight: Int,
        val dataHighlightDark: Int,
    )

    /** 所有主题 */
    val ALL = listOf(
        Palette(
            id = 0,
            name = "默认",
            // 浅色
            pageBgLight     = 0xFFF8F8F8.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF111111.toInt(),
            textSecondaryLight = 0xFF444444.toInt(),
            dividerLight    = 0xFFE5E5E5.toInt(),
            accentLight     = 0xFF222222.toInt(),
            accentSecondaryLight = 0xFFE5E5E5.toInt(),
            btnBgLight      = 0xFF222222.toInt(),
            iconTintLight   = 0xFF111111.toInt(),
            // 深色
            pageBgDark      = 0xFF1A1A1A.toInt(),
            cardBgDark      = 0xFF2A2A2A.toInt(),
            textPrimaryDark   = 0xFFEEEEEE.toInt(),
            textSecondaryDark  = 0xFFBBBBBB.toInt(),
            dividerDark     = 0xFF333333.toInt(),
            accentDark      = 0xFF555555.toInt(),
            accentSecondaryDark = 0xFF555555.toInt(),
            btnBgDark       = 0xFF5A5A5A.toInt(),
            iconTintDark    = 0xFFEEEEEE.toInt(),
            // 数据高亮：浅色用深黑，深色用纯白
            dataHighlightLight = 0xFF111111.toInt(),
            dataHighlightDark  = 0xFFFFFFFF.toInt(),
        ),
        Palette(
            id = 1,
            name = "科技蓝",
            // 浅色
            pageBgLight     = 0xFFF5F7FA.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF1D2129.toInt(),
            textSecondaryLight = 0xFF86909C.toInt(),
            dividerLight    = 0xFFE5E6EB.toInt(),
            accentLight     = 0xFF1677FF.toInt(),
            accentSecondaryLight = 0xFF69B1FF.toInt(),
            btnBgLight      = 0xFF1677FF.toInt(),
            iconTintLight   = 0xFF1677FF.toInt(),
            // 深色
            pageBgDark      = 0xFF1D2939.toInt(),
            cardBgDark      = 0xFF263548.toInt(),
            textPrimaryDark   = 0xFFE8EDF2.toInt(),
            textSecondaryDark  = 0xFF86909C.toInt(),
            dividerDark     = 0xFF2A3A4E.toInt(),
            accentDark      = 0xFF0E5ACD.toInt(),
            accentSecondaryDark = 0xFF69B1FF.toInt(),
            btnBgDark       = 0xFF0E5ACD.toInt(),
            iconTintDark    = 0xFF0E5ACD.toInt(),
            dataHighlightLight = 0xFF1677FF.toInt(),
            dataHighlightDark  = 0xFF69B1FF.toInt(),
        ),
        Palette(
            id = 2,
            name = "薄荷绿",
            // 浅色
            pageBgLight     = 0xFFF7FCFA.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF2C3631.toInt(),
            textSecondaryLight = 0xFF7A9487.toInt(),
            dividerLight    = 0xFFE2EBE6.toInt(),
            accentLight     = 0xFF34C799.toInt(),
            accentSecondaryLight = 0xFF90E4C3.toInt(),
            btnBgLight      = 0xFF34C799.toInt(),
            iconTintLight   = 0xFF34C799.toInt(),
            // 深色
            pageBgDark      = 0xFF1A2822.toInt(),
            cardBgDark      = 0xFF24332D.toInt(),
            textPrimaryDark   = 0xFFD8E8DF.toInt(),
            textSecondaryDark  = 0xFF7A9487.toInt(),
            dividerDark     = 0xFF2A3D33.toInt(),
            accentDark      = 0xFF34C799.toInt(),
            accentSecondaryDark = 0xFF1B6B4E.toInt(),
            btnBgDark       = 0xFF228B55.toInt(),
            iconTintDark    = 0xFF34C799.toInt(),
            dataHighlightLight = 0xFF34C799.toInt(),
            dataHighlightDark  = 0xFF90E4C3.toInt(),
        ),
        Palette(
            id = 3,
            name = "梦幻紫",
            // 浅色
            pageBgLight     = 0xFFF7F5FF.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF3A3152.toInt(),
            textSecondaryLight = 0xFF8A84B8.toInt(),
            dividerLight    = 0xFFEAE6FC.toInt(),
            accentLight     = 0xFF7B61FF.toInt(),
            accentSecondaryLight = 0xFFB1A1FF.toInt(),
            btnBgLight      = 0xFF7B61FF.toInt(),
            iconTintLight   = 0xFF7B61FF.toInt(),
            // 深色
            pageBgDark      = 0xFF1A1630.toInt(),
            cardBgDark      = 0xFF272044.toInt(),
            textPrimaryDark   = 0xFFEAE6FF.toInt(),
            textSecondaryDark  = 0xFF8A84B8.toInt(),
            dividerDark     = 0xFF2A2540.toInt(),
            accentDark      = 0xFFB1A1FF.toInt(),
            accentSecondaryDark = 0xFF5B46CC.toInt(),
            btnBgDark       = 0xFF5B46CC.toInt(),
            iconTintDark    = 0xFFB1A1FF.toInt(),
            dataHighlightLight = 0xFF7B61FF.toInt(),
            dataHighlightDark  = 0xFFB1A1FF.toInt(),
        ),
        Palette(
            id = 4,
            name = "活力橙",
            // 浅色
            pageBgLight     = 0xFFFFF8F3.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF3D2B20.toInt(),
            textSecondaryLight = 0xFF997B69.toInt(),
            dividerLight    = 0xFFFFEDE0.toInt(),
            accentLight     = 0xFFFF7D34.toInt(),
            accentSecondaryLight = 0xFFFFB989.toInt(),
            btnBgLight      = 0xFFFF7D34.toInt(),
            iconTintLight   = 0xFFFF7D34.toInt(),
            // 深色
            pageBgDark      = 0xFF241A15.toInt(),
            cardBgDark      = 0xFF2F221A.toInt(),
            textPrimaryDark   = 0xFFE8D8CC.toInt(),
            textSecondaryDark  = 0xFF997B69.toInt(),
            dividerDark     = 0xFF3A2A20.toInt(),
            accentDark      = 0xFFFF7D34.toInt(),
            accentSecondaryDark = 0xFFB86020.toInt(),
            btnBgDark       = 0xFFCC5500.toInt(),
            iconTintDark    = 0xFFFF7D34.toInt(),
            dataHighlightLight = 0xFFFF7D34.toInt(),
            dataHighlightDark  = 0xFFFFB989.toInt(),
        ),
    )

    /** 按 ID 获取主题（id=-1 为自定义，从 SP 读取颜色） */
    fun getById(ctx: Context, id: Int, isWidget: Boolean = false): Palette {
        if (id == -1) return buildCustomPalette(ctx, isWidget)
        return ALL.find { it.id == id } ?: ALL[0]
    }

    /** 按 ID 获取预设主题（不读取 SP，用于列表遍历） */
    fun getById(id: Int): Palette = ALL.find { it.id == id } ?: ALL[0]

    /** 从 SharedPreferences 构建自定义 Palette */
    private fun buildCustomPalette(ctx: Context, isWidget: Boolean = false): Palette {
        val sp = SPUtil.getSp(ctx)
        val accentL = if (isWidget) sp.getInt("widget_custom_accent_light", 0xFF222222.toInt())
                      else sp.getInt("custom_accent_light", 0xFF222222.toInt())
        val accentD = if (isWidget) sp.getInt("widget_custom_accent_dark", 0xFFCCCCCC.toInt())
                      else sp.getInt("custom_accent_dark", 0xFFCCCCCC.toInt())

        // 基于强调色自动推导辅色（亮度 ±30%）
        fun deriveSecondary(accent: Int, factor: Float): Int {
            val r = ((accent shr 16) and 0xFF)
            val g = ((accent shr 8) and 0xFF)
            val b = (accent and 0xFF)
            val nr = (r * factor).toInt().coerceIn(0, 255)
            val ng = (g * factor).toInt().coerceIn(0, 255)
            val nb = (b * factor).toInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (nr shl 16) or (ng shl 8) or nb
        }

        // 基于强调色推导暗/浅背景（自动判断亮暗）
        fun isLightColor(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 > 180
        }

        val baseLight = isLightColor(accentL)
        val baseDark = isLightColor(accentD)

        return Palette(
            id = -1,
            name = "自定义",
            pageBgLight     = if (baseLight) 0xFFF5F5F5.toInt() else 0xFFF8F8F8.toInt(),
            cardBgLight     = 0xFFFFFFFF.toInt(),
            textPrimaryLight  = 0xFF111111.toInt(),
            textSecondaryLight = deriveSecondary(accentL, 0.45f),
            dividerLight    = 0xFFE5E5E5.toInt(),
            accentLight     = accentL,
            accentSecondaryLight = deriveSecondary(accentL, 0.65f),
            btnBgLight      = accentL,
            iconTintLight   = accentL,
            pageBgDark      = if (baseDark) 0xFF1A1A1A.toInt() else 0xFF121212.toInt(),
            cardBgDark      = 0xFF2A2A2A.toInt(),
            textPrimaryDark   = 0xFFEEEEEE.toInt(),
            textSecondaryDark  = deriveSecondary(accentD, 0.6f),
            dividerDark     = 0xFF333333.toInt(),
            accentDark      = accentD,
            accentSecondaryDark = deriveSecondary(accentD, 0.45f),
            btnBgDark       = deriveSecondary(accentD, 0.72f),
            iconTintDark    = accentD,
            dataHighlightLight = accentL,
            dataHighlightDark  = accentD,
        )
    }

    // ==================== 便捷方法 ====================

    /** 判断当前是否为暗色模式 */
    fun isDark(ctx: Context): Boolean {
        val sp = SPUtil.getSp(ctx)
        val appTheme = sp.getString("app_theme", "system") ?: "system"
        return when (appTheme) {
            "dark" -> true
            "light" -> false
            else -> {
                // 使用 UiModeManager 读取真实系统暗色模式
                // （避免 resources.configuration.uiMode 被 AppCompatDelegate.setDefaultNightMode 污染）
                val uiModeMgr = ctx.getSystemService(UiModeManager::class.java)!!
                uiModeMgr.nightMode == UiModeManager.MODE_NIGHT_YES
            }
        }
    }

    /** 获取当前激活的主题 Palette */
    fun current(ctx: Context): Palette {
        val id = SPUtil.getSp(ctx).getInt("color_theme", 0)
        return getById(ctx, id)
    }

    /** 当前页面背景色 */
    fun pageBg(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.pageBgDark else p.pageBgLight
    }

    /** 当前卡片背景色 */
    fun cardBg(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.cardBgDark else p.cardBgLight
    }

    /** 当前主文字颜色 */
    fun textPrimary(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.textPrimaryDark else p.textPrimaryLight
    }

    /** 当前辅助文字颜色 */
    fun textSecondary(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.textSecondaryDark else p.textSecondaryLight
    }

    /** 当前强调色 */
    fun accent(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.accentDark else p.accentLight
    }

    /** 当前核心数据高亮色（专用，不用于交互背景） */
    fun dataHighlight(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.dataHighlightDark else p.dataHighlightLight
    }

    /** 当前辅助强调色 */
    fun accentSecondary(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.accentSecondaryDark else p.accentSecondaryLight
    }

    /** 当前分割线颜色 */
    fun divider(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.dividerDark else p.dividerLight
    }

    /** 当前按钮背景色（保证白色文字可读） */
    fun btnBg(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.btnBgDark else p.btnBgLight
    }

    /** 当前图标着色（用于 iconTint，保持品牌色辨识度） */
    fun iconTint(ctx: Context): Int {
        val p = current(ctx)
        return if (isDark(ctx)) p.iconTintDark else p.iconTintLight
    }
}
