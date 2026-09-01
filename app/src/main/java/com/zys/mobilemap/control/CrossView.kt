package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CrossView : View {
    private val paint = Paint()

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr) {
        init()
    }

    protected fun init() {
        paint.setColor(Color.RED)
        paint.setStrokeWidth(3f)
    }

    override fun onDraw(canvas: Canvas) {
        val w: Int = this.getWidth()
        val h: Int = this.getHeight()
        canvas.drawLine((w / 2).toFloat(), 0f, (w / 2).toFloat(), h.toFloat(), paint) // 竖线
        canvas.drawLine(0f, (h / 2).toFloat(), w.toFloat(), (h / 2).toFloat(), paint) // 横线
    }
}
