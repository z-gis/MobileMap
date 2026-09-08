package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.MapSource

/**
 * 添加/编辑地图源对话框：名称、网址、类型（image/png、image/jpeg）、Token1、Token2。
 * [source] 为空表示添加自定义地图源；系统自带地图源名称不可修改（[nameEditable] 为 false）。
 */
class MapSourceEditDialog(
    context: Context,
    source: MapSource?,
    nameEditable: Boolean,
    private val listener: OnConfirmListener
) {

    fun interface OnConfirmListener {
        fun onConfirm(name: String, url: String, imageFormat: String, token1: String, token2: String)
    }

    private val dialog: Dialog

    init {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(if (source == null) R.string.map_source_add_title else R.string.map_source_edit_title)

        val view = View.inflate(context, R.layout.dialog_map_source, null)
        builder.setView(view)

        val etName: EditText = view.findViewById(R.id.map_source_et_name)
        val etUrl: EditText = view.findViewById(R.id.map_source_et_url)
        val spinnerType: Spinner = view.findViewById(R.id.map_source_spinner_type)
        val etToken1: EditText = view.findViewById(R.id.map_source_et_token1)
        val etToken2: EditText = view.findViewById(R.id.map_source_et_token2)
        val btnConfirm: Button = view.findViewById(R.id.map_source_btn_confirm)

        val typeAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, IMAGE_FORMATS)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = typeAdapter

        if (source != null) {
            etName.setText(source.name ?: "")
            etUrl.setText(source.url ?: "")
            etToken1.setText(source.token1 ?: "")
            etToken2.setText(source.token2 ?: "")
            val index = IMAGE_FORMATS.indexOf(source.imageFormat)
            if (index >= 0) spinnerType.setSelection(index)
        }
        // 系统自带地图源名称不可设置
        etName.isEnabled = nameEditable

        btnConfirm.setOnClickListener {
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.map_source_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (url.isEmpty()) {
                Toast.makeText(context, R.string.map_source_url_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listener.onConfirm(
                name,
                url,
                IMAGE_FORMATS[spinnerType.selectedItemPosition],
                etToken1.text.toString().trim(),
                etToken2.text.toString().trim()
            )
            dialog.dismiss()
        }

        dialog = builder.create()
    }

    fun create(): Dialog = dialog

    companion object {
        private val IMAGE_FORMATS = arrayOf("image/png", "image/jpeg")
    }
}
