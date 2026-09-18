package com.zys.mobilemap.survey

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.zys.mobilemap.R
import com.zys.mobilemap.ui.activity.BaseStyledActivity
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.util.AppLog

/**
 * 样地调查录入 Activity。
 *
 * 从要素详情"样地调查"入口启动，支持新建与编辑样地记录。
 * 数据来源：intent extras（sourcePath / sourceFid / compartmentNo / plotId / centerLat / centerLon）。
 */
class SampleSurveyActivity : BaseStyledActivity() {

    companion object {
        private const val TAG = "SampleSurveyActivity"
        const val EXTRA_SOURCE_PATH = "sample_survey_source_path"
        const val EXTRA_SOURCE_FID = "sample_survey_source_fid"
        const val EXTRA_COMPARTMENT_NO = "sample_survey_compartment_no"
        const val EXTRA_CENTER_LAT = "sample_survey_center_lat"
        const val EXTRA_CENTER_LON = "sample_survey_center_lon"
        const val EXTRA_PLOT_ID = "sample_survey_plot_id"

        fun start(
            context: Context,
            sourcePath: String,
            sourceFid: Long,
            compartmentNo: String?,
            centerLat: Double = Double.NaN,
            centerLon: Double = Double.NaN,
            plotId: Long = -1L
        ) {
            val intent = Intent(context, SampleSurveyActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
                putExtra(EXTRA_SOURCE_FID, sourceFid)
                putExtra(EXTRA_COMPARTMENT_NO, compartmentNo)
                putExtra(EXTRA_CENTER_LAT, centerLat)
                putExtra(EXTRA_CENTER_LON, centerLon)
                putExtra(EXTRA_PLOT_ID, plotId)
            }
            context.startActivity(intent)
        }
    }

    private var sourcePath: String = ""
    private var sourceFid: Long = -1L
    private var compartmentNo: String = "未命名小班"
    private var centerLat: Double = Double.NaN
    private var centerLon: Double = Double.NaN
    private var selectedPlotId: Long = -1L

    private var draftPlot: SurveyPlot? = null
    private val rows = mutableListOf<SurveyRow>()
    private lateinit var adapter: SurveyRowAdapter

