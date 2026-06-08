package com.ufi_toolswidget.util

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.TypedValue
import com.google.android.material.color.DynamicColors

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
        // 自定义配色优先：用户明确选择了自定义颜色时，直接使用自定义调色板
        if (id == -1) return buildCustomPalette(ctx, isWidget)
        // Android 12+ 动态配色：仅对预设主题生效，从系统壁纸提取 Material You 色调
        if (isWidget && supportsDynamicColors() && SPUtil.getWidgetDynamicColor(ctx)) {
            return buildDynamicPalette(ctx)
        }
        if (id in ALL.indices) return ALL[id]
        return ALL[0]
    }

    /** 按 ID 获取预设主题（不读取 SP，用于列表遍历） */
    fun getById(id: Int): Palette {
        if (id in ALL.indices) return ALL[id]
        return ALL[0]
    }

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

    // ==================== Android 12+ 动态配色（Material You）====================

    /**
     * 当前设备是否支持 Material You 动态配色。
     * 需要 Android 12 (API 31) 及以上版本，且 OEM 提供了动态配色能力。
     */
    fun supportsDynamicColors(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try { DynamicColors.isDynamicColorAvailable() } catch (_: Exception) { true }
    }

    /** 壁纸可用的所有色调 */
    data class WallpaperColorSet(
        val primary: Int?,
        val secondary: Int?,
        val tertiary: Int?
    )

    /**
     * 公开入口：供 WifiWidget 在独立颜色路径中获取动态调色板。
     * 根据用户选择的色源（primary/secondary/tertiary/neutral/neutral_variant）
     * 从系统壁纸提取对应的主色，再构建完整调色板。
     */
    fun buildDynamicPalette(ctx: Context): Palette {
        return buildWallpaperBasedPalette(ctx)
    }

    /**
     * 获取用户当前选择的色源颜色。
     * 仅从小组件自定义背景图提取；未设置背景图时返回 null。
     * @param source 0=primary, 1=secondary, 2=tertiary, 3=neutral, 4=neutral_variant
     */
    fun getWallpaperSourceColor(ctx: Context): Int? {
        val colors = extractWidgetAwareColors(ctx)
        val source = SPUtil.getWidgetDynamicColorSource(ctx)
        val selected = when (source) {
            1 -> colors.secondary
            2 -> colors.tertiary
            3 -> colors.primary?.let { deriveNeutral(it) }
            4 -> colors.primary?.let { deriveNeutralVariant(it) }
            else -> colors.primary
        }
        return selected ?: colors.primary ?: colors.secondary ?: colors.tertiary
    }

    /** 获取所有可用的壁纸色调名称和对应颜色（用于 UI 显示） */
    fun getAvailableWallpaperColors(ctx: Context): List<Pair<String, Int?>> {
        val colors = extractWidgetAwareColors(ctx)
        return listOf(
            "Primary (主色)" to colors.primary,
            "Secondary (次色)" to colors.secondary,
            "Tertiary (第三色)" to colors.tertiary,
            "Neutral (中性色)" to colors.primary?.let { deriveNeutral(it) },
            "Neutral Variant (中性变体)" to colors.primary?.let { deriveNeutralVariant(it) }
        )
    }

    // ==================== 壁纸色调提取 ====================

    /**
     * 从系统壁纸提取所有可用色调（Primary / Secondary / Tertiary）。
     * 三级兜底策略：
     * 1. WallpaperManager.getWallpaperColors() — API 27+，系统级色彩提取
     * 2. WallpaperManager.drawable 采样 — 直接从壁纸图像采样主色
     * 3. 返回默认颜色集
     */
    private fun extractWallpaperColors(ctx: Context): WallpaperColorSet {
        // 方式 1: WallpaperColors API (API 27+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val wm = android.app.WallpaperManager.getInstance(ctx)
                val wpColors = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                val primary   = wpColors?.primaryColor?.toArgb()
                val secondary = wpColors?.secondaryColor?.toArgb()
                val tertiary  = wpColors?.tertiaryColor?.toArgb()
                if (primary != null && primary != 0) {
                    return WallpaperColorSet(primary, secondary, tertiary)
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // 方式 2: 直接从壁纸 Drawable 采样主色
        try {
            val wm = android.app.WallpaperManager.getInstance(ctx)
            val drawable = wm.drawable
            if (drawable != null) {
                val dominant = sampleDominantColorFromDrawable(drawable)
                if (dominant != null) {
                    // 从主色推导近似 secondary/tertiary（偏移色相 ±30°）
                    val hsl = FloatArray(3)
                    android.graphics.Color.RGBToHSV(
                        (dominant shr 16) and 0xFF,
                        (dominant shr 8) and 0xFF,
                        dominant and 0xFF, hsl
                    )
                    val sec = buildHsvColor((hsl[0] + 30f) % 360f, (hsl[1] * 0.6f).coerceAtMost(1f), (hsl[2] * 0.8f).coerceAtMost(1f))
                    val ter = buildHsvColor((hsl[0] + 60f) % 360f, (hsl[1] * 0.4f).coerceAtMost(1f), (hsl[2] * 0.7f).coerceAtMost(1f))
                    return WallpaperColorSet(dominant, sec, ter)
                }
            }
        } catch (_: Exception) { /* fall through */ }

        return WallpaperColorSet(null, null, null)
    }

    private fun sampleDominantColorFromDrawable(drawable: android.graphics.drawable.Drawable): Int? {
        return try {
            val sampleSize = 20
            val bitmap = android.graphics.Bitmap.createBitmap(sampleSize, sampleSize, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, sampleSize, sampleSize)
            drawable.draw(canvas)

            val hsl = FloatArray(3)
            var bestPixel = 0
            var bestSat = -1f
            var firstOpaque = 0
            var foundAny = false

            for (y in 0 until sampleSize) {
                for (x in 0 until sampleSize) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = (pixel shr 24) and 0xFF
                    if (alpha < 128) continue
                    if (!foundAny) { firstOpaque = pixel; foundAny = true }
                    android.graphics.Color.RGBToHSV(
                        (pixel shr 16) and 0xFF,
                        (pixel shr 8) and 0xFF,
                        pixel and 0xFF, hsl
                    )
                    if (hsl[1] > bestSat) {
                        bestSat = hsl[1]
                        bestPixel = pixel
                    }
                }
            }
            bitmap.recycle()

            if (foundAny) {
                if (bestSat > 0.05f) bestPixel else firstOpaque
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 从 URI 加载图片并提取最具有代表性的颜色（频率统计法）。
     * 将各通道量化到 8 个区间（步长 32），统计像素最多的颜色桶，
     * 取该桶的平均 RGB 作为最终结果。目标采样尺寸约 40×40。
     */
    private fun sampleDominantColorFromUri(ctx: Context, uriString: String): Int? {
        return try {
            val uri = android.net.Uri.parse(uriString)

            // 1. 获取原图尺寸
            val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            // 2. 计算采样率（缩放到约 40px）
            val targetSize = 40
            var sampleSize = 1
            while (boundsOpts.outWidth / (sampleSize * 2) >= targetSize
                && boundsOpts.outHeight / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }

            // 3. 解码采样 Bitmap
            val bitmap = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize })
            } ?: return null

            // 4. 颜色量化统计（每通道 8 区间，步长 32），找出像素最多的颜色桶
            val quantizeStep = 32
            val colorBuckets = mutableMapOf<Int, MutableList<Int>>()
            var foundAny = false

            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = (pixel shr 24) and 0xFF
                    if (alpha < 128) continue
                    foundAny = true

                    val r = ((pixel shr 16) and 0xFF) / quantizeStep
                    val g = ((pixel shr 8) and 0xFF) / quantizeStep
                    val b = (pixel and 0xFF) / quantizeStep
                    val bucketKey = (r shl 16) or (g shl 8) or b
                    colorBuckets.getOrPut(bucketKey) { mutableListOf() }.add(pixel)
                }
            }
            bitmap.recycle()

            if (!foundAny) return null

            // 5. 取像素最多的颜色桶，计算平均色
            val dominantBucket = colorBuckets.maxByOrNull { it.value.size } ?: return null
            val pixels = dominantBucket.value
            val avgR = pixels.sumOf { (it shr 16) and 0xFF } / pixels.size
            val avgG = pixels.sumOf { (it shr 8) and 0xFF } / pixels.size
            val avgB = pixels.sumOf { it and 0xFF } / pixels.size

            return 0xFF000000.toInt() or (avgR shl 16) or (avgG shl 8) or avgB
        } catch (_: Exception) { null }
    }

    /**
     * 从小组件自定义背景图提取动态配色色源。
     * 仅从用户设置的小组件背景图片提取颜色，不兜底到系统壁纸。
     */
    private fun extractWidgetAwareColors(ctx: Context): WallpaperColorSet {
        val bgUri = SPUtil.getWidgetBgImageUri(ctx)
        if (bgUri.isBlank()) return WallpaperColorSet(null, null, null)

        return try {
            val dominant = sampleDominantColorFromUri(ctx, bgUri)
            if (dominant != null) {
                // 从主色推导近似 secondary/tertiary（偏移色相，降低饱和度/明度）
                val hsl = FloatArray(3)
                android.graphics.Color.RGBToHSV(
                    (dominant shr 16) and 0xFF,
                    (dominant shr 8) and 0xFF,
                    dominant and 0xFF, hsl
                )
                val sec = buildHsvColor((hsl[0] + 30f) % 360f, (hsl[1] * 0.6f).coerceAtMost(1f), (hsl[2] * 0.8f).coerceAtMost(1f))
                val ter = buildHsvColor((hsl[0] + 60f) % 360f, (hsl[1] * 0.4f).coerceAtMost(1f), (hsl[2] * 0.7f).coerceAtMost(1f))
                WallpaperColorSet(dominant, sec, ter)
            } else {
                WallpaperColorSet(null, null, null)
            }
        } catch (_: Exception) { WallpaperColorSet(null, null, null) }
    }

    /**
     * 从壁纸提取用户选择的色源颜色，构建完整调色板。
     * 接入对比度预设/高级设置。
     */
    private fun buildWallpaperBasedPalette(ctx: Context): Palette {
        return try {
            val sourceColor = getWallpaperSourceColor(ctx)
            if (sourceColor != null && sourceColor != 0) {
                buildTonalPaletteFromSource(sourceColor, ctx)
            } else {
                ALL[0]
            }
        } catch (_: Exception) {
            ALL[0]
        }
    }

    /** 将颜色去饱和至接近中性（保留极淡色调） */
    private fun deriveNeutral(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF, hsv
        )
        return buildHsvColor(hsv[0], 0.04f, hsv[2]) // 仅保留 4% 饱和度
    }

    /** 将颜色部分去饱和（中性变体） */
    private fun deriveNeutralVariant(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF, hsv
        )
        return buildHsvColor(hsv[0], 0.12f, hsv[2]) // 保留 12% 饱和度
    }

    /**
     * 从源色构建 Material You 风格的色调梯度调色板。
     * 使用 HSV 色彩空间控制饱和度 (S) 和明度 (V)，
     * 为每个颜色角色独立配置以获得最佳的层次感和可读性。
     */
    private fun buildTonalPaletteFromSource(source: Int, ctx: Context): Palette {
        val r = (source shr 16) and 0xFF
        val g = (source shr 8) and 0xFF
        val b = source and 0xFF
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val rawSat = hsv[1].coerceAtLeast(0.25f)  // 最低 25% 饱和度确保可见

        // 饱和度增强（高级设置）
        val satBoost = if (SPUtil.getWidgetDynamicAdvanced(ctx)) {
            SPUtil.getDynAdvSatBoost(ctx) / 100f
        } else 1f
        val baseSat = (rawSat * satBoost).coerceIn(0.20f, 1f)

        val contrast = SPUtil.getWidgetDynamicContrast(ctx)
        val advanced = SPUtil.getWidgetDynamicAdvanced(ctx)

        // ── 亮度参数 ──
        val surfaceL: Float
        val txtPriL: Float
        val txtSecL: Float
        val accentL: Float
        val surfDarkL: Float
        val txtPriDarkL: Float
        val txtSecDarkL: Float
        val accentDarkL: Float

        if (advanced) {
            surfaceL    = SPUtil.getDynAdvLightBg(ctx) / 100f
            txtPriL     = SPUtil.getDynAdvLightTxt(ctx) / 100f
            txtSecL     = (txtPriL + 0.22f).coerceAtMost(0.50f)
            accentL     = (txtPriL + 0.28f).coerceIn(0.25f, 0.60f)
            surfDarkL   = SPUtil.getDynAdvDarkBg(ctx) / 100f
            txtPriDarkL = SPUtil.getDynAdvDarkTxt(ctx) / 100f
            txtSecDarkL = (txtPriDarkL - 0.18f).coerceAtLeast(0.55f)
            accentDarkL = (txtPriDarkL - 0.12f).coerceIn(0.60f, 0.92f)
        } else {
            surfaceL    = when (contrast) { 0 -> 0.95f; 2 -> 0.98f; else -> 0.97f }
            txtPriL     = when (contrast) { 0 -> 0.20f; 2 -> 0.06f; else -> 0.12f }
            txtSecL     = when (contrast) { 0 -> 0.42f; 2 -> 0.28f; else -> 0.35f }
            accentL     = when (contrast) { 0 -> 0.50f; 2 -> 0.36f; else -> 0.42f }
            surfDarkL   = when (contrast) { 0 -> 0.12f; 2 -> 0.05f; else -> 0.08f }
            txtPriDarkL = when (contrast) { 0 -> 0.85f; 2 -> 0.96f; else -> 0.90f }
            txtSecDarkL = when (contrast) { 0 -> 0.65f; 2 -> 0.80f; else -> 0.72f }
            accentDarkL = when (contrast) { 0 -> 0.70f; 2 -> 0.85f; else -> 0.78f }
        }

        val cardL       = (surfaceL - 0.03f).coerceAtLeast(0.85f)
        val divL        = (surfaceL - 0.09f).coerceIn(0.80f, 0.92f)
        val accentSecL  = (accentL + 0.20f).coerceAtMost(0.72f)
        val cardDarkL   = (surfDarkL + 0.07f).coerceAtMost(0.25f)
        val divDarkL    = (surfDarkL + 0.16f).coerceIn(0.18f, 0.32f)
        val accentSecDarkL = (accentDarkL - 0.20f).coerceAtLeast(0.45f)

        // ── 饱和度乘数（各角色独立）──
        val surfSM  = 0.10f
        val txtSM   = if (advanced) 0.55f else when (contrast) { 0 -> 0.50f; 2 -> 0.75f; else -> 0.60f }
        val accSM   = if (advanced) 0.95f else when (contrast) { 0 -> 0.80f; 2 -> 1.0f; else -> 0.95f }
        val accSecSM = 0.55f
        val darkSurfSM = 0.20f
        val darkTxtSM  = if (advanced) 0.35f else when (contrast) { 0 -> 0.25f; 2 -> 0.45f; else -> 0.35f }
        val darkAccSM  = if (advanced) 0.85f else when (contrast) { 0 -> 0.65f; 2 -> 0.95f; else -> 0.85f }

        return Palette(
            id = -2,
            name = "动态配色",
            // 浅色模式
            pageBgLight         = buildHsvColor(h, baseSat * surfSM, surfaceL),
            cardBgLight         = buildHsvColor(h, baseSat * surfSM * 1.2f, cardL),
            textPrimaryLight    = buildHsvColor(h, baseSat * txtSM, txtPriL),
            textSecondaryLight  = buildHsvColor(h, baseSat * txtSM * 0.9f, txtSecL),
            dividerLight        = buildHsvColor(h, baseSat * surfSM, divL),
            accentLight         = buildHsvColor(h, baseSat * accSM, accentL),
            accentSecondaryLight = buildHsvColor(h, baseSat * accSecSM, accentSecL),
            btnBgLight          = buildHsvColor(h, baseSat * accSM, accentL),
            iconTintLight       = buildHsvColor(h, baseSat * accSM, accentL),
            // 深色模式
            pageBgDark          = buildHsvColor(h, baseSat * darkSurfSM, surfDarkL),
            cardBgDark          = buildHsvColor(h, baseSat * darkSurfSM * 1.2f, cardDarkL),
            textPrimaryDark     = buildHsvColor(h, baseSat * darkTxtSM, txtPriDarkL),
            textSecondaryDark   = buildHsvColor(h, baseSat * darkTxtSM * 1.1f, txtSecDarkL),
            dividerDark         = buildHsvColor(h, baseSat * darkSurfSM * 0.8f, divDarkL),
            accentDark          = buildHsvColor(h, baseSat * darkAccSM, accentDarkL),
            accentSecondaryDark = buildHsvColor(h, baseSat * accSecSM, accentSecDarkL),
            btnBgDark           = buildHsvColor(h, baseSat * darkAccSM, accentDarkL),
            iconTintDark        = buildHsvColor(h, baseSat * darkAccSM, accentDarkL),
            // 数据高亮（使用强调色级饱和度与亮度，与 textPrimary 区分）
            dataHighlightLight  = buildHsvColor(h, baseSat * accSM, accentL),
            dataHighlightDark   = buildHsvColor(h, baseSat * darkAccSM, accentDarkL),
        )
    }

    /**
     * 从 HSV 分量构建 ARGB 颜色。
     * @param h 色相 0-360
     * @param s 饱和度 0-1
     * @param v 明度 0-1
     */
    private fun buildHsvColor(h: Float, s: Float, v: Float): Int {
        return android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
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

    /**
     * 直接判断系统暗色模式，不受应用主题设置影响。
     * 用于动态配色独立颜色路径——当动态取色开启时，
     * 小组件应跟随系统而非应用主题。
     */
    fun isSystemDark(ctx: Context): Boolean {
        return try {
            val uiModeMgr = ctx.getSystemService(UiModeManager::class.java)!!
            uiModeMgr.nightMode == UiModeManager.MODE_NIGHT_YES
        } catch (_: Exception) {
            val nightMode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
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
