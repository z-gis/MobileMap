package com.zys.mobilemap.util

import java.util.Locale

/**
 * ARGB 颜色值与 `#AARRGGBB` 字符串的互转。
 *
 * 统一走这里而不是 `String.format("%08X", color)`：后者不带 Locale 时受系统区域设置影响，
 * 在使用非 ASCII 数字的区域（如泰语、阿拉伯语数字变体）会生成非 ASCII 的十六进制串，
 * 随后 `Color.parseColor` 直接抛异常，表现为“点开要素样式就闪退”。
 *
 * 位序固定为 AARRGGBB（透明度在前），与 Android `Color.parseColor` 及 document.json
 * 中 [com.zys.mobilemap.doc.VectorStyle] 的颜色串约定一致。
 */
object ColorHex {

    /** ARGB Int -> 8 位大写十六进制数字串（不含 `#`），如 `FF4A90D9`。 */
    fun toArgbDigits(color: Int): String =
        Integer.toHexString(color).uppercase(Locale.ROOT).padStart(ARGB_HEX_LENGTH, '0')

    /** ARGB Int -> `#AARRGGBB`，可被 `Color.parseColor` 直接解析。 */
    fun toArgbHex(color: Int): String = "#${toArgbDigits(color)}"

    /** ARGB 分量（各 0..255）-> `#AARRGGBB`。 */
    fun fromArgb(alpha: Int, red: Int, green: Int, blue: Int): String =
        toArgbHex((alpha shl 24) or (red shl 16) or (green shl 8) or blue)

    /** AARRGGBB 完整长度 */
    private const val ARGB_HEX_LENGTH = 8
}
