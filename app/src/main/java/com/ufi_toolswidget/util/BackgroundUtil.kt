package com.ufi_toolswidget.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.view.WindowCompat

/**
 * 全局窗口背景管理器。
 * 具备内存缓存机制，确保切换页面时背景更平滑。
 */
object BackgroundUtil {

    private var cachedBitmap: Bitmap? = null
    private var cachedUri: String? = null

    /**
     * 初始化 Activity 的 UI 基础状态。
     */
    fun initActivity(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        applyWindowBackground(activity)
    }

    /**
     * 应用并刷新窗口背景。
     */
    fun applyWindowBackground(activity: Activity) {
        val uriStr = SPUtil.getBgImageUri(activity)
        
        if (uriStr.isNotBlank()) {
            // 检查缓存
            if (uriStr == cachedUri && cachedBitmap != null && !cachedBitmap!!.isRecycled) {
                activity.window.decorView.background = BitmapDrawable(activity.resources, cachedBitmap)
                return
            }

            // 加载并缓存
            try {
                val uri = Uri.parse(uriStr)
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565 // 节省内存
                    }
                    val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    if (bitmap != null) {
                        // 清理旧缓存
                        cachedBitmap?.let { if (!it.isRecycled && it != bitmap) it.recycle() }
                        
                        cachedBitmap = bitmap
                        cachedUri = uriStr
                        activity.window.decorView.background = BitmapDrawable(activity.resources, bitmap)
                    }
                } ?: throw Exception("Stream is null")
                return
            } catch (e: Exception) {
                e.printStackTrace()
                cachedUri = null
                cachedBitmap = null
            }
        }
        
        // 回退到纯色
        val color = ThemeColors.pageBg(activity)
        activity.window.decorView.setBackgroundColor(color)
    }

    /** 清除缓存 */
    fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedUri = null
    }
}
