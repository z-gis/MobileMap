package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.zys.mobilemap.R
import java.io.File

/**
 * 矢量图层 SQL 查询配置对话框（图层管理界面 SQL 按键打开）。
 *
 * 不再要求手写 SQL 语句，改为可视化拼装 WHERE 条件：每条子句一行
 * （连接词 Where/And/Or + 字段 + 运算符 + 值），点「添加子句」增行、行尾 × 删行。
 * 「应用」时把各子句拼成完整语句交 GDAL/OGR 的 ExecuteSQL 执行（shp 走 OGR SQL 方言），
 * 命中要素在地图上以洋红高亮图层叠加显示。
 *
 * 拼装规则（解决此前手写语句常命中不到的几处坑）：
 *  - 表名取图层文件主名（与 OGR 对 shp 的图层命名一致），字段名/表名统一用双引号包裹，
 *    避免中文字段名、含空格或保留字的标识符解析失败；
 *  - 值为纯数字时按数值输出（不加引号，保证 >/</>= 等比较按数值而非字典序），
 *    否则按字符串用单引号包裹并转义内部单引号；
 *  - 「包含」译为 LIKE '%值%'。
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

    /** OGR 对 shp 的图层名即文件主名，作为 SQL 表名 */
    private val tableName = File(layerPath).nameWithoutExtension

    /** 可用字段名（无属性字段时为空列表，子句字段下拉将无可选项） */
    private val fields: List<String> = fieldNames?.toList() ?: emptyList()

    /** 运算符下拉顺序对应的 SQL 运算符（与 R.array.sql_operators 一一对应） */
    private val operatorTokens = arrayOf("=", "<>", ">", ">=", "<", "<=", "LIKE")

    /** 子句行容器：每 inflate 一条 [item_sql_clause] 加入其中 */
    private val clauseContainer: LinearLayout

    init {
        val view = View.inflate(context, R.layout.dialog_sql_query, null)
        clauseContainer = view.findViewById(R.id.sql_query_ll_clauses)
        val btnAdd: View = view.findViewById(R.id.sql_query_btn_add)
        val btnClear: Button = view.findViewById(R.id.sql_query_btn_clear)
        val btnApply: Button = view.findViewById(R.id.sql_query_btn_apply)
        val btnCancel: Button = view.findViewById(R.id.sql_query_btn_cancel)

        // 初始一条子句
        addClauseRow()

        btnAdd.setOnClickListener { addClauseRow() }

        btnApply.setOnClickListener {
            val sql = buildSql()
            if (sql == null) {
                Toast.makeText(context, R.string.sql_query_incomplete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listener.onExecute(sql)
            dialog.dismiss()
        }

        btnClear.setOnClickListener {
            listener.onClear()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.sql_query_title, layerName))
            .setView(view)
            .create()

        // 加宽对话框：默认 AlertDialog 宽度偏窄，四控件会挤在一起导致选中值显示不全，
        // 弹出时把窗口宽度撑到屏幕宽的 95%（左右各留 2.5% 边距）
        dialog.setOnShowListener {
            val metrics = context.resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.95).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun create(): Dialog = dialog

    /** inflate 一条子句行并加入容器，随后刷新所有行的连接词下拉（首行固定 Where） */
    private fun addClauseRow() {
        val row = View.inflate(context, R.layout.item_sql_clause, null)
        val spField: Spinner = row.findViewById(R.id.clause_sp_field)
        val spOperator: Spinner = row.findViewById(R.id.clause_sp_operator)
        val ivDelete: ImageView = row.findViewById(R.id.clause_iv_delete)

        spField.adapter = spinnerAdapter(fields)
        spOperator.adapter = spinnerAdapter(context.resources.getStringArray(R.array.sql_operators).toList())

        ivDelete.setOnClickListener {
            // 至少保留一条子句：仅剩一条时清空其值而不删除
            if (clauseContainer.childCount > 1) {
                clauseContainer.removeView(row)
                refreshConnectors()
            } else {
                row.findViewById<EditText>(R.id.clause_et_value).setText("")
            }
        }

        clauseContainer.addView(row)
        refreshConnectors()
    }

    /**
     * 刷新各子句卡片的连接词下拉：首行固定为 Where（仅单选项），
     * 其余行为 And/Or 可选。删除中间行后原非首行的选择会被保留（其下拉内容不变）。
     */
    private fun refreshConnectors() {
        val whereOnly = listOf(context.getString(R.string.sql_query_where))
        val connectors = context.resources.getStringArray(R.array.sql_connectors).toList()
        for (i in 0 until clauseContainer.childCount) {
            val spConnector: Spinner =
                clauseContainer.getChildAt(i).findViewById(R.id.clause_sp_connector)
            if (i == 0) {
                // 首行固定 Where：仅一个选项且保持启用（禁用态 Spinner 不绘制选中文本会显示为空）
                spConnector.adapter = spinnerAdapter(whereOnly)
            } else {
                spConnector.adapter = spinnerAdapter(connectors)
            }
        }
    }

    /** 构造下拉适配器：选中项用自定义布局（保证文本可见），展开项用系统下拉布局 */
    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(context, R.layout.item_sql_spinner, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    /**
     * 依据当前子句行拼装完整 SQL；任一子句字段值为空则视为无效返回 null。
     * 结果形如：SELECT * FROM "表名" WHERE "字段" = 值 AND "字段" LIKE '%值%'
     */
    private fun buildSql(): String? {
        if (clauseContainer.childCount == 0) return null
        val where = StringBuilder()
        for (i in 0 until clauseContainer.childCount) {
            val row = clauseContainer.getChildAt(i)
            val field = (row.findViewById<Spinner>(R.id.clause_sp_field).selectedItem as? String)
                ?: return null
            val opIndex = row.findViewById<Spinner>(R.id.clause_sp_operator).selectedItemPosition
                .coerceIn(0, operatorTokens.lastIndex)
            val op = operatorTokens[opIndex]
            val rawValue = row.findViewById<EditText>(R.id.clause_et_value).text.toString().trim()
            if (rawValue.isEmpty()) return null
            val valueLiteral = valueLiteral(op, rawValue)

            if (i > 0) {
                val connector = row.findViewById<Spinner>(R.id.clause_sp_connector).selectedItem as? String
                where.append(if (connector.equals("Or", ignoreCase = true)) " OR " else " AND ")
            }
            where.append("\"").append(escapeIdentifier(field)).append("\" ")
                .append(op).append(" ").append(valueLiteral)
        }
        return "SELECT * FROM \"${escapeIdentifier(tableName)}\" WHERE $where"
    }

    /**
     * 生成值的 SQL 字面量：
     *  - 包含（LIKE）：'%值%'（值内单引号转义）；
     *  - 纯数字：原样输出（不加引号，按数值比较）；
     *  - 其余：'值'（单引号包裹并转义内部单引号）。
     */
    private fun valueLiteral(op: String, rawValue: String): String {
        return if (op == "LIKE") {
            "'%${rawValue.replace("'", "''")}%'"
        } else if (rawValue.toDoubleOrNull() != null) {
            rawValue
        } else {
            "'${rawValue.replace("'", "''")}'"
        }
    }

    /** 标识符（表名/字段名）内双引号转义为两个双引号，避免破坏引号配对 */
    private fun escapeIdentifier(name: String): String = name.replace("\"", "\"\"")
}
