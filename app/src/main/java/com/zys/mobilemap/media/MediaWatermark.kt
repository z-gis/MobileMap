package com.zys.mobilemap.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.Locale

/**
 * 照片坐标水印：在照片左下角绘制"经纬度 + 拍摄时间"两行文字（半透明圆角底板），
 * 参照上一版本实现；绘制前按 EXIF 方向纠正位图，绘制后重写 EXIF 方向为 NORMAL。
 */
object MediaWatermark {

    /**
     * 为照片添加坐标水印（原地覆盖写回）。
     *
     * @return 是否成功
     */
    fun addCoordinateWatermark(photoFile: File, latitude: Double, longitude: Double, capturedAt: Long): Boolean {
        var exif: ExifInterface? = null
        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            exif = ExifInterface(photoFile.absolutePath)
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (ignored: IOException) {
            // 无 EXIF 信息按正常方向处理
        }

        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return false

        val orientedBitmap = applyExifOrientation(bitmap, orientation)
        if (orientedBitmap !== bitmap) {
            bitmap.recycle()
        }

        var workingBitmap = orientedBitmap
        if (!workingBitmap.isMutable) {
            val config = workingBitmap.config ?: Bitmap.Config.ARGB_8888
            val mutableCopy = workingBitmap.copy(config, true)
            if (mutableCopy == null) {
                if (!workingBitmap.isRecycled) workingBitmap.recycle()
                return false
            }
            if (mutableCopy !== workingBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = mutableCopy
        }

        val canvas = Canvas(workingBitmap)
        val minEdge = minOf(workingBitmap.width, workingBitmap.height).toFloat()
        val textSize = maxOf(28f, minEdge * 0.035f)
        val padding = maxOf(16f, minEdge * 0.025f)

        val coordinateLine = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        val timeLine = String.format(Locale.getDefault(), "%1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS", Date(capturedAt))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = -0x1 // 0xFFFFFFFF
        textPaint.textSize = textSize
        textPaint.style = Paint.Style.FILL

        val coordinateBounds = Rect()
        val timeBounds = Rect()
        textPaint.getTextBounds(coordinateLine, 0, coordinateLine.length, coordinateBounds)
        textPaint.getTextBounds(timeLine, 0, timeLine.length, timeBounds)
        val textX = padding
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent
        val lineSpacing = textSize * 0.25f
        val secondLineY = workingBitmap.height - padding - fontMetrics.descent
        val firstLineY = secondLineY - lineHeight - lineSpacing

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = 0x88000000.toInt()
        val bgPadH = textSize * 0.30f
        val bgPadV = textSize * 0.22f
        val bgLeft = textX - bgPadH
        val bgTop = firstLineY + fontMetrics.ascent - bgPadV
        val textWidth = maxOf(coordinateBounds.width().toFloat(), timeBounds.width().toFloat())
        val bgRight = textX + textWidth + bgPadH
        val bgBottom = secondLineY + fontMetrics.descent + bgPadV
        val bgRadius = textSize * 0.18f
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, bgRadius, bgRadius, bgPaint)
        canvas.drawText(coordinateLine, textX, firstLineY, textPaint)
        canvas.drawText(timeLine, textX, secondLineY, textPaint)

        var saved = false
        try {
            FileOutputStream(photoFile, false).use { fos ->
                saved = workingBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } catch (ignored: IOException) {
            saved = false
        }

        if (!workingBitmap.isRecycled) {
            workingBitmap.recycle()
        }

        if (!saved) return false

        try {
            val newExif = ExifInterface(photoFile.absolutePath)
            newExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            newExif.saveAttributes()
        } catch (ignored: IOException) {
            // 保持照片可用，忽略 EXIF 写回失败
        }
        return true
    }

    /**
     * 根据 EXIF 方向信息应用图像旋转和翻转。
     */
    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            else -> return source
        }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: IllegalArgumentException) {
            source
        }
    }
}
