package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.style.AbsoluteSizeSpan
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import com.zys.mobilemap.R
import com.zys.mobilemap.control.ColorPickerView
import com.zys.mobilemap.util.ColorHex

/**
 * 颜色选取对话框：主色板 + 色相色谱条选择颜色，下方显示旧颜色、新颜色
 * 及当前颜色的十六进制值，底部为取消/确定按钮。
 * [initialColor] 为打开对话框时的初始颜色，确定后通过 [listener] 回调。
 */
class ColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val listener: OnConfirmListener
) {

    fun interface OnConfirmListener {
        fun onConfirm(color: Int)
    }

    private val dialog: Dialog
    private val etHex: EditText

    /** 防止选择器回调与文本框输入互相触发 */
    private var updatingFromPicker = false

    init {
        val builder = AlertDialog.Builder(context)
        // 标题字号调小（Spannable 方式，不依赖系统标题控件 id）
        val title = SpannableString(context.getString(R.string.color_picker_title)).apply {
            setSpan(AbsoluteSizeSpan(TITLE_SIZE_SP, true), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        builder.setTitle(title)

        val view = View.inflate(context, R.layout.dialog_color_picker, null)
        builder.setView(view)

        val picker: ColorPickerView = view.findViewById(R.id.color_picker_view)
        val oldColorView: View = view.findViewById(R.id.color_picker_old_color)
        val newColorView: View = view.findViewById(R.id.color_picker_new_color)
        etHex = view.findViewById(R.id.color_picker_et_hex)
        val btnCancel: Button = view.findViewById(R.id.color_picker_btn_cancel)
        val btnConfirm: Button = view.findViewById(R.id.color_picker_btn_confirm)

        // 旧颜色固定为初始颜色
        oldColorView.setBackgroundColor(initialColor)
        newColorView.setBackgroundColor(initialColor)
        picker.color = initialColor
        updateHexText(initialColor)

        // 色板/色谱条拖动 -> 更新新颜色与十六进制显示
        picker.onColorChangedListener = { color ->
            updatingFromPicker = true
            newColorView.setBackgroundColor(color)
            updateHexText(color)
            updatingFromPicker = false
        }

        // 手动输入合法的十六进制值（8 位 AARRGGBB 或 6 位 RGB，后者按不透明处理）
        // -> 同步选择器与新颜色
        etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromPicker) return
                val color = parseHexOrNull(s?.toString()) ?: return
                picker.color = color
                newColorView.setBackgroundColor(color)
            }
        })

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val color = parseHexOrNull(etHex.text.toString()) ?: picker.color
            listener.onConfirm(color)
            dialog.dismiss()
        }

        dialog = builder.create()
    }

    /** 十六进制显示为 8 位 AARRGGBB（含透明度） */
    private fun updateHexText(color: Int) {
        etHex.setText(ColorHex.toArgbDigits(color))
        etHex.setSelection(etHex.text.length)
    }

    /** 解析十六进制输入：8 位 AARRGGBB 或 6 位 RGB（补不透明）；非法返回 null */
    private fun parseHexOrNull(text: CharSequence?): Int? {
        val hex = text?.toString()?.trim() ?: return null
        val full = when (hex.length) {
            HEX_LENGTH_RGB -> "FF$hex"
            HEX_LENGTH -> hex
            else -> return null
        }
        return runCatching { Color.parseColor("#$full") }.getOrNull()
    }

    fun create(): Dialog = dialog

    companion object {
        /** 含透明度的完整长度：AARRGGBB */
        private const val HEX_LENGTH = 8
        /** 仅 RGB 长度（按不透明处理） */
        private const val HEX_LENGTH_RGB = 6
        private const val TITLE_SIZE_SP = 15
    }
}
