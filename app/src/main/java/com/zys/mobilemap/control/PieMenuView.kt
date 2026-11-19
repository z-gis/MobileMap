package com.zys.mobilemap.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 圆菜单（pie / radial menu）：长按地图落点后，以落点为锚在其周围弹出一圈等分排布的快捷动作按钮。
 *
 * 设计移植自 QField `QfToolButtonPie.qml`（纯 UI 浮层，不触碰地图 native 渲染），落到 Android 经典 View
 * 技术栈的实现要点：
 *  - **按钮悬浮、无环带**：动作块直接浮在遮罩上，不画环带背景（口径对齐鸿蒙 PieMenuLayer），
 *    文案向下外扩不会再被圆环边界切到；
 *  - **沿弧排布动作块**：把动作项按 `360/N` 等分顺时针分布在半径 `pathRadius` 的圆上（第 0 个在正上方），
 *    每项为「图标在上、文案在下」的整块——文案带圆角底衬，避免压在明暗不定的底图上读不出；
 *  - **顺时针逐个弹出**：[openingAngle] 由 0 补间到 360，第 i 个按钮仅当 `openingAngle >= segment*(i+1)`
 *    时才显示，视觉上即“扇区扫到哪、按钮长到哪”；
 *  - **分项矩形命中**：抬手点落在哪一项的整块热区（图标圆 + 下方文案，外扩 `hitPad`）即选中该项；
 *    加了下方文案后整块不再是扇形，故弃用极坐标 + 环带半径判定。中心孔、项间空隙与环外均为点空白收起；
 *  - **屏幕边缘避让 + 连接线**：圆心据落点钳位使整个菜单（含文案外扩，见 [setup] 预计算的
 *    [contentExtentPx]）留在屏内；若因此与落点脱开一段距离，则用虚线把菜单圆心连回落点（对齐 QField showConnectionLine）。
 *
 * 本 View 以 MATCH_PARENT 叠在地图之上（见 [PieMenu.show]），打开期间拦截全部触摸：命中按钮执行动作、
 * 其余区域收起菜单。
 *
 * 由代码动态创建（XML 直接声明亦支持，故用 @JvmOverloads 生成三构造函数）。
 */
class PieMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 可调尺寸（dp→px，density 口径同工程其余控件 resources.displayMetrics.density）──────────
    private val density = resources.displayMetrics.density

    /** 菜单外径（环带外沿直径）：对齐 QField width 上限 150dp */
    private val diameterPx = DIAMETER_DP * density
    /** 环带线宽 = 单个按钮槽位边长：对齐 QField bandWidth = toolButtonSize */
    private val bandPx = BAND_DP * density
    /** 按钮图标圆形底板相对槽位的内缩（留出按钮间隙） */
    private val buttonGapPx = BUTTON_GAP_DP * density
    /** 圆心距屏幕边的最小留白（保证整块菜单可见） */
    private val edgeMarginPx = EDGE_MARGIN_DP * density
    /** 落点与圆心脱离多远处才画连接线 */
    private val connectionThresholdPx = CONNECTION_THRESHOLD_DP * density

    // ── 文案尺寸（图标在上、文案在下的整块布局）──────────────────────────────────────────────
    /** 文案胶囊的水平/垂直内衬 */
    private val labelPadHPx = LABEL_PAD_H_DP * density
    private val labelPadVPx = LABEL_PAD_V_DP * density
    /** 图标圆底板与文案胶囊之间的间隙 */
    private val labelGapPx = LABEL_GAP_DP * density
    private val labelCornerPx = LABEL_CORNER_DP * density
    /** 热区相对整块的外扩（手指落点抖动余量） */
    private val hitPadPx = HIT_PAD_DP * density

    // ── 几何派生量（直径 = 环中线直径 + 线宽，故环中线半径 = 外径半径 - 半带宽）────────────────
    private val outerRadius = diameterPx / 2f
    private val pathRadius = outerRadius - bandPx / 2f
    private val buttonRadius = bandPx / 2f - buttonGapPx

    // ── 画笔 ────────────────────────────────────────────────────────────────────────────────
    private val scrimPaint = Paint().apply { color = SCRIM_COLOR }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUTTON_BG_COLOR }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINK_WIDTH_DP * density
        color = LINK_COLOR
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINK_COLOR }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL_BG_COLOR }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_TEXT_COLOR
        textSize = LABEL_TEXT_SIZE_DP * density
        textAlign = Paint.Align.CENTER
    }
    private val labelRect = RectF()
    /** 复用的字体度量容器（逐帧绘制不分配对象） */
    private val labelFm = Paint.FontMetrics()

    /** 落点（锚点）屏幕像素坐标：相对本 View，因铺满 content 即等于屏幕坐标 */
    var anchorX = 0f
    var anchorY = 0f

    /** 当前张角（度）：动画驱动，决定已扫出、因而可见可点的按钮数量 */
    private var openingAngle = 0f

    /** 动作项列表；沿弧等分排布 */
    private var items: List<PieMenuItem> = emptyList()

    /** 各项整块（图标圆 ∪ 下方文案胶囊）的横向半宽，[setup] 预计算，绘制与命中共用 */
    private var itemHalfWidths = FloatArray(0)

    /** 文案胶囊高（各项同字号，故单值） */
    private var labelBlockHeightPx = 0f

    /** 整块相对圆心的最大外扩（含文案与热区余量）：[onSizeChanged] 据此钳位，保证文案不被屏幕裁掉 */
    private var contentExtentPx = 0f

    /** 菜单收起（动画结束、已从父容器移除）后回调，供宿主清理引用 */
    var onDismissed: (() -> Unit)? = null

    private var animator: ValueAnimator? = null

    /** 菜单圆心：由落点钳位到屏内得到，onDraw 与命中检测共用，故缓存为字段在布局尺寸确定后计算 */
    private var centerX = 0f
    private var centerY = 0f

    /** 配置动作项与锚点，须在 [startOpen] 之前调用。 */
    fun setup(anchorX: Float, anchorY: Float, items: List<PieMenuItem>) {
        this.anchorX = anchorX
        this.anchorY = anchorY
        this.items = items
        layoutItems()
    }

    /**
     * 预计算文案块高、各项横向半宽与菜单最大外扩：文案长短决定块宽，而块宽又要参与边缘钳位，
     * 故必须在拿到 items 后算一次；结果供绘制、命中、钳位三方共用（尺寸恒定，不再逐帧 measureText）。
     */
    private fun layoutItems() {
        labelPaint.getFontMetrics(labelFm)
        labelBlockHeightPx = labelFm.descent - labelFm.ascent + labelPadVPx * 2f
        val pillHalfWidths = FloatArray(items.size) { i ->
            (labelPaint.measureText(items[i].label) + labelPadHPx * 2f) / 2f
        }
        itemHalfWidths = FloatArray(items.size) { i -> maxOf(buttonRadius, pillHalfWidths[i]) }
        // 文案只向块下方外扩，故上/下/横向分别求最坏值
        val maxHalf = (pillHalfWidths.maxOrNull() ?: 0f).let { maxOf(it, buttonRadius) } + hitPadPx
        val downExtent = buttonRadius + labelGapPx + labelBlockHeightPx + hitPadPx
        contentExtentPx = maxOf(outerRadius, pathRadius + maxOf(maxHalf, downExtent))
    }

    /** 开始弹出动画：openingAngle 0→360，扇区顺时针扫过即逐个显示按钮。 */
    fun startOpen() {
        animator?.cancel()
        openingAngle = 0f
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = OPEN_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                openingAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 收起：openingAngle 360→0 反向扫回，动画结束移除并回调 [onDismissed]。 */
    fun dismiss() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(openingAngle, 0f).apply {
            duration = CLOSE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                openingAngle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (parent as? android.view.ViewGroup)?.removeView(this@PieMenuView)
                    onDismissed?.invoke()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 把落点钳位为圆心：保证整块菜单（环 + 文案外扩）留在屏幕内，等价 QField tooCloseTo* 的 x/y 修正
        val extent = contentExtentPx + edgeMarginPx
        centerX = anchorX.coerceIn(extent, (w - extent).coerceAtLeast(extent))
        centerY = anchorY.coerceIn(extent, (h - extent).coerceAtLeast(extent))
    }

    override fun onDraw(canvas: Canvas) {
        // 轻遮罩聚焦（极淡，仍可透出地图）
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val n = items.size
        if (n == 0) return
        val segment = 360f / n

        // 连接线 + 落点标记：圆心因边缘钳位偏离落点足够远时才画（对齐 QField showConnectionLine）
        val linkDist = hypot(centerX - anchorX, centerY - anchorY)
        if (linkDist > connectionThresholdPx) {
            canvas.drawLine(centerX, centerY, anchorX, anchorY, linkPaint)
            canvas.drawCircle(anchorX, anchorY, ANCHOR_DOT_RADIUS_DP * density, dotPaint)
        }

        // 按钮：第 i 个仅在 openingAngle 扫过其扇区末端后出现，逐个“长出来”
        for (i in 0 until n) {
            if (openingAngle < segment * (i + 1)) continue
            val (bx, by) = buttonCenter(i, segment)
            canvas.drawCircle(bx, by, buttonRadius, buttonBgPaint)
            drawIcon(canvas, items[i], bx, by)
            drawLabel(canvas, items[i].label, bx, by)
        }
    }

    /** 第 [index] 项图标圆心：第 0 个在正上方，顺时针等分（屏幕 y 向下 → 角度 CW 为正） */
    private fun buttonCenter(index: Int, segment: Float): Pair<Float, Float> {
        val angleRad = Math.toRadians(-90.0 + index * segment)
        return (centerX + pathRadius * cos(angleRad).toFloat()) to
            (centerY + pathRadius * sin(angleRad).toFloat())
    }

    /** 图标下方的文案胶囊（水平居中于图标圆心） */
    private fun drawLabel(canvas: Canvas, label: String, bx: Float, by: Float) {
        val halfPill = labelPaint.measureText(label) / 2f + labelPadHPx
        val top = by + buttonRadius + labelGapPx
        labelRect.set(bx - halfPill, top, bx + halfPill, top + labelBlockHeightPx)
        canvas.drawRoundRect(labelRect, labelCornerPx, labelCornerPx, labelBgPaint)
        canvas.drawText(label, bx, top + labelPadVPx - labelFm.ascent, labelPaint)
    }

    /** 在按钮圆心绘制图标：矢量 drawable 经 AppCompatResources 解码，可按项着色。 */
    private fun drawIcon(canvas: Canvas, item: PieMenuItem, cx: Float, cy: Float) {
        val d = AppCompatResources.getDrawable(context, item.iconRes) ?: return
        val size = (buttonRadius * 2f * ICON_RATIO).toInt().coerceAtLeast(1)
        val half = size / 2
        d.setTint(item.iconTint ?: ICON_COLOR)
        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        d.setBounds(left, top, left + size, top + size)
        d.draw(canvas)
    }

    /** 本次触点是否在本浮层上按下过：用于吞掉“长按弹菜单后同一次抬手”穿透进来的孤立 UP（其 DOWN 落在 mapView 上）。 */
    private var sawDown = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sawDown = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 长按弹菜单的同一次抬手：本浮层未收到其 DOWN，属穿透孤立 UP → 吞掉，避免“弹出即被关”
                if (!sawDown) return true
                sawDown = false
                performClick()
                val hit = hitTest(event.x, event.y)
                if (hit != null) {
                    hit.onClick()
                    dismiss()
                } else {
                    // 点空白 / 中心孔 / 环外 → 收起（等价 QField 外围 MouseArea 关闭）
                    dismiss()
                }
                return true
            }
        }
        // 打开期间拦截移动等事件，避免穿透触发地图手势
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 分项矩形命中：抬手点落在哪一项的整块热区（图标圆 + 下方文案，横向取二者较宽者，外扩 [hitPadPx]）
     * 即选中该项；未扫出的项不可点，中心孔/项间空隙/环外一律返回 null 由调用方收起菜单。
     */
    private fun hitTest(x: Float, y: Float): PieMenuItem? {
        val n = items.size
        if (n == 0) return null
        val segment = 360f / n
        for (i in 0 until n) {
            // 未扫出（尚未显示）的按钮不可点
            if (openingAngle < segment * (i + 1)) continue
            val (bx, by) = buttonCenter(i, segment)
            val half = itemHalfWidths[i] + hitPadPx
            if (x < bx - half || x > bx + half) continue
            if (y < by - buttonRadius - hitPadPx) continue
            if (y > by + buttonRadius + labelGapPx + labelBlockHeightPx + hitPadPx) continue
            return items[i]
        }
        return null
    }

    companion object {
        // 尺寸（dp）
        private const val DIAMETER_DP = 150f
        private const val BAND_DP = 44f
        private const val BUTTON_GAP_DP = 4f
        private const val EDGE_MARGIN_DP = 8f
        private const val CONNECTION_THRESHOLD_DP = 12f
        private const val ANCHOR_DOT_RADIUS_DP = 3f
        private const val LINK_WIDTH_DP = 1.5f
        private const val ICON_RATIO = 0.6f // 图标相对按钮底板直径比例
        // 文案（图标下方）：字号 + 胶囊内衬 + 与图标圆的间隙 + 圆角，另加热区外扩
        private const val LABEL_TEXT_SIZE_DP = 11f
        private const val LABEL_PAD_H_DP = 6f
        private const val LABEL_PAD_V_DP = 3f
        private const val LABEL_GAP_DP = 3f
        private const val LABEL_CORNER_DP = 7f
        private const val HIT_PAD_DP = 5f

        // 动画时长（ms）
        private const val OPEN_DURATION_MS = 220L
        private const val CLOSE_DURATION_MS = 160L

        // 颜色（#AARRGGBB）：极淡遮罩 + 白底按钮 + 深色图标；连接线用定位蓝，口径同 MapStyleColors
        private val SCRIM_COLOR = 0x22000000.toInt()
        private val BUTTON_BG_COLOR = Color.WHITE
        private val ICON_COLOR = 0xFF333333.toInt()
        private val LINK_COLOR = 0xFF57ACFD.toInt()
        // 文案压在底图上明暗不定，故用近乎不透明的深色胶囊 + 白字保证可读
        private val LABEL_BG_COLOR = 0xE6303030.toInt()
        private val LABEL_TEXT_COLOR = Color.WHITE
    }
}
