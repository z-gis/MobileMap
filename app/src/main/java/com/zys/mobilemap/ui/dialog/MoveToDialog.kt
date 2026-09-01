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

    private lateinit var dialog: Dialog

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
                if (currentTab == TAB_LATLON) {
                    llLatLon.visibility = View.VISIBLE
                    llGeo.visibility = View.GONE
                } else {
                    llLatLon.visibility = View.GONE
                    llGeo.visibility = View.VISIBLE
                    updateGeoFields(spinnerCrs, etEasting, etNorthing, etLon.text.toString(), etLat.text.toString())
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
                if (currentTab == TAB_LATLON) {
                    val lon = etLon.text.toString().trim().toDouble()
                    val lat = etLat.text.toString().trim().toDouble()
                    listener.onConfirm(lon, lat)
                } else {
                    val easting = etEasting.text.toString().trim().toDouble()
                    val northing = etNorthing.text.toString().trim().toDouble()
                    val srcCrs = CRS_CODES[spinnerCrs.selectedItemPosition]
                    val result = nativeConvertCoordinate(easting, northing, srcCrs, "EPSG:4326")
                    if (result != null && result.size == 2) {
                        listener.onConfirm(result[0], result[1])
                    } else {
                        Toast.makeText(context, "坐标转换失败，请检查输入", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                dialog.dismiss()
            } catch (e: NumberFormatException) {
                Toast.makeText(context, "请输入有效的坐标数值", Toast.LENGTH_SHORT).show()
            }
        }

        dialog = builder.create()
    }

    fun create(): Dialog = dialog

    private fun updateGeoFields(
        spinnerCrs: Spinner,
        etEasting: EditText,
        etNorthing: EditText,
        lonStr: String,
        latStr: String
    ) {
        val selectedCrs = CRS_CODES[spinnerCrs.selectedItemPosition]
        if ("EPSG:4490" == selectedCrs) {
            etEasting.setText(lonStr)
            etNorthing.setText(latStr)
        } else {
            try {
                val lon = lonStr.toDouble()
                val lat = latStr.toDouble()
                val result = nativeConvertCoordinate(lon, lat, "EPSG:4326", selectedCrs)
                if (result != null && result.size == 2) {
                    etEasting.setText(String.format(Locale.getDefault(), "%.3f", result[0]))
                    etNorthing.setText(String.format(Locale.getDefault(), "%.3f", result[1]))
                }
            } catch (ignored: NumberFormatException) {
            }
        }
    }

    // 保持与 JNI 函数名 Java_com_zys_mobilemap_ui_dialog_MoveToDialog_nativeConvertCoordinate 一致，
    // 因此声明为类成员 external 函数（不能放入 companion object，否则符号名会变化）
    private external fun nativeConvertCoordinate(x: Double, y: Double, srcCrs: String, tgtCrs: String): DoubleArray?

    companion object {
        private val CRS_NAMES = arrayOf(
            "CGCS2000 3度带 102 (EPSG:4543)",
            "CGCS2000 3度带 105 (EPSG:4544)",
            "CGCS2000 3度带 108 (EPSG:4545)",
            "CGCS2000 3度带 111 (EPSG:4546)",
            "CGCS2000 3度带 114 (EPSG:4547)",
            "CGCS2000 3度带 117 (EPSG:4548)",
            "CGCS2000 3度带 120 (EPSG:4549)",
            "CGCS2000 3度带 123 (EPSG:4550)",
            "CGCS2000 3度带 126 (EPSG:4541)",
            "CGCS2000 3度带 129 (EPSG:4552)",
            "CGCS2000 3度带 132 (EPSG:4553)",
            "CGCS2000 3度带 135 (EPSG:4554)"
        )

        private val CRS_CODES = arrayOf(
            "EPSG:4543", "EPSG:4544", "EPSG:4545", "EPSG:4546", "EPSG:4547", "EPSG:4548",
            "EPSG:4549", "EPSG:4550", "EPSG:4551", "EPSG:4552", "EPSG:4553", "EPSG:4554"
        )

        private const val TAB_LATLON = 0
        private const val TAB_GEO = 1
    }
}
