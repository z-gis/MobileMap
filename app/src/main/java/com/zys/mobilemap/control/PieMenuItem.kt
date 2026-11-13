package com.zys.mobilemap.control

/**
 * 圆菜单（pie menu）单个动作项（对齐 QField QfToolButtonPie 内每个子 QfToolButton 的语义）。
 *
 * 沿圆环顺时针等分排布，点击命中后回调 [onClick] 并自动收起菜单。
 *
 * @param iconRes    按钮图标 drawable 资源 id（用 AppCompatResources 解码，支持矢量 XML）
 * @param label      动作文案：绘制在图标**下方**的胶囊里（图标在上、文案在下），同时兼作无障碍描述
 * @param iconTint   图标着色 ARGB；默认跟随 [PieMenuView] 的按钮前景色
 * @param onClick    点击该动作的业务回调
 */
data class PieMenuItem(
    val iconRes: Int,
    val label: String,
    val iconTint: Int? = null,
    val onClick: () -> Unit
)
