package com.zys.mobilemap.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
        val orientation = readOrientation(photoFile)

        val decoded = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return false
        val oriented = applyExifOrientation(decoded, orientation)
        // applyExifOrientation 可能返回新位图，此时原图须回收
        if (oriented !== decoded) decoded.recycle()

        // 失败时 ensureMutable 已回收 oriented，此处直接返回即可
        val working = ensureMutable(oriented) ?: return false

        drawWatermark(working, latitude, longitude, capturedAt)

        val saved = writeJpeg(photoFile, working)
        if (!working.isRecycled) working.recycle()
        if (!saved) return false

        resetOrientation(photoFile)
        return true
    }

    /** 读取 EXIF 方向；无 EXIF 信息或读取失败时按正常方向处理 */
    private fun readOrientation(photoFile: File): Int = try {
        ExifInterface(photoFile.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (ignored: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 取得可绘制的位图：本身可变则原样返回，否则复制一份；
     * 复制失败（内存不足）时回收 [source] 并返回 null。
     */
    private fun ensureMutable(source: Bitmap): Bitmap? {
        if (source.isMutable) return source
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val copy = source.copy(config, true)
        if (copy == null) {
            if (!source.isRecycled) source.recycle()
            return null
        }
        if (copy !== source && !source.isRecycled) source.recycle()
        return copy
    }

    /** 在左下角绘制两行水印文字，衬半透明圆角底板 */
    private fun drawWatermark(bitmap: Bitmap, latitude: Double, longitude: Double, capturedAt: Long) {
        val canvas = Canvas(bitmap)
        val minEdge = minOf(bitmap.width, bitmap.height).toFloat()
        // 字号与内边距随照片短边等比缩放，并各设像素下限，保证小图上也清晰可读
        val textSize = maxOf(TEXT_SIZE_MIN_PX, minEdge * TEXT_SIZE_RATIO)
        val padding = maxOf(PADDING_MIN_PX, minEdge * PADDING_RATIO)

        val coordinateLine = "%.6f, %.6f".format(Locale.US, latitude, longitude)
        val timeLine = TIME_FORMAT.format(Locale.getDefault(), Date(capturedAt))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
        }

        val coordinateBounds = Rect()
        val timeBounds = Rect()
        textPaint.getTextBounds(coordinateLine, 0, coordinateLine.length, coordinateBounds)
        textPaint.getTextBounds(timeLine, 0, timeLine.length, timeBounds)

        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent
        // 第二行贴底，第一行在其上方“一行高 + 行距”处
        val secondLineY = bitmap.height - padding - fontMetrics.descent
        val firstLineY = secondLineY - lineHeight - textSize * LINE_SPACING_RATIO

        // 底板包住两行文字，四周各留相对字号的内边距
        val bgPadH = textSize * BG_PADDING_H_RATIO
        val bgPadV = textSize * BG_PADDING_V_RATIO
        val bgRadius = textSize * BG_RADIUS_RATIO
        val textWidth = maxOf(coordinateBounds.width().toFloat(), timeBounds.width().toFloat())
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG_COLOR }
        canvas.drawRoundRect(
            padding - bgPadH,
            firstLineY + fontMetrics.ascent - bgPadV,
            padding + textWidth + bgPadH,
            secondLineY + fontMetrics.descent + bgPadV,
            bgRadius, bgRadius, bgPaint
        )
        canvas.drawText(coordinateLine, padding, firstLineY, textPaint)
        canvas.drawText(timeLine, padding, secondLineY, textPaint)
    }

    /** 以 JPEG 覆盖写回照片；IO 失败返回 false（不抛出，调用方据此放弃后续 EXIF 写回） */
    private fun writeJpeg(photoFile: File, bitmap: Bitmap): Boolean = try {
        FileOutputStream(photoFile, false).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
    } catch (ignored: IOException) {
        false
    }

    /** 位图已按 EXIF 方向摆正，故把方向标记重写为 NORMAL，避免相册再次旋转 */
    private fun resetOrientation(photoFile: File) {
        try {
            ExifInterface(photoFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        } catch (ignored: IOException) {
            // 保持照片可用，忽略 EXIF 写回失败
        }
    }

    /**
     * 根据 EXIF 方向信息应用图像旋转和翻转；无需变换或变换失败时原样返回 [source]。
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
        } catch (ignored: IllegalArgumentException) {
            // 变换参数非法（如尺寸越界）：退回未摆正的原图，保证水印仍能加上
            source
        }
    }

    /** JPEG 写回质量（%）：兼顾清晰度与照片体积 */
    private const val JPEG_QUALITY = 92

    /** 字号 / 内边距：随照片短边等比缩放，并各设像素下限 */
    private const val TEXT_SIZE_MIN_PX = 28f
    private const val TEXT_SIZE_RATIO = 0.035f
    private const val PADDING_MIN_PX = 16f
    private const val PADDING_RATIO = 0.025f

    /** 行距与底板内边距、圆角（均相对字号） */
    private const val LINE_SPACING_RATIO = 0.25f
    private const val BG_PADDING_H_RATIO = 0.30f
    private const val BG_PADDING_V_RATIO = 0.22f
    private const val BG_RADIUS_RATIO = 0.18f

    /** 水印底板底色：半透明黑 */
    private const val BG_COLOR = 0x88000000.toInt()

    /** 时间行格式：yyyy-MM-dd HH:mm:ss */
    private const val TIME_FORMAT = "%1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS"
}
