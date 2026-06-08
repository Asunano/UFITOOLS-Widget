package com.ufi_toolswidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.SimpleCropView
import com.ufi_toolswidget.util.ThemeColors
import com.ufi_toolswidget.util.ThemeUtil
import java.io.File
import java.io.FileOutputStream

/**
 * 背景图片裁切页面。
 */
class ImageCropActivity : AppCompatActivity() {

    private lateinit var cropView: SimpleCropView
    private var sourceUri: Uri? = null
    private var targetW: Int = 1080
    private var targetH: Int = 1920

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        // 应用主题
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)

        cropView = findViewById(R.id.crop_view)
        sourceUri = intent.data
        targetW = intent.getIntExtra("targetW", 1080)
        targetH = intent.getIntExtra("targetH", 1920)

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
            contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                // 如果图片非常大，建议先压缩加载以防 OOM
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                if (bitmap != null) {
                    cropView.setImageBitmap(bitmap, targetW, targetH)
                } else {
                    throw Exception("Bitmap decode failed")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun performCrop() {
        val cropped = cropView.getCroppedBitmap(targetW, targetH)
        if (cropped == null) {
            Toast.makeText(this, "裁切失败", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存到内部目录以获得持久权限
        try {
            val file = File(getExternalFilesDir("background"), "custom_bg_${System.currentTimeMillis()}.jpg")
            file.parentFile?.mkdirs()
            
            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            val resultUri = Uri.fromFile(file)
            val resultIntent = Intent().apply {
                putExtra("cropped_uri", resultUri.toString())
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
}
