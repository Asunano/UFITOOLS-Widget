package com.ufi_toolswidget.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * 全局窗口背景管理器。
 * 具备内存缓存机制，确保切换页面时背景更平滑。
 */
object BackgroundUtil {

    private var cachedBitmap: Bitmap? = null
    private var cachedUri: String? = null

    /** 用于异步加载背景图的协程作用域（不依赖 LifecycleOwner） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前正在进行的异步加载 Job，新加载启动时会取消前一个，防止并发解码导致 Bitmap 竞态 */
    @Volatile
    private var loadingJob: Job? = null

    /**
     * 初始化 Activity 的 UI 基础状态。
     * 使用异步加载背景图，避免主线程阻塞。
     */
    fun initActivity(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        applyWindowBackgroundAsync(activity)
    }

    /**
     * 应用并刷新窗口背景（同步，在主线程执行）。
     * 内部已加入 inSampleSize 下采样以控制内存占用。
     */
    fun applyWindowBackground(activity: Activity) {
        val uriStr = SPUtil.getBgImageUri(activity)

        if (uriStr.isNotBlank()) {
            // 检查缓存：命中时使用副本设置背景，避免共享引用
            if (uriStr == cachedUri && cachedBitmap != null && !cachedBitmap!!.isRecycled) {
                activity.window.decorView.background =
                    BitmapDrawable(activity.resources, cachedBitmap!!.copy(cachedBitmap!!.config ?: Bitmap.Config.RGB_565, false))
                return
            }

            // 加载并缓存
            try {
                val uri = Uri.parse(uriStr)
                val isFilePath = !uriStr.startsWith("content://") && !uriStr.startsWith("file://")
                val resolvedUri = if (isFilePath) Uri.fromFile(java.io.File(uriStr)) else uri

                val bytes = activity.contentResolver.openInputStream(resolvedUri)?.use { it.readBytes() }
                if (bytes != null) {
                    val bitmap = decodeBitmapDownsampled(bytes, activity)
                    if (bitmap != null) {
                        applyDecodedBitmap(activity, bitmap, uriStr)
                    }
                } else {
                    // contentResolver 失败时尝试直接 FileInputStream（兼容内部存储文件路径）
                    val path = if (isFilePath) uriStr else resolvedUri.path
                    if (path != null && java.io.File(path).exists()) {
                        val bytes2 = java.io.FileInputStream(java.io.File(path)).use { it.readBytes() }
                        val bitmap = decodeBitmapDownsampled(bytes2, activity)
                        if (bitmap != null) {
                            applyDecodedBitmap(activity, bitmap, uriStr)
                        } else throw Exception("Bitmap decode returned null")
                    } else throw Exception("Stream is null and no file found")
                }
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

    /**
     * 异步版本：在 IO 线程解码图片，主线程应用背景。
     * 适用于对首帧渲染时间不敏感的场景（如 Activity onResume）。
     */
    fun applyWindowBackgroundAsync(activity: Activity) {
        val uriStr = SPUtil.getBgImageUri(activity)

        if (uriStr.isNotBlank()) {
            // 缓存命中时直接在主线程设置，无需异步
            if (uriStr == cachedUri && cachedBitmap != null && !cachedBitmap!!.isRecycled) {
                activity.window.decorView.background =
                    BitmapDrawable(activity.resources, cachedBitmap!!.copy(cachedBitmap!!.config ?: Bitmap.Config.RGB_565, false))
                return
            }

            // 取消前一次异步加载，防止并发解码导致 Bitmap 竞态
            loadingJob?.cancel()
            val activityRef = WeakReference(activity)
            loadingJob = scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        val uri = Uri.parse(uriStr)
                        val isFilePath = !uriStr.startsWith("content://") && !uriStr.startsWith("file://")
                        val resolvedUri = if (isFilePath) Uri.fromFile(java.io.File(uriStr)) else uri

                        val act = activityRef.get() ?: return@withContext null
                        val bytes = act.contentResolver.openInputStream(resolvedUri)?.use { it.readBytes() }
                        if (bytes != null) {
                            decodeBitmapDownsampled(bytes, act)
                        } else {
                            val path = if (isFilePath) uriStr else resolvedUri.path
                            if (path != null && java.io.File(path).exists()) {
                                val bytes2 = java.io.FileInputStream(java.io.File(path)).use { it.readBytes() }
                                decodeBitmapDownsampled(bytes2, act)
                            } else null
                        }
                    }

                    val act = activityRef.get()
                    if (act != null && !act.isDestroyed) {
                        if (bitmap != null) {
                            applyDecodedBitmap(act, bitmap, uriStr)
                        } else {
                            val color = ThemeColors.pageBg(act)
                            act.window.decorView.setBackgroundColor(color)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    cachedUri = null
                    cachedBitmap = null
                    val act = activityRef.get()
                    if (act != null && !act.isDestroyed) {
                        val color = ThemeColors.pageBg(act)
                        act.window.decorView.setBackgroundColor(color)
                    }
                }
            }
            return
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

    // region Private helpers

    /**
     * 将解码后的 Bitmap 存入缓存，并用副本设置 decorView 背景。
     * 使用副本是为了避免 cachedBitmap 被 recycle 后 decorView 仍持有已回收引用。
     */
    private fun applyDecodedBitmap(activity: Activity, bitmap: Bitmap, uriStr: String) {
        cachedBitmap?.let { if (!it.isRecycled && it != bitmap) it.recycle() }
        cachedBitmap = bitmap
        cachedUri = uriStr
        // 使用副本设置背景，避免 shared reference 导致 recycle 后崩溃
        activity.window.decorView.background =
            BitmapDrawable(activity.resources, bitmap.copy(bitmap.config ?: Bitmap.Config.RGB_565, false))
    }

    /**
     * 两阶段解码：先用 inJustDecodeBounds 获取尺寸，
     * 计算 inSampleSize 后再解码，避免超大图片占满内存。
     *
     * @param data 图片的完整字节数据（调用方需预先将 InputStream 读入 byte[]）
     */
    private fun decodeBitmapDownsampled(data: ByteArray, activity: Activity): Bitmap? {
        // 第一阶段：仅获取原始尺寸，不分配像素内存
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, boundsOptions)

        // 计算 inSampleSize：以屏幕分辨率为目标尺寸
        val displayMetrics = activity.resources.displayMetrics
        val targetWidth = displayMetrics.widthPixels
        val targetHeight = displayMetrics.heightPixels

        val sampleSize = calculateInSampleSize(
            boundsOptions.outWidth, boundsOptions.outHeight,
            targetWidth, targetHeight
        )

        // 第二阶段：带 inSampleSize 实际解码
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
    }

    /**
     * 计算 BitmapFactory 的 inSampleSize（必须为 2 的幂次）。
     * 保证解码后的图片尺寸 >= 目标尺寸，同时尽量接近目标以节省内存。
     */
    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        targetWidth: Int, targetHeight: Int
    ): Int {
        var inSampleSize = 1
        if (srcHeight > targetHeight || srcWidth > targetWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while (halfHeight / inSampleSize >= targetHeight &&
                halfWidth / inSampleSize >= targetWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // endregion
}
