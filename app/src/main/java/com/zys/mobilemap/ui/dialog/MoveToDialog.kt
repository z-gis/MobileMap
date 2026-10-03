package com.zys.mobilemap.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.google.android.material.tabs.TabLayout
import com.zys.mobilemap.R
import com.zys.mobilemap.util.CoordTransform
import java.util.Locale

class MoveToDialog(
    context: Context,
    initLon: Double,
    initLat: Double,
    private val listener: OnConfirmListener
) {

    fun interface OnConfirmListener {
        fun onConfirm(lon: Double, lat: Double)
    }

    private var currentTab = TAB_LATLON

    private val dialog: Dialog

    init {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.move_to_title)

        val view = View.inflate(context, R.layout.dialog_move_to, null)
        builder.setView(view)

        val tabLayout: TabLayout = view.findViewById(R.id.move_to_tab_layout)
        val llLatLon: LinearLayout = view.findViewById(R.id.move_to_ll_latlon)
        val llGeo: LinearLayout = view.findViewById(R.id.move_to_ll_geo)
        val etLon: EditText = view.findViewById(R.id.move_to_et_lon)
        val etLat: EditText = view.findViewById(R.id.move_to_et_lat)
        val spinnerCrs: Spinner = view.findViewById(R.id.move_to_spinner_crs)
        val etEasting: EditText = view.findViewById(R.id.move_to_et_easting)
        val etNorthing: EditText = view.findViewById(R.id.move_to_et_northing)
        val btnConfirm: Button = view.findViewById(R.id.move_to_btn_confirm)

        etLon.setText(String.format(Locale.getDefault(), "%.6f", initLon))
        etLat.setText(String.format(Locale.getDefault(), "%.6f", initLat))

        val crsAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, CRS_NAMES)
        crsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCrs.adapter = crsAdapter

        tabLayout.addTab(tabLayout.newTab().setText("经纬度"))
        tabLayout.addTab(tabLayout.newTab().setText("地理坐标"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                // 两组输入互斥显示；切到地理坐标页签时按当前坐标系回填东/北坐标
                when (currentTab) {
                    TAB_GEO -> {
                        llLatLon.visibility = View.GONE
                        llGeo.visibility = View.VISIBLE
                        updateGeoFields(spinnerCrs, etEasting, etNorthing, etLon.text.toString(), etLat.text.toString())
                    }
                    else -> {
                        llLatLon.visibility = View.VISIBLE
                        llGeo.visibility = View.GONE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        spinnerCrs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateGeoFields(spinnerCrs, etEasting, etNorthing, etLon.text.toString(), etLat.text.toString())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnConfirm.setOnClickListener {
            try {
                if (currentTab == TAB_GEO) {
                    val easting = etEasting.text.toString().trim().toDouble()
                    val northing = etNorthing.text.toString().trim().toDouble()
                    val srcCrs = selectedCrsOf(spinnerCrs)
                    val result = CoordTransform.convert(easting, northing, srcCrs, CoordTransform.CRS_WGS84)
                    if (result != null && result.size == 2) {
                        listener.onConfirm(result[0], result[1])
                    } else {
                        Toast.makeText(context, "坐标转换失败，请检查输入", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                } else {
                    val lon = etLon.text.toString().trim().toDouble()
                    val lat = etLat.text.toString().trim().toDouble()
                    listener.onConfirm(lon, lat)
                }
                dialog.dismiss()
            } catch (e: NumberFormatException) {
                Toast.makeText(context, "请输入有效的坐标数值", Toast.LENGTH_SHORT).show()
            }
        }

        dialog = builder.create()
    }

    fun create(): Dialog = dialog

    /** 按选定坐标系把经纬度输入框的值换算成平面坐标（米），回填东/北坐标框 */
    private fun updateGeoFields(
        spinnerCrs: Spinner,
        etEasting: EditText,
        etNorthing: EditText,
        lonStr: String,
        latStr: String
    ) {
        try {
            val lon = lonStr.toDouble()
            val lat = latStr.toDouble()
            val result = CoordTransform.convert(lon, lat, CoordTransform.CRS_WGS84, selectedCrsOf(spinnerCrs))
            if (result != null && result.size == 2) {
                etEasting.setText(String.format(Locale.getDefault(), "%.3f", result[0]))
                etNorthing.setText(String.format(Locale.getDefault(), "%.3f", result[1]))
            }
        } catch (ignored: NumberFormatException) {
            // 输入框里还不是合法数字（用户正在输入）：不换算、不报错，保留东/北坐标框原值
        }
    }

    /**
     * 当前下拉选定的坐标系编码。
     *
     * 用 getOrElse 兜底而非直接下标：Spinner 在 adapter 尚未生效时 selectedItemPosition 为 -1，
     * 直接索引会抛 ArrayIndexOutOfBoundsException，而调用处只捕 NumberFormatException，接不住。
     */
    private fun selectedCrsOf(spinner: Spinner): String =
        CRS_CODES.getOrElse(spinner.selectedItemPosition) { CRS_CODES[0] }

    companion object {
        /** CGCS2000 3 度带可选带号（中央经线 102°E ~ 135°E，带号 = 中央经线 / 3） */
        private val ZONES = intArrayOf(34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45)

        /** 带号 -> EPSG 编码（映射唯一来源为 [CoordTransform.epsgOfZone]） */
        private val CRS_CODES = ZONES.map { CoordTransform.epsgOfZone(it) }.toTypedArray()

        /** 下拉显示名：由带号推导中央经线与编码，与 [CRS_CODES] 同源，不会两表失配 */
        private val CRS_NAMES =
            ZONES.map { "CGCS2000 3度带 ${it * 3} (${CoordTransform.epsgOfZone(it)})" }.toTypedArray()

        /** 页签索引：经纬度（十进制度）/ 地理坐标（投影平面坐标，米） */
        private const val TAB_LATLON = 0
        private const val TAB_GEO = 1
    }
}
