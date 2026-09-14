package com.zys.mobilemap.ui.activity

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.zys.mobilemap.R

/**
 * 帮助界面：三段使用说明（软件界面、添加本地图层、添加自定义地图源）。
 * 每段标题常驻显示，正文默认折叠，点击标题展开/收起（沿用关于页 gdal 驱动列表交互）。
 */
class HelpActivity : BaseStyledActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        applyDefaultPageStyle()

        findViewById<View>(R.id.help_iv_back).setOnClickListener { finish() }

        bindSection(R.id.help_row_ui, R.id.help_body_ui)
        bindSection(R.id.help_row_local_layer, R.id.help_body_local_layer)
        bindSection(R.id.help_row_map_source, R.id.help_body_map_source)
    }

    /**
     * 绑定一段说明：点击标题行切换正文可见性（正文文案在布局中已引用 string 资源）。
     */
    private fun bindSection(titleId: Int, bodyId: Int) {
        val title = findViewById<TextView>(titleId)
        val body = findViewById<TextView>(bodyId)
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }
}
