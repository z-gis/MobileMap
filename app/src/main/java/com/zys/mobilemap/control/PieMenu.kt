package com.zys.mobilemap.control

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * [PieMenuView] 的挂载入口：把圆菜单以铺满方式叠到 Activity 内容容器（android.R.id.content）上，
 * 使其覆盖在地图 [com.zys.globecore.NativeMapView] 的 GL Surface 之上——纯 UI 浮层，地图 native 无感知
 * （口径同 QField：圆菜单只是 Menu 浮层，不参与渲染）。
 *
 * 同一时刻只保留一个菜单：再次 [show] 前会先收起上一个，避免叠层。
 */
object PieMenu {

    /** 当前展示的菜单视图（弱引用非必需：随父容器移除即置空），用于去重与外部主动收起。 */
    private var current: PieMenuView? = null

    /**
     * 在落点 ([anchorX], [anchorY])（屏幕像素）处弹出圆菜单。
     *
     * @param activity 宿主，用于取其 content 容器作浮层父级
     * @param anchorX  落点屏幕 X（如长按回调传入的视图内像素坐标）
     * @param anchorY  落点屏幕 Y
     * @param items    动作项，沿圆环顺时针等分（第 0 个在正上方）
     */
    fun show(activity: AppCompatActivity, anchorX: Float, anchorY: Float, items: List<PieMenuItem>) {
        if (items.isEmpty()) return
        // 去重：先立即移除上一个（跳过收起动画，避免新旧叠层闪烁）
        current?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val menu = PieMenuView(activity).apply {
            setup(anchorX, anchorY, items)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onDismissed = { if (current === this) current = null }
        }
        current = menu
        root.addView(menu)
        menu.startOpen()
    }

    /** 主动收起当前菜单（带扫回动画）；无菜单时空操作。 */
    fun dismiss() {
        current?.dismiss()
    }
}
