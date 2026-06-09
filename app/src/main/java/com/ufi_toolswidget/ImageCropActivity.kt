package com.ufi_toolswidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.SimpleCropView
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.ufi_toolswidget.util.ToastStyle
import com.ufi_toolswidget.util.ToastUtil

/**
 * 背景图片裁切页面。
 */
class ImageCropActivity : AppCompatActivity() {

    private lateinit var cropView: SimpleCropView
    private var sourceUri: Uri? = null
    private var targetW: Int = 1080
    private var targetH: Int = 1920
    private var saveSubDir: String = "widget_bg"
    private var saveFileName: String = "custom_bg.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        // 应用主题
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)

        cropView = findViewById(R.id.crop_view)
        sourceUri = intent.data
        targetW = intent.getIntExtra("targetW", 1080)
        targetH = intent.getIntExtra("targetH", 1920)
        saveSubDir = intent.getStringExtra("saveSubDir") ?: "widget_bg"
        saveFileName = intent.getStringExtra("saveFileName") ?: "custom_bg.jpg"

        if (sourceUri == null) {
            finish()
            return
        }

        loadSourceImage()

        findViewById<View>(R.id.btn_cancel).setOnClickListener { finish() }

        // 设置确定按钮主题色
        findViewById<MaterialButton>(R.id.btn_done).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeColors.accent(this@ImageCropActivity))
            setOnClickListener { performCrop() }
        }
    }

    private fun loadSourceImage() {
        try {
            val bitmap: Bitmap? = try {
                contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (_: Exception) {
                // contentResolver 失败时尝试直接 FileInputStream（兼容 file:// URI）
                val path = sourceUri?.path
                if (path != null && File(path).exists()) {
                    FileInputStream(File(path)).use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } else null
            }
            if (bitmap != null) {
                cropView.setImageBitmap(bitmap, targetW, targetH)
            } else {
                throw Exception("Bitmap decode failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "图片加载失败")
            finish()
        }
    }

    private fun performCrop() {
        val cropped = cropView.getCroppedBitmap(targetW, targetH)
        if (cropped == null) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "裁切失败")
            return
        }

        // 保存到内部存储（filesDir/{saveSubDir}/{saveFileName}），确保 Widget 进程可访问
        try {
            val dir = File(filesDir, saveSubDir)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, saveFileName)

            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val resultIntent = Intent().apply {
                putExtra("cropped_file_path", file.absolutePath)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "保存失败")
        }
    }
}
