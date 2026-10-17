package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放/双击放大/拖动平移的图片视图（照片浏览器大图页用）。
 *
 * 变换采用双矩阵合成（同 PhotoView 思路）：[baseMatrix] 把 drawable 按视图尺寸 fitCenter
 * 摆正，[suppMatrix] 承载用户缩放/平移（作用在视图坐标系），显示矩阵 = suppMatrix ∘ baseMatrix。
 * 用户缩放倍率即 suppMatrix 的缩放分量，限定在 [MIN_SCALE]~[MAX_SCALE]（1 = fitCenter 原始大小）。
 *
 * 与 ViewPager2（左右滑页）协作：未放大时不拦截手势，父容器照常翻页；放大后（或双指捏合中）
 * 经 requestDisallowInterceptTouchEvent 禁止父容器拦截，单指拖动变为平移图片。
 * 平移/缩放后即时夹取（[clampTranslation]）：图小于视图则居中，大于视图则边缘不得缩进视图内。
 *
 * 换新图（setImageBitmap/setImageDrawable 等）自动复位缩放，适配 RecyclerView 页复用。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** fitCenter 基础矩阵：drawable 原始尺寸 → 视图内等比居中 */
    private val baseMatrix = Matrix()

    /** 用户变换矩阵（视图坐标系）：缩放 + 平移 */
    private val suppMatrix = Matrix()

    /** 合成显示矩阵（base 与 supp 的合成结果，赋给 ImageView.imageMatrix） */
    private val drawMatrix = Matrix()

    private val matrixValues = FloatArray(9)
    private val displayRect = RectF()

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        // MATRIX 模式下 ImageView 不再自动适配，缩放/居中全部由本类矩阵接管
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // 以捏合焦点为中心缩放；结果倍率夹取到 [MIN_SCALE, MAX_SCALE]
                    val target = (currentScale() * detector.scaleFactor)
                        .coerceIn(MIN_SCALE, MAX_SCALE)
                    val factor = target / currentScale()
                    suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    clampTranslation()
                    applyMatrix()
                    return true
                }
            })

        gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale() > MIN_SCALE) {
                        resetZoom()
                    } else {
                        suppMatrix.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.x, e.y)
                        clampTranslation()
                        applyMatrix()
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
                ): Boolean {
                    // 仅放大后单指平移；捏合进行中交给 scaleDetector，未放大时留给父容器翻页
                    if (scaleDetector.isInProgress || currentScale() <= MIN_SCALE) return false
                    suppMatrix.postTranslate(-distanceX, -distanceY)
                    clampTranslation()
                    applyMatrix()
                    return true
                }
            })

        setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                // 捏合开始或已放大：禁止 ViewPager2 拦截，手势归本视图（翻页/平移二选一）
                MotionEvent.ACTION_POINTER_DOWN ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_DOWN ->
                    parent?.requestDisallowInterceptTouchEvent(currentScale() > MIN_SCALE)
                else -> Unit
            }
            true
        }
    }

    /** 当前用户缩放倍率（1 = fitCenter 原始大小） */
    fun currentScale(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** 复位到 fitCenter 原始大小（换图/双击还原时调用） */
    fun resetZoom() {
        suppMatrix.reset()
        applyMatrix()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // RecyclerView 页复用换图：缩放态复位并重建基础矩阵
        suppMatrix.reset()
        rebuildBaseMatrix()
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBaseMatrix()
        clampTranslation()
        applyMatrix()
    }

    /** 重算 fitCenter 基础矩阵：drawable 等比缩放至视图内并居中（视图/图像尺寸未就绪时置单位阵） */
    private fun rebuildBaseMatrix() {
        baseMatrix.reset()
        val d = drawable ?: return
        val viewW = (width - paddingLeft - paddingRight).toFloat()
        val viewH = (height - paddingTop - paddingBottom).toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = minOf(viewW / dw, viewH / dh)
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(
            (viewW - dw * scale) / 2f + paddingLeft,
            (viewH - dh * scale) / 2f + paddingTop
        )
    }

    /**
     * 夹取平移量：显示区域小于视图的轴居中，大于视图的轴保证边缘盖满（不得露出空白）。
     * 修正量直接叠加进 [suppMatrix]，避免用户把图拖出可视范围。
     */
    private fun clampTranslation() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        computeDisplayMatrix()
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(displayRect)

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val rectW = displayRect.width()
        val rectH = displayRect.height()
        val dx = if (rectW <= viewW) {
            (viewW - rectW) / 2f - displayRect.left
        } else {
            when {
                displayRect.left > 0f -> -displayRect.left
                displayRect.right < viewW -> viewW - displayRect.right
                else -> 0f
            }
        }
        val dy = if (rectH <= viewH) {
            (viewH - rectH) / 2f - displayRect.top
        } else {
            when {
                displayRect.top > 0f -> -displayRect.top
                displayRect.bottom < viewH -> viewH - displayRect.bottom
                else -> 0f
            }
        }
        if (dx != 0f || dy != 0f) suppMatrix.postTranslate(dx, dy)
    }

    /** 合成显示矩阵：先 base（fitCenter）再叠加 supp（用户变换，视图坐标系） */
    private fun computeDisplayMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
    }

    private fun applyMatrix() {
        computeDisplayMatrix()
        imageMatrix = drawMatrix
    }

    companion object {
        /** 最小缩放倍率：1 = fitCenter 原始大小，不允许缩得更小 */
        private const val MIN_SCALE = 1f

        /** 最大缩放倍率（2048px 解码上限下 8 倍仍可辨认细节） */
        private const val MAX_SCALE = 8f

        /** 双击放大目标倍率 */
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
