package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 地图中心十字准星：横竖两条红线交于视图中心，指示当前地图中心点。
 * 由活动布局（activity_main.xml）直接声明，故用 @JvmOverloads 生成三个标准 View 构造函数供 XML 反射调用。
 */
class CrossView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 准星画笔：不开抗锯齿、线宽按物理像素给定，保持移植前的锐利观感 */
    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = STROKE_WIDTH_PX
    }

    override fun onDraw(canvas: Canvas) {
        // 整数除法定中心（与原实现一致，避免半像素偏移使线条发虚）
        val cx = (width / 2).toFloat()
        val cy = (height / 2).toFloat()
        canvas.drawLine(cx, 0f, cx, height.toFloat(), paint) // 竖线
        canvas.drawLine(0f, cy, width.toFloat(), cy, paint) // 横线
    }

    companion object {
        /** 准星线宽（物理像素，不按密度缩放） */
        private const val STROKE_WIDTH_PX = 3f
    }
}
