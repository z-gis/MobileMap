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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 圆菜单（pie / radial menu）：长按地图落点后，以落点为锚在其周围弹出一圈等分排布的快捷动作按钮。
 *
 * 设计移植自 QField `QfToolButtonPie.qml`（纯 UI 浮层，不触碰地图 native 渲染），落到 Android 经典 View
 * 技术栈的实现要点：
 *  - **环带背景**：一条以 `pathRadius` 为半径、[bandWidth] 为线宽的圆弧（[Paint.Style.STROKE] + 圆头），
 *    扫过的角度即 [openingAngle]，起始固定于正上方（-90°）；
 *  - **沿弧排布按钮**：把动作项按 `360/N` 等分顺时针分布在环带中线上（第 0 个在正上方）；
 *  - **顺时针逐个弹出**：[openingAngle] 由 0 补间到 360，第 i 个按钮仅当 `openingAngle >= segment*(i+1)`
 *    时才显示，视觉上即“扇区扫到哪、按钮长到哪”；
 *  - **极坐标命中**：抬手点按屏幕坐标换算到以菜单圆心为原点的极角，落在哪个扇区即选中哪个按钮，
 *    半径须处于环带内方为有效点击，否则视为点空白收起；
 *  - **屏幕边缘避让 + 连接线**：圆心据落点钳位使整个环留在屏内；若因此与落点脱开一段距离，
 *    则用虚线把菜单圆心连回落点（对齐 QField showConnectionLine）。
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
    /** 圆心距屏幕边的最小留白（保证整环可见） */
    private val edgeMarginPx = EDGE_MARGIN_DP * density
    /** 落点与圆心脱离多远处才画连接线 */
    private val connectionThresholdPx = CONNECTION_THRESHOLD_DP * density

    // ── 几何派生量（直径 = 环中线直径 + 线宽，故环中线半径 = 外径半径 - 半带宽）────────────────
    private val outerRadius = diameterPx / 2f
    private val pathRadius = outerRadius - bandPx / 2f
    private val buttonRadius = bandPx / 2f - buttonGapPx

    // ── 画笔 ────────────────────────────────────────────────────────────────────────────────
    private val scrimPaint = Paint().apply { color = SCRIM_COLOR }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = bandPx
        color = BAND_COLOR
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUTTON_BG_COLOR }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINK_WIDTH_DP * density
        color = LINK_COLOR
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINK_COLOR }
    private val arcRect = RectF()

    /** 落点（锚点）屏幕像素坐标：相对本 View，因铺满 content 即等于屏幕坐标 */
    var anchorX = 0f
    var anchorY = 0f

    /** 当前张角（度）：动画驱动，同时决定可见按钮数量与环带弧长 */
    private var openingAngle = 0f

    /** 动作项列表；沿弧等分排布 */
    private var items: List<PieMenuItem> = emptyList()

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
        // 把落点钳位为圆心：保证整个外环（含边距）留在屏幕内，等价 QField tooCloseTo* 的 x/y 修正
        centerX = anchorX.coerceIn(outerRadius + edgeMarginPx, (w - outerRadius - edgeMarginPx).coerceAtLeast(outerRadius + edgeMarginPx))
        centerY = anchorY.coerceIn(outerRadius + edgeMarginPx, (h - outerRadius - edgeMarginPx).coerceAtLeast(outerRadius + edgeMarginPx))
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

        // 环带背景：以环中线为路径、从正上方顺时针扫过 openingAngle
        arcRect.set(centerX - pathRadius, centerY - pathRadius, centerX + pathRadius, centerY + pathRadius)
        canvas.drawArc(arcRect, -90f, openingAngle, false, bandPaint)

        // 按钮：第 i 个仅在 openingAngle 扫过其扇区末端后出现，逐个“长出来”
        for (i in 0 until n) {
            if (openingAngle < segment * (i + 1)) continue
            // 第 0 个在正上方，顺时针等分（屏幕 y 向下 → 角度 CW 为正）
            val angleRad = Math.toRadians(-90.0 + i * segment)
            val bx = centerX + pathRadius * cos(angleRad).toFloat()
            val by = centerY + pathRadius * sin(angleRad).toFloat()
            canvas.drawCircle(bx, by, buttonRadius, buttonBgPaint)
            drawIcon(canvas, items[i], bx, by)
        }
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

    /**
     * 极坐标命中：屏幕点换算为相对圆心的极角/半径，半径须落在环带内、且该扇区按钮已显示，
     * 返回对应动作项，否则 null。
     */
    private fun hitTest(x: Float, y: Float): PieMenuItem? {
        val n = items.size
        if (n == 0) return null
        val dx = x - centerX
        val dy = y - centerY
        val r = hypot(dx, dy)
        if (kotlin.math.abs(r - pathRadius) > bandPx / 2f) return null
        val segment = 360f / n
        // atan2 角：0=east、y 向下故随顺时针增大；归一到“自正上方顺时针”[0,360)
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (deg < 0f) deg += 360f
        val idx = (deg / segment).toInt() % n
        // 未扫出（尚未显示）的按钮不可点
        if (openingAngle < segment * (idx + 1)) return null
        return items[idx]
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

        // 动画时长（ms）
        private const val OPEN_DURATION_MS = 220L
        private const val CLOSE_DURATION_MS = 160L

        // 颜色（#AARRGGBB）：极淡遮罩 + 半透明深色环带 + 白底按钮 + 深色图标；连接线用定位蓝，口径同 MapStyleColors
        private val SCRIM_COLOR = 0x22000000.toInt()
        private val BAND_COLOR = 0xB3303030.toInt()
        private val BUTTON_BG_COLOR = Color.WHITE
        private val ICON_COLOR = 0xFF333333.toInt()
        private val LINK_COLOR = 0xFF57ACFD.toInt()
    }
}
