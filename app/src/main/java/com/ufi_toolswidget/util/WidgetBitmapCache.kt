package com.ufi_toolswidget.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.Log

/**
 * 小组件背景 Bitmap 缓存，减少重复分配（~2.5MB/次）带来的 GC 压力。
 *
 * 两类缓存：
 * - solidColorBitmap：纯色圆角 Bitmap（按颜色缓存 1 槽，刷新时颜色通常不变）
 * - imageBgBitmap：    自定义背景图 Bitmap（按 URI+尺寸缓存 1 槽）
 */
object WidgetBitmapCache {

    private const val TAG = "WidgetBitmapCache"
    private const val TARGET_W = 640
    private const val TARGET_H = 320

    // ── 纯色 Bitmap 缓存 ──
    private var cachedColor: Int = Int.MIN_VALUE
    private var cachedSolidBitmap: Bitmap? = null

    // ── 自定义背景图 Bitmap 缓存 ──
    private var cachedImageUri: String = ""
    private var cachedImageBitmap: Bitmap? = null

    /**
     * 获取或创建纯色圆角 Bitmap。颜色未变时命中缓存，零分配。
     */
    @Synchronized
    fun getOrCreateSolidBitmap(context: Context, color: Int, cornerRadiusDp: Float): Bitmap? {
        if (cachedColor == color && cachedSolidBitmap != null && cachedSolidBitmap?.isRecycled == false) {
            return cachedSolidBitmap
        }
        recycleSolid()
        val bmp = createSolidRoundedBitmap(context, color, cornerRadiusDp)
        cachedSolidBitmap = bmp
        cachedColor = color
        return bmp
    }

    /**
     * 获取或创建自定义背景图 Bitmap。URI 未变时命中缓存，零分配。
     */
    @Synchronized
    fun getOrCreateImageBitmap(context: Context, uri: String, cornerRadiusDp: Float): Bitmap? {
        if (uri.isEmpty()) return null
        val key = "$uri|$cornerRadiusDp"
        if (key == cachedImageUri && cachedImageBitmap != null && cachedImageBitmap?.isRecycled == false) {
            return cachedImageBitmap
        }
        recycleImage()
        val bmp = loadResizedBitmap(context, uri, cornerRadiusDp)
        cachedImageBitmap = bmp
        cachedImageUri = key
        return bmp
    }

    /** 回收所有缓存（主题/配置变更后调用） */
    @Synchronized
    fun evictAll() {
        recycleSolid()
        recycleImage()
    }

    // ── 内部方法 ──

    private fun recycleSolid() {
        cachedSolidBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedSolidBitmap = null
        cachedColor = Int.MIN_VALUE
    }

    private fun recycleImage() {
        cachedImageBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedImageBitmap = null
        cachedImageUri = ""
    }

    /**
     * 生成指定颜色的圆角纯色 Bitmap。目标 ≤640×320，ARGB_8888 ≤820KB。
     */
    private fun createSolidRoundedBitmap(context: Context, color: Int, cornerRadiusDp: Float): Bitmap? {
        return try {
            val source = Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888)
            source.eraseColor(color)
            applyRoundedCorners(context, source, cornerRadiusDp)
        } catch (e: Exception) {
            Log.e(TAG, "createSolidRoundedBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * 安全加载、缩放并圆角化用户选择的背景图（防止 RemoteViews 超 1MB 崩溃）。
     * 支持 content:// URI 和绝对文件路径两种来源。
     */
    private fun loadResizedBitmap(context: Context, uriString: String, cornerRadiusDp: Float = 0f): Bitmap? {
        return try {
            // 判断是文件路径还是 URI
            val isFilePath = !uriString.startsWith("content://") && !uriString.startsWith("file://")
            val uri = if (isFilePath) Uri.fromFile(java.io.File(uriString)) else Uri.parse(uriString)

            // 1. 获取图片原始尺寸（仅解码尺寸），使用 use{} 确保流关闭
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            } ?: run {
                // contentResolver 失败时尝试直接 FileInputStream（兼容内部存储文件路径）
                if (isFilePath && java.io.File(uriString).exists()) {
                    java.io.FileInputStream(java.io.File(uriString)).use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream, null, options)
                    }
                }
            } ?: return null

            // 2. 计算缩放比例（必须是 2 的幂，Android 文档要求）
            var inSampleSize = 1
            if (options.outWidth > TARGET_W || options.outHeight > TARGET_H) {
                val ratioW = (options.outWidth + TARGET_W - 1) / TARGET_W
                val ratioH = (options.outHeight + TARGET_H - 1) / TARGET_H
                inSampleSize = Integer.highestOneBit(maxOf(ratioW, ratioH, 1))
            }

            // 3. 按比例加载，使用 use{} 确保流关闭
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { finalStream ->
                android.graphics.BitmapFactory.decodeStream(finalStream, null,
                    android.graphics.BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })
            } ?: run {
                // 兜底：直接 FileInputStream
                if (isFilePath && java.io.File(uriString).exists()) {
                    java.io.FileInputStream(java.io.File(uriString)).use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream, null,
                            android.graphics.BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })
                    }
                } else null
            } ?: return null

            // 4. 应用圆角
            val rounded = if (cornerRadiusDp > 0f) {
                applyRoundedCorners(context, rawBitmap, cornerRadiusDp)
            } else rawBitmap

            // 5. 安全阈值：若 Bitmap 仍超 1MB → 等比缩放到安全尺寸
            val maxBytes = 1000 * 1024
            if (rounded != null && !rounded.isRecycled && rounded.byteCount > maxBytes) {
                val scale = kotlin.math.sqrt(maxBytes.toFloat() / rounded.byteCount)
                val sw = (rounded.width * scale).toInt().coerceAtLeast(1)
                val sh = (rounded.height * scale).toInt().coerceAtLeast(1)
                val shrunk = Bitmap.createScaledBitmap(rounded, sw, sh, true)
                if (shrunk !== rounded) rounded.recycle()
                shrunk
            } else rounded
        } catch (e: Exception) {
            Log.e(TAG, "loadResizedBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * 给 Bitmap 添加圆角，crop 到圆角矩形区域。
     * radiusDp <= 0 时直接返回原始 Bitmap。
     */
    private fun applyRoundedCorners(context: Context, source: Bitmap, radiusDp: Float): Bitmap? {
        if (radiusDp <= 0f) return source
        if (source.isRecycled) {
            Log.w(TAG, "applyRoundedCorners: source bitmap is already recycled")
            return null  // 返回 null 而非已回收的 bitmap，避免下游使用时崩溃
        }
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "applyRoundedCorners: invalid bitmap size ${w}x${h}")
            return source
        }
        val radius = (radiusDp * context.resources.displayMetrics.density)
            .coerceAtMost((w.coerceAtMost(h) / 2f))

        return try {
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, 0f, 0f, paint)
            if (source !== output) source.recycle()
            output
        } catch (e: Exception) {
            Log.e(TAG, "applyRoundedCorners failed: ${e.message}, returning original")
            source
        }
    }
}
