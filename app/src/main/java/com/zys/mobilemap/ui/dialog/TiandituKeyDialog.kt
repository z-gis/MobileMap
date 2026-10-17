package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.ui.FormattedTexts

/**
 * 天地图密钥输入对话框：切换到天地图图源而文档中没有密钥时弹出。
 * 正文按「APP」类型申请密钥的官方流程给出引导；因 selectable 文本与链接点击互斥，
 * 官网链接做成可点击控件（[R.id.tianditu_key_site]，autoLink 跳浏览器），本应用包名
 * 单独成行并提供「复制」按钮（[R.id.tianditu_key_copy]，写入系统剪贴板）。
 * 天地图 APP 类型密钥须绑定包名+签名，服务端类型密钥在手机端不可用。
 * [onSaved] 仅在保存非空密钥时回调；取消或密钥为空均不改动任何状态。
 */
class TiandituKeyDialog(context: Context, private val onSaved: (String) -> Unit) {

    private val dialog: Dialog
    private val etKey: EditText

    init {
        val view = View.inflate(context, R.layout.dialog_tianditu_key, null)
        view.findViewById<TextView>(R.id.tianditu_key_guide)
            .text = FormattedTexts.tiandituKeyGuide()
        // 包名单独成行 + 复制：正文 selectable 与链接点击互斥，故链接与包名复制拆为独立控件
        val packageName = context.packageName
        view.findViewById<TextView>(R.id.tianditu_key_package).text = packageName
        view.findViewById<TextView>(R.id.tianditu_key_copy).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("packageName", packageName))
            Toast.makeText(context, R.string.tianditu_key_copied, Toast.LENGTH_SHORT).show()
        }
        etKey = view.findViewById(R.id.tianditu_key_et)
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.tianditu_key_title)
            .setView(view)
            .setNegativeButton(R.string.dialog_cancel, null)
            // 空校验时不关闭对话框，故正按钮点击逻辑在 show() 后接管
            .setPositiveButton(R.string.tianditu_key_save, null)
            .create()
    }

    fun show() {
        dialog.show()
        (dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(dialog.context, R.string.tianditu_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onSaved(key)
        }
    }
}
