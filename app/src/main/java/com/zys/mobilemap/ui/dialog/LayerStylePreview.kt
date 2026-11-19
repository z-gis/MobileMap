package com.zys.mobilemap.ui.dialog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.vector.FeatureIcons

/**
 * 图层样式预览统一控件（对应鸿蒙 components/LayerStylePreview.ets）：
 * 栅格由宿主静态图标承载，本控件只画矢量——点（实际标识图标）/ 线（微折线）/ 面（填充+描边），
 * 标注字段已设置时按标注样式叠绘字段名：字号 16×缩放（同 native 渲染基准）、轮廓为 8 方向 1dp
 * 偏移掩膜（同 native halo）；KML 有效标注样式与加图入地图同口径（Name 缺省 + 白晕 + 开关门控）。
 *
 * 颜色优先级：文档样式 > 地图实际渲染色 > 默认渲染色（与整层样式对话框回显同序）；
 * 线层第二色/线宽走线通道（默认橙、宽 2），面描边默认白/宽 1 对线层是误导。
 * 颜色串统一 '#AARRGGBB'；坏值回退不中断绘制（document.json 可能被手改坏）。
 */
object LayerStylePreview {

    const val DEFAULT_SHAPE_FILL = "#664A8FE3"
    const val DEFAULT_SHAPE_OUTLINE = "#FFFFFFFF"
    const val DEFAULT_SHAPE_LINE = "#FFE66B33"
    /** 默认白字（同 MapStyleColors.DEFAULT_LABEL_COLOR / native 渲染默认） */
    const val DEFAULT_LABEL_COLOR = "#FFFFFFFF"
    const val DEFAULT_LABEL_OUTLINE_COLOR = "#FF000000"

    /** 轮廓掩膜 8 方向（同 native Renderer.cpp kDir）：偏移 1dp */
    private val HALO_DIRS = arrayOf(
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
        floatArrayOf(0.7071f, 0.7071f), floatArrayOf(-0.7071f, 0.7071f),
        floatArrayOf(0.7071f, -0.7071f), floatArrayOf(-0.7071f, -0.7071f)
    )

    /**
     * 有效标注字段（与 MapLayerManager.addVectorLayerNative 同口径）：
     * KML 受 showKmlLabel 门控、缺省 Name；非 KML 仅文档显式值。宿主取样本文本时同用此解析。
     */
    fun effectiveLabelField(style: VectorStyle?, isKml: Boolean, showKmlLabel: Boolean): String = when {
        isKml && !showKmlLabel -> ""
        isKml -> style?.labelField?.trim()?.takeIf { it.isNotEmpty() } ?: "Name"
        else -> style?.labelField?.trim() ?: ""
    }

    /**
     * 按整层样式绘制预览位图。
     * @param geom 几何类型（0 点 / 1 线 / 2 面 / -1 未知或混合，画面块）
     * @param renderColors 地图实际渲染色（填充/描边），未加载为 null
     * @param isKml KML/KMZ 层：标注有效样式按加图同口径解析（Name 缺省 + 白晕）
     * @param showKmlLabel 文档 KML 标注开关（关则 KML 层不画标注）
     * @param labelText 标注样本值（数据首条非空注记值，同地图实际渲染内容）；空则回退字段名
     */
    fun draw(
        context: Context,
        style: VectorStyle?,
        geom: Int,
        renderColors: Pair<String?, String?>?,
        widthPx: Int,
        heightPx: Int,
        isKml: Boolean = false,
        showKmlLabel: Boolean = true,
        labelText: String? = null
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val density = context.resources.displayMetrics.density

        val fillHex = style?.fillColor ?: renderColors?.first ?: DEFAULT_SHAPE_FILL
        val outlineHex = style?.outlineColor ?: renderColors?.second
            ?: if (geom == 1) DEFAULT_SHAPE_LINE else DEFAULT_SHAPE_OUTLINE

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)

        // 线宽按文档值缩放限幅保证可见（无覆盖时线层 2 / 面层 1）
        val strokeWidth = (style?.outlineWidth?.coerceIn(1f, 10f) ?: if (geom == 1) 2f else 1f) * density
        val inset = strokeWidth / 2 + 2 * density

        if (geom == 0) {
            drawPoint(canvas, context, style, widthPx, heightPx, density)
        } else if (geom == 1) {
            drawLine(canvas, outlineHex, strokeWidth, inset, widthPx, heightPx, density)
        } else {
            drawPolygon(canvas, style, fillHex, outlineHex, strokeWidth, inset, widthPx, heightPx, density)
        }