    // Views
    private lateinit var tvCompartment: TextView
    private lateinit var tvPlotNo: TextView
    private lateinit var tvGps: TextView
    private lateinit var etTreeHeight: EditText
    private lateinit var etCanopyDensity: EditText
    private lateinit var lvRows: NonScrollListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample_survey)
        applyDefaultPageStyle()

        readArguments()
        initViews()
        loadPlot()
        updateGpsDisplay()
    }

    private fun readArguments() {
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: ""
        sourceFid = intent.getLongExtra(EXTRA_SOURCE_FID, -1L)
        compartmentNo = intent.getStringExtra(EXTRA_COMPARTMENT_NO)?.takeIf { it.isNotBlank() } ?: "未命名小班"
        centerLat = intent.getDoubleExtra(EXTRA_CENTER_LAT, Double.NaN)
        centerLon = intent.getDoubleExtra(EXTRA_CENTER_LON, Double.NaN)
        selectedPlotId = intent.getLongExtra(EXTRA_PLOT_ID, -1L)
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        tvCompartment = findViewById(R.id.tv_compartment_no)
        tvPlotNo = findViewById(R.id.tv_plot_no)
        tvGps = findViewById(R.id.tv_gps)
        etTreeHeight = findViewById(R.id.et_tree_height)
        etCanopyDensity = findViewById(R.id.et_canopy_density)
        lvRows = findViewById(R.id.lv_survey_rows)

        adapter = SurveyRowAdapter()
        lvRows.adapter = adapter

        findViewById<Button>(R.id.btn_add_row).setOnClickListener { addRow() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveSurvey() }

        tvCompartment.text = compartmentNo
    }

    private fun loadPlot() {
        val plot = if (selectedPlotId > 0L) {
            SampleSurveyStore.loadPlot(this, selectedPlotId)
        } else null

        draftPlot = plot ?: SampleSurveyStore.createDraftPlot(
            this, sourcePath, sourceFid, compartmentNo, "",
            centerLat, centerLon
        )

        draftPlot?.let { p ->
            tvPlotNo.text = "第 ${p.plotNo} 号样地"
            etTreeHeight.setText(p.averageTreeHeight ?: "")
            if (!p.canopyDensity.isNaN()) etCanopyDensity.setText(p.canopyDensity.toString())
            rows.clear()
            rows.addAll(p.rows)
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateGpsDisplay() {
        val loc = LocationManager.getInstance().getLastLocation()
        if (loc != null) {
            tvGps.text = String.format("%.6f, %.6f", loc.longitude, loc.latitude)
            // 更新草稿中心坐标
            draftPlot?.let {
                if (it.centerLatitude.isNaN()) it.centerLatitude = loc.latitude
                if (it.centerLongitude.isNaN()) it.centerLongitude = loc.longitude
            }
        } else if (!centerLat.isNaN() && !centerLon.isNaN()) {
            tvGps.text = String.format("%.6f, %.6f", centerLon, centerLat)
        }
    }

    private fun addRow() {
        syncRowsFromViews()
        rows.add(SurveyRow(sortNo = rows.size + 1))
        adapter.notifyDataSetChanged()
    }

    private fun saveSurvey() {
        syncRowsFromViews()
        val plot = draftPlot ?: return

        // 从 EditText 读取平均树高和郁闭度
        plot.averageTreeHeight = etTreeHeight.text.toString().trim().takeIf { it.isNotEmpty() }
        plot.canopyDensity = etCanopyDensity.text.toString().trim().toDoubleOrNull() ?: Double.NaN

        val saved = SampleSurveyStore.savePlot(this, plot, rows)
        if (saved != null) {
            draftPlot = saved
            Toast.makeText(this, "样地调查已保存", Toast.LENGTH_SHORT).show()
            AppLog.i(TAG, "保存样地 plotId=${saved.id}, rows=${rows.size}")
            finish()
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从当前可见的 ListView 行控件中同步用户输入到 rows 列表 */
    private fun syncRowsFromViews() {
        for (i in 0 until lvRows.childCount) {
            val view = lvRows.getChildAt(i)
            val pos = i // NonScrollListView 全部展开，childIndex == position
            if (pos >= rows.size) break
            val row = rows[pos]
            row.treeSpecies = view.findViewById<Spinner>(R.id.sp_species)?.selectedItem?.toString()
            row.dbh = view.findViewById<EditText>(R.id.et_dbh)?.text?.toString()?.trim()
            row.category = view.findViewById<Spinner>(R.id.sp_category)?.selectedItem?.toString()
            row.thinningMethod = view.findViewById<Spinner>(R.id.sp_thinning)?.selectedItem?.toString()
            row.remark = view.findViewById<EditText>(R.id.et_remark)?.text?.toString()?.trim()
        }
    }

    // ─── ListView Adapter ────────────────────────────────────────

    private inner class SurveyRowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@SampleSurveyActivity)
                .inflate(R.layout.item_sample_survey_row, parent, false)

            val row = rows[position]
            view.findViewById<TextView>(R.id.tv_sort_no).text = (position + 1).toString()

            // 树种 Spinner
            val spSpecies = view.findViewById<Spinner>(R.id.sp_species)
            setupSpinner(spSpecies, SampleSurveyConfig.TREE_SPECIES, row.treeSpecies)

            // 胸径
            val etDbh = view.findViewById<EditText>(R.id.et_dbh)
            if (etDbh.text.toString() != (row.dbh ?: "")) etDbh.setText(row.dbh ?: "")

            // 分类 Spinner
            val spCategory = view.findViewById<Spinner>(R.id.sp_category)
            setupSpinner(spCategory, SampleSurveyConfig.CATEGORIES, row.category)

            // 抚育方式 Spinner
            val spThinning = view.findViewById<Spinner>(R.id.sp_thinning)
            setupSpinner(spThinning, SampleSurveyConfig.THINNING_METHODS, row.thinningMethod)

            // 备注
            val etRemark = view.findViewById<EditText>(R.id.et_remark)
            if (etRemark.text.toString() != (row.remark ?: "")) etRemark.setText(row.remark ?: "")

            // 删除按键
            view.findViewById<ImageView>(R.id.iv_delete).setOnClickListener {
                syncRowsFromViews()
                rows.removeAt(position)
                // 重排序号
                rows.forEachIndexed { idx, r -> r.sortNo = idx + 1 }
                adapter.notifyDataSetChanged()
            }

            return view
        }

        private fun setupSpinner(spinner: Spinner, options: List<String>, selected: String?) {
            val adapter = ArrayAdapter(this@SampleSurveyActivity,
                android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            val idx = if (selected != null) options.indexOf(selected) else -1
            spinner.setSelection(if (idx >= 0) idx else 0)
        }
    }
}
