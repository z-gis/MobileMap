package com.zys.mobilemap.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.location.Location
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.location.BackgroundLocationController
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.util.CoordFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定位详情对话框（点击主界面底部定位坐标文本弹出）：展示当前定位坐标（随系统配置以十进制度或米显示）、
 * 海拔、定位精度、速度、方位角、GNSS 卫星信息、数据源与定位时间，并提供「后台定位」开关
 * （与设置页同源，改动即时写回文档）与「复制坐标」（WGS84 经纬度到剪贴板）。
 *
 * 数据均取 [LocationManager] 单例的最新定位与卫星快照；无定位时各项显示占位符，开关仍可用。
 */
object LocationDetailDialog {

    private const val NONE = "—"

    fun show(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_location_detail, null)
        val loc = LocationManager.getInstance().getLastLocation()
        val sat = LocationManager.getInstance().getSatelliteInfo()

        bindText(view, R.id.ld_tv_coord, if (loc != null) CoordFormatter.format("当前位置", loc.longitude, loc.latitude) else context.getString(R.string.location_detail_no_loc))
        bindText(view, R.id.ld_tv_alt, context.getString(R.string.ld_alt, altText(loc)))
        bindText(view, R.id.ld_tv_acc, context.getString(R.string.ld_acc, accText(loc)))
        bindText(view, R.id.ld_tv_speed, context.getString(R.string.ld_speed, speedText(loc)))
        bindText(view, R.id.ld_tv_bearing, context.getString(R.string.ld_bearing, bearingText(loc)))
        bindText(view, R.id.ld_tv_sat, context.getString(R.string.ld_sat, satText(sat)))
        bindText(view, R.id.ld_tv_provider, context.getString(R.string.ld_provider, providerText(loc)))
        bindText(view, R.id.ld_tv_time, context.getString(R.string.ld_time, timeText(loc)))

        // 后台定位开关：与 SystemConfig.backgroundLocation 双向绑定，改动即时落盘（非结构变更，不触发界面重建）；
        // 开关为「轨迹记录期后台定位」许可——保活服务仅在记录中且开关为开时运行，切换后据当前记录态同步启停
        val config = DocumentManager.getInstance().getDocument()?.systemConfig
        val bgSwitch = view.findViewById<SwitchCompat>(R.id.ld_switch_bg_location)
        bgSwitch.isChecked = config?.backgroundLocation == true
        bgSwitch.setOnCheckedChangeListener { _, isChecked ->
            config?.backgroundLocation = isChecked
            DocumentManager.getInstance().save(context)
            BackgroundLocationController.onSettingChanged(context)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.location_detail_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok, null)
            .apply {
                if (loc != null) {
                    setNeutralButton(R.string.location_detail_copy) { _, _ ->
                        copyCoordinate(context, loc)
                    }
                }
            }
            .create()
        dialog.show()
    }

    private fun bindText(view: android.view.View, id: Int, text: CharSequence) {
        view.findViewById<TextView>(id).text = text
    }

    /** hasAltitude 为 false（部分芯片/室内无高程）时返回占位，避免显示不可信海拔 */
    private fun altText(loc: Location?): String {
        if (loc == null || !loc.hasAltitude()) return NONE
        return String.format(Locale.getDefault(), "%.1f 米", loc.altitude)
    }

    private fun accText(loc: Location?): String {
        if (loc == null || !loc.hasAccuracy()) return NONE
        return String.format(Locale.getDefault(), "±%.1f 米", loc.accuracy)
    }

    private fun speedText(loc: Location?): String {
        if (loc == null || !loc.hasSpeed()) return NONE
        val mps = loc.speed
        return String.format(Locale.getDefault(), "%.1f 米/秒（%.1f km/h）", mps, mps * 3.6)
    }

    private fun bearingText(loc: Location?): String {
        if (loc == null || !loc.hasBearing()) return NONE
        return String.format(Locale.getDefault(), "%.0f°", loc.bearing)
    }

    private fun satText(sat: com.zys.mobilemap.location.GnssSatelliteInfo?): String {
        if (sat == null || (sat.visible == 0 && sat.usedInFix == 0)) return NONE
        val cn0 = if (sat.avgCn0DbHz.isNaN()) NONE else String.format(Locale.getDefault(), "%.0f dB·Hz", sat.avgCn0DbHz)
        return String.format(Locale.getDefault(), "参与定位 %d / 可见 %d，平均信噪比 %s", sat.usedInFix, sat.visible, cn0)
    }

    private fun providerText(loc: Location?): String = loc?.provider?.takeIf { it.isNotEmpty() } ?: NONE

    private fun timeText(loc: Location?): String {
        if (loc == null || loc.time <= 0L) return NONE
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(loc.time))
    }

    /** 复制 WGS84 经纬度（十进制度，6 位小数）到剪贴板 */
    private fun copyCoordinate(context: Context, loc: Location) {
        val text = String.format(Locale.getDefault(), "%.6f,%.6f", loc.longitude, loc.latitude)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("coordinate", text))
            Toast.makeText(context, R.string.location_detail_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