        drawLabel(canvas, style, widthPx, heightPx, density, isKml, showKmlLabel, labelText)
        return bitmap
    }

    /** 点层示例：画该层实际使用的标识图标（iconKey → 内置 drawable，默认 pin），居中限高 */
    private fun drawPoint(
        canvas: Canvas, context: Context, style: VectorStyle?,
        widthPx: Int, heightPx: Int, density: Float
    ) {
        val d = AppCompatResources.getDrawable(context, FeatureIcons.resOf(style?.iconKey)) ?: return
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) return
        val box = 24 * density
        val scale = minOf(box / iw, box / ih, (heightPx - 4 * density) / ih)
        val w = iw * scale
        val h = ih * scale
        d.setBounds(0, 0, w.toInt(), h.toInt())
        canvas.save()
        canvas.translate((widthPx - w) / 2, (heightPx - h) / 2)
        d.draw(canvas)
        canvas.restore()
    }

    /** 线层示例：横贯预览区的微折线（无填充），线色/线宽与文档一致 */
    private fun drawLine(
        canvas: Canvas, outlineHex: String, strokeWidth: Float, inset: Float,
        widthPx: Int, heightPx: Int, density: Float
    ) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth.coerceIn(density, 4 * density)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = parseColorOr(outlineHex, Color.BLACK)
        }
        val linePath = Path()
        linePath.moveTo(inset, heightPx * 0.72f)
        linePath.lineTo(widthPx * 0.42f, heightPx * 0.30f)
        linePath.lineTo(widthPx - inset, heightPx * 0.58f)
        canvas.drawPath(linePath, linePaint)
    }

    /** 面层示例：圆角矩形填充 + 描边；填充开关关闭时只描边不填充（与渲染层同口径） */
    private fun drawPolygon(
        canvas: Canvas, style: VectorStyle?, fillHex: String, outlineHex: String,
        strokeWidth: Float, inset: Float, widthPx: Int, heightPx: Int, density: Float
    ) {
        val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.color = parseColorOr(fillHex, Color.BLACK)
        style?.fillOpacity?.let { fillPaint.alpha = (it.coerceIn(0f, 1f) * 255).toInt() }
        if (style?.fillEnabled != false) {
            canvas.drawRoundRect(rect, 4 * density, 4 * density, fillPaint)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth.coerceIn(density, 4 * density)
            color = parseColorOr(outlineHex, Color.BLACK)
        }
        canvas.drawRoundRect(rect, 4 * density, 4 * density, strokePaint)
    }

    /**
     * 标注：有效样式与加图入地图同口径（MapLayerManager.addVectorLayerNative）——
     * KML 受 showKmlLabel 门控、字段缺省 Name、轮廓缺省开 + 白晕；非 KML 仅读文档显式值。
     * 示例文本取样本值（数据首条非空注记值，同地图实际渲染内容），缺省回退字段名。
     * 字号 16dp×缩放（同 native 渲染基准，不限幅，位图边界天然裁剪）；
     * 轮廓 = 8 方向 1dp 偏移掩膜（同 native poor-man's halo），画在文字主体之下。
     */
    private fun drawLabel(
        canvas: Canvas, style: VectorStyle?, widthPx: Int, heightPx: Int, density: Float,
        isKml: Boolean, showKmlLabel: Boolean, labelText: String?
    ) {
        val labelField = effectiveLabelField(style, isKml, showKmlLabel)
        if (labelField.isEmpty()) return
        val text = labelText?.trim()?.takeIf { it.isNotEmpty() } ?: labelField
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16 * density * (style?.labelSize ?: 1f)
            color = parseColorOr(style?.labelColor ?: DEFAULT_LABEL_COLOR, Color.WHITE)
            textAlign = Paint.Align.CENTER
        }
        val cx = widthPx / 2f
        val cy = heightPx / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2
        val outlineOn = style?.labelOutline ?: isKml
        if (outlineOn) {
            val outlineHex = style?.labelOutlineColor
                ?: if (isKml) DEFAULT_LABEL_COLOR else DEFAULT_LABEL_OUTLINE_COLOR
            val halo = Paint(labelPaint).apply {
                color = parseColorOr(outlineHex, Color.BLACK)
            }
            val o = 1f * density
            for (d in HALO_DIRS) {
                canvas.drawText(text, cx + d[0] * o, cy + d[1] * o, halo)
            }
        }
        canvas.drawText(text, cx, cy, labelPaint)
    }

    /** 解析 `#AARRGGBB` 颜色串；为空或非法时回退 [fallback]，保证预览绘制不因单个坏值中断 */
    private fun parseColorOr(hex: String?, fallback: Int): Int =
        if (hex.isNullOrEmpty()) {
            fallback
        } else {
            try {
                hex.toColorInt()
            } catch (ignored: IllegalArgumentException) {
                fallback
            }
        }
}