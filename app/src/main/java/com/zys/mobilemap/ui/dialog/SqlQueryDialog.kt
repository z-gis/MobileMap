package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.zys.mobilemap.R
import java.io.File

/**
 * 矢量图层 SQL 查询对话框（图层管理界面 SQL 按键打开）。
 *
 * 语句由 GDAL/OGR 的 ExecuteSQL 执行（shp 走 OGR SQL 方言），命中要素在地图上以
 * 洋红高亮图层叠加显示。为降低书写成本：
 *  - 输入框预填 SELECT 骨架，表名为图层文件名（与 OGR 对 shp 的图层命名一致）；
 *  - 顶部列出 DBF 字段名，点击字段名可插入到光标处；
 *  - 仅输入条件（不以 SELECT 开头）时自动包装为完整语句。
 */
class SqlQueryDialog(
    private val context: Context,
    layerPath: String,
    layerName: String,
    fieldNames: List<String>?,
    private val listener: Listener
) {

    /** 查询动作回调：执行语句 / 清除地图上已有结果 */
    interface Listener {
        fun onExecute(sql: String)
        fun onClear()
    }

    private val dialog: Dialog

    /** OGR 对 shp 的图层名即文件主名，作为 SQL 表名预填 */
    private val tableName = File(layerPath).nameWithoutExtension

    init {
        val view = View.inflate(context, R.layout.dialog_sql_query, null)
        val tvFields: TextView = view.findViewById(R.id.sql_query_tv_fields)
        val etSql: EditText = view.findViewById(R.id.sql_query_et_sql)
        val btnClear: Button = view.findViewById(R.id.sql_query_btn_clear)
        val btnExecute: Button = view.findViewById(R.id.sql_query_btn_execute)

        if (fieldNames.isNullOrEmpty()) {
            tvFields.setText(R.string.sql_query_fields_none)
        } else {
            tvFields.text = context.getString(R.string.sql_query_fields, fieldNames.joinToString("、"))
            // 点击字段名提示逐个插入到光标处，避免手工输入中文/长字段名出错
            tvFields.setOnClickListener {
                val items = fieldNames.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle(R.string.sql_query_fields_title)
                    .setItems(items) { _, which -> insertAtCursor(etSql, items[which]) }
                    .show()
            }
        }

        etSql.setText(defaultSql())
        etSql.setSelection(etSql.text.length)

        btnExecute.setOnClickListener {
            val sql = normalize(etSql.text.toString())
            if (sql.isEmpty()) {
                Toast.makeText(context, R.string.sql_query_empty_input, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listener.onExecute(sql)
            dialog.dismiss()
        }

        btnClear.setOnClickListener {
            listener.onClear()
            dialog.dismiss()
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.sql_query_title, layerName))
            .setView(view)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
    }

    fun create(): Dialog = dialog

    /** 预填语句骨架：SELECT * FROM <表名> WHERE  */
    private fun defaultSql(): String = "SELECT * FROM $tableName WHERE "

    /**
     * 语句归一化：去首尾空白与结尾分号；不以 SELECT 开头时视为条件表达式，
     * 自动包装为完整语句（支持直接输入 “字段 = 值” 或 “WHERE 字段 = 值”，关键字大小写不敏感）。
     */
    private fun normalize(input: String): String {
        var sql = input.trim().removeSuffix(";").trim()
        if (sql.isEmpty()) return ""
        if (sql.startsWith("SELECT", ignoreCase = true)) return sql
        // 已自行写了 WHERE 前缀：按长度剔除（忽略大小写，不能用 substringAfter("WHERE") 否则小写 where 匹配不上）
        if (sql.startsWith("WHERE", ignoreCase = true)) {
            sql = sql.substring("WHERE".length).trim().ifEmpty { return "" }
        }
        return "SELECT * FROM $tableName WHERE $sql"
    }

    /** 在光标处插入文本（字段名），并保持焦点与光标位置 */
    private fun insertAtCursor(editText: EditText, text: String) {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        editText.text.replace(start, end, text)
        editText.requestFocus()
    }
}
