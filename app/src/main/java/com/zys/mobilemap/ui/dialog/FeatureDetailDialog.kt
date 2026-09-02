package com.zys.mobilemap.ui.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.vector.FeatureIcons
import com.google.android.material.R as MaterialR
import java.util.Locale

/**
 * 要素详情 BottomSheet：点击矢量要素后弹出，样式编辑/属性表双标签页。
 *
 * 初始高度为展开态（[BottomSheetBehavior.STATE_EXPANDED] 且 skipCollapsed），
 * 保证样式页控件全量显示；内容超出屏幕高度时页内滚动。
 *
 * [styleEditable] 为 false（kml/kmz 单要素待二期、dwg/dxf 不改样式）时隐藏样式页；
 * 保存经 [onSave] 写回 [VectorStyle]，由宿主写入文档并递增样式版本触发图层重载。
 *
 * [layerStyleMode] 图层样式模式（图层管理样式图标复用）：仅样式页无属性页，
 * 标注字段候选取 [labelFieldOptions]（整层字段清单），
 * [labelSectionVisible] 为 false时（kml 标注随源文件）隐藏标注设置区。
 */
class FeatureDetailDialog(
    resource: Int,
    private val featureName: String?,
    private val layerName: String?,
    private val featureId: Long,
    private val attributes: Map<String, String>?,
    private val initialStyle: VectorStyle?,
    private val styleEditable: Boolean,
    /** 要素当前实际渲染色（无文档样式覆盖时色块回显所见即所得） */
    private val fallbackFill: String?,
    private val fallbackOutline: String?,
    /** 点要素：样式页以标识图标切换代替面样式（填充/描边/线宽），标注设置保留 */
    private val isPointFeature: Boolean = false,
    private val layerStyleMode: Boolean = false,
    private val labelFieldOptions: List<String>? = null,
    private val labelSectionVisible: Boolean = true,
    /** 要素几何量算信息（面：面积+周长，线：长度），代替原图层行展示；为空时隐藏该行 */
    private val geometryText: String? = null,
    /** KML/KMZ 要素 <description> HTML：非空时属性页以 WebView 渲染（对齐 2.5.3），否则显示键值属性表 */
    private val description: String? = null,
    private val onSave: ((VectorStyle) -> Unit)?
) : BaseBottomSheetDialog(resource) {

    /** 当前样式编辑状态（初始取文档已有样式，缺省用默认值） */
    private var fillColor = DEFAULT_FILL
    private var outlineColor = DEFAULT_OUTLINE
    private var outlineWidth = 1
    private var labelColor = DEFAULT_LABEL
    private var labelSizePt = LABEL_SIZE_BASE
    private var labelOutlineEnabled = false
    private var labelOutlineColor = DEFAULT_LABEL_OUTLINE

    /** 点要素当前选中的标识图标 key（见 [FeatureIcons]） */
    private var selectedIconKey = FeatureIcons.DEFAULT_KEY

    /** 图标网格视图（key -> ImageView），供选中态高亮刷新 */
    private val iconViews = mutableListOf<Pair<String, ImageView>>()

    private lateinit var spLabelField: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 覆盖基类 onShowListener：圆角背景 + 初始展开态（控件全量显示）；
        // peekHeight 取内容实测高度，折叠态不矮于内容——
        // 列表内手势滚列表，列表外拖拽回缩/收起弹层（嵌套滚动边界自动分发）
        dialog.setOnShowListener { d ->
            val bottomSheet = (d as BottomSheetDialog)
                .findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            bottomSheet?.let { bs ->
                bs.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded)
                sheet.post {
                    BottomSheetBehavior.from(bs).apply {
                        peekHeight = sheet.height
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }

        // 标题与基本信息（图层样式模式无要素概念，隐藏量算/要素ID行；
        // 要素模式量算行显示面积+周长（面）/长度（线），点要素无几何量算时隐藏）
        val tvName = sheet.findViewById<TextView>(R.id.feature_detail_tv_name)
        val tvLayer = sheet.findViewById<TextView>(R.id.feature_detail_tv_layer)
        val tvId = sheet.findViewById<TextView>(R.id.feature_detail_tv_id)
        if (layerStyleMode) {
            sheet.findViewById<TextView>(R.id.feature_detail_tv_title).text = "图层样式"
            tvName.text = layerName ?: ""
            tvLayer.visibility = View.GONE
            tvId.visibility = View.GONE
        } else {
            val title = featureName?.takeIf { it.isNotEmpty() } ?: layerName ?: "要素详情"
            sheet.findViewById<TextView>(R.id.feature_detail_tv_title).text = title
            tvName.text = featureName?.takeIf { it.isNotEmpty() } ?: "（无名称）"
            if (geometryText.isNullOrEmpty()) {
                tvLayer.visibility = View.GONE
            } else {
                tvLayer.text = geometryText
            }
            tvId.text = if (featureId >= 0) "要素ID：$featureId" else "要素ID：无"
        }
        sheet.findViewById<TextView>(R.id.feature_detail_tv_close).setOnClickListener { dismiss() }

        // 标签页：样式 / 属性
        val pageStyle = sheet.findViewById<View>(R.id.feature_detail_page_style)
        val pageAttrs = sheet.findViewById<View>(R.id.feature_detail_page_attrs)
        val tabLayout = sheet.findViewById<TabLayout>(R.id.feature_detail_tab_layout)
        tabLayout.addTab(tabLayout.newTab().setText("样式"))
        tabLayout.addTab(tabLayout.newTab().setText("属性"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val isStyle = tab.text == "样式"
                pageStyle.visibility = if (isStyle) View.VISIBLE else View.GONE
                pageAttrs.visibility = if (isStyle) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        if (layerStyleMode) {
            // 图层样式模式：仅样式页，移除属性页签（无单要素属性概念）
            tabLayout.removeTabAt(1)
            pageAttrs.visibility = View.GONE
            pageStyle.visibility = View.VISIBLE
            if (styleEditable) setupStylePage(sheet)
        } else if (styleEditable) {
            setupStylePage(sheet)
        } else {
            // kml/kmz 单要素样式待二期（无要素 id），dwg/dxf 明确不改样式：隐藏样式页
            tabLayout.removeTabAt(0)
            pageStyle.visibility = View.GONE
            pageAttrs.visibility = View.VISIBLE
        }

        if (!layerStyleMode) fillAttributePage(sheet)
        return dialog
    }

    /**
     * 样式页：面样式（填充色/描边色/线宽）+ 字体设置（标注字段/颜色/轮廓/字号）。
     * 单要素模式初值取合并后样式，保存写为单要素样式；
     * 图层样式模式初值取整层样式，标注字段候选取 [labelFieldOptions]，
     * [labelSectionVisible] 为 false 时隐藏标注设置区（kml 标注随源文件）。
     */
    private fun setupStylePage(sheet: View) {
        // 初值：文档样式优先，无覆盖时回显要素实际渲染色（所见即所得），再无则默认色；
        // 渲染色可能带透明度（如默认填充 0.4），色块如实展示；
        // 选色器按不透明 RGB 交互，避免拾取值与预览透明度不一致。
        fillColor = initialStyle?.fillColor ?: fallbackFill ?: DEFAULT_FILL
        outlineColor = initialStyle?.outlineColor ?: fallbackOutline ?: DEFAULT_OUTLINE
        initialStyle?.outlineWidth?.let { outlineWidth = it.toInt().coerceIn(WIDTH_MIN, WIDTH_MAX) }
        initialStyle?.labelColor?.let { labelColor = it }
        initialStyle?.labelSize?.let { labelSizePt = Math.round(it * LABEL_SIZE_BASE).coerceIn(SIZE_MIN, SIZE_MAX) }
        labelOutlineEnabled = initialStyle?.labelOutline == true
        initialStyle?.labelOutlineColor?.let { labelOutlineColor = it }

        val ivFill = sheet.findViewById<ImageView>(R.id.feature_detail_iv_fill_color)
        val ivOutline = sheet.findViewById<ImageView>(R.id.feature_detail_iv_outline_color)
        updateSwatch(ivFill, fillColor)
        updateSwatch(ivOutline, outlineColor)

        ivFill.setOnClickListener { pickColor(ivFill, fillColor) { fillColor = it } }
        ivOutline.setOnClickListener { pickColor(ivOutline, outlineColor) { outlineColor = it } }

        // 点要素：隐藏面样式（填充/描边/线宽），改为标识图标切换（标注设置区保留）
        if (isPointFeature) {
            sheet.findViewById<View>(R.id.feature_detail_tv_fill_title).visibility = View.GONE
            sheet.findViewById<View>(R.id.feature_detail_ll_fill_group).visibility = View.GONE
            selectedIconKey = initialStyle?.iconKey ?: FeatureIcons.DEFAULT_KEY
            setupIconSection(sheet)
        }

        // 标注设置区：不开放时整区隐藏（kml 标注随源文件，无文档覆盖意义）
        if (!labelSectionVisible) {
            sheet.findViewById<TextView>(R.id.feature_detail_tv_label_title).visibility = View.GONE
            sheet.findViewById<View>(R.id.feature_detail_ll_label_group).visibility = View.GONE
        } else {
            setupLabelSection(sheet)
        }

        // 线宽步进
        val tvWidth = sheet.findViewById<TextView>(R.id.feature_detail_tv_width_value)
        tvWidth.text = outlineWidth.toString()
        sheet.findViewById<TextView>(R.id.feature_detail_btn_width_minus).setOnClickListener {
            outlineWidth = (outlineWidth - 1).coerceIn(WIDTH_MIN, WIDTH_MAX)
            tvWidth.text = outlineWidth.toString()
        }
        sheet.findViewById<TextView>(R.id.feature_detail_btn_width_plus).setOnClickListener {
            outlineWidth = (outlineWidth + 1).coerceIn(WIDTH_MIN, WIDTH_MAX)
            tvWidth.text = outlineWidth.toString()
        }

        // 字号步进（显示为磅值，存储为缩放系数 = 磅值/14）
        val tvSize = sheet.findViewById<TextView>(R.id.feature_detail_tv_size_value)
        tvSize.text = labelSizePt.toString()
        sheet.findViewById<TextView>(R.id.feature_detail_btn_size_minus).setOnClickListener {
            labelSizePt = (labelSizePt - 1).coerceIn(SIZE_MIN, SIZE_MAX)
            tvSize.text = labelSizePt.toString()
        }
        sheet.findViewById<TextView>(R.id.feature_detail_btn_size_plus).setOnClickListener {
            labelSizePt = (labelSizePt + 1).coerceIn(SIZE_MIN, SIZE_MAX)
            tvSize.text = labelSizePt.toString()
        }

        sheet.findViewById<Button>(R.id.feature_detail_btn_save).setOnClickListener {
            onSave?.invoke(buildStyle())
            dismiss()
        }
        // 恢复默认：保存空样式，宿主移除该要素的单要素覆盖（图层模式为清除整层覆盖字段）
        sheet.findViewById<Button>(R.id.feature_detail_btn_reset).setOnClickListener {
            onSave?.invoke(VectorStyle())
            dismiss()
        }
    }

    /** 标注设置区：标注字段下拉（单要素取属性表字段，图层模式取整层字段清单）+ 颜色/轮廓/字号控件 */
    private fun setupLabelSection(sheet: View) {
        val ivLabelColor = sheet.findViewById<ImageView>(R.id.feature_detail_iv_label_color)
        val ivLabelOutline = sheet.findViewById<ImageView>(R.id.feature_detail_iv_label_outline_color)
        updateSwatch(ivLabelColor, labelColor)
        updateSwatch(ivLabelOutline, labelOutlineColor)
        ivLabelColor.setOnClickListener { pickColor(ivLabelColor, labelColor) { labelColor = it } }
        ivLabelOutline.setOnClickListener { pickColor(ivLabelOutline, labelOutlineColor) { labelOutlineColor = it } }

        // 标注字段：不标注 + 字段候选（单要素属性表字段或整层字段清单）
        spLabelField = sheet.findViewById(R.id.feature_detail_sp_label_field)
        val fields = labelFieldOptions ?: attributes?.keys?.toList() ?: emptyList()
        val options = listOf(LABEL_FIELD_NONE) + fields
        spLabelField.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        val initField = initialStyle?.labelField
        if (initField != null) {
            val idx = options.indexOf(initField)
            if (idx >= 0) spLabelField.setSelection(idx)
        }

        // 字体轮廓开关（字号步进控件已在样式页主体绑定）
        sheet.findViewById<CheckBox>(R.id.feature_detail_cb_label_outline).setOnCheckedChangeListener { _, checked ->
            labelOutlineEnabled = checked
        }
        sheet.findViewById<CheckBox>(R.id.feature_detail_cb_label_outline).isChecked = labelOutlineEnabled
    }

    /** 标识图标区（点要素）：程序填充内置图标网格，单选高亮，选择写入 [selectedIconKey] */
    private fun setupIconSection(sheet: View) {
        sheet.findViewById<View>(R.id.feature_detail_tv_icon_title).visibility = View.VISIBLE
        val group = sheet.findViewById<LinearLayout>(R.id.feature_detail_ll_icon_group)
        group.visibility = View.VISIBLE
        group.removeAllViews()
        iconViews.clear()
        val density = resources.displayMetrics.density
        val cell = (44 * density).toInt()
        val margin = (4 * density).toInt()
        val pad = (8 * density).toInt()
        for ((key, res) in FeatureIcons.ICONS) {
            val iv = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(cell, cell).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setPadding(pad, pad, pad, pad)
                setImageResource(res)
                contentDescription = key
                setOnClickListener {
                    selectedIconKey = key
                    refreshIconSelection()
                }
            }
            iconViews.add(key to iv)
            group.addView(iv)
        }
        refreshIconSelection()
    }

    /** 刷新图标网格选中态：选中项加高亮边框背景，其余透明 */
    private fun refreshIconSelection() {
        for ((key, iv) in iconViews) {
            if (key == selectedIconKey) iv.setBackgroundResource(R.drawable.bg_icon_selected)
            else iv.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /** 汇总当前控件状态为样式（标注区隐藏时不写标注字段，避免无效覆盖） */
    private fun buildStyle(): VectorStyle {
        val s = VectorStyle()
        if (isPointFeature) {
            // 点要素：写标识图标 key（面样式控件已隐藏，不写填充/描边）
            s.iconKey = selectedIconKey
        } else {
            s.fillColor = fillColor
            s.outlineColor = outlineColor
            s.outlineWidth = outlineWidth.toFloat()
        }
        if (labelSectionVisible) {
            val field = spLabelField.selectedItem?.toString()
            s.labelField = field?.takeIf { it != LABEL_FIELD_NONE }
            s.labelColor = labelColor
            s.labelSize = labelSizePt.toFloat() / LABEL_SIZE_BASE
            s.labelOutline = labelOutlineEnabled
            if (labelOutlineEnabled) s.labelOutlineColor = labelOutlineColor
        }
        return s
    }

    /**
     * 属性页：KML/KMZ 有 <description> 时用 WebView 渲染 HTML（对齐 2.5.3），
     * 否则显示属性名/属性值两列表格；无属性时显示提示。
     */
    private fun fillAttributePage(sheet: View) {
        val hint = sheet.findViewById<TextView>(R.id.feature_detail_tv_attr_hint)
        val header = sheet.findViewById<View>(R.id.feature_detail_ll_attr_header)
        val container = sheet.findViewById<LinearLayout>(R.id.feature_detail_ll_attr_rows)

        // KML/KMZ 描述优先：WebView 渲染 HTML（隐藏键值表头）
        if (!description.isNullOrBlank()) {
            hint.visibility = View.GONE
            header.visibility = View.GONE
            addDescriptionWebView(container, description)
            return
        }

        if (attributes.isNullOrEmpty()) {
            hint.text = "该要素暂无属性表"
            hint.visibility = View.VISIBLE
            header.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        for ((k, v) in attributes) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = (10 * density).toInt()
                setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
            }
            val tvKey = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = k
                setTextColor(Color.parseColor("#424242"))
                textSize = 14f
            }
            val tvVal = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                text = v.ifEmpty { "<空>" }
                setTextColor(Color.parseColor("#616161"))
                textSize = 14f
            }
            row.addView(tvKey)
            row.addView(tvVal)
            container.addView(row)
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt())
                setBackgroundColor(Color.parseColor("#E5E5E5"))
            })
        }
    }

    /**
     * WebView 渲染 <description> HTML：禁用 JS/DOM 存储/缩放控件，宽视口 + 概览模式，
     * 文字放大 300%（对齐 2.5.3 的 KML/KMZ 描述展示）；固定高度内滚，触摸时禁止父容器拦截以滚动 WebView。
     */
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun addDescriptionWebView(container: LinearLayout, html: String) {
        val density = resources.displayMetrics.density
        val webView = WebView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt())
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.textZoom = 300
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            loadDataWithBaseURL(null, normalizeHtml(html), "text/html", "UTF-8", null)
        }
        container.addView(webView)
    }

    /** 规范化 HTML：已含标签原样返回，纯文本包成 <html><body>…</body></html>（对齐 2.5.3） */
    private fun normalizeHtml(html: String): String {
        val trimmed = html.trim()
        if (trimmed.isEmpty()) return "<html><body></body></html>"
        val lower = trimmed.lowercase(Locale.getDefault())
        val hasTag = lower.startsWith("<html") ||
                listOf("<body", "<table", "<div", "<p", "<br", "<span").any { lower.contains(it) }
        return if (hasTag) trimmed else "<html><body>$trimmed</body></html>"
    }

    /** 色块同步当前颜色 */
    private fun updateSwatch(iv: ImageView, hex: String) {
        runCatching { iv.setBackgroundColor(Color.parseColor(hex)) }
    }

    /** 打开颜色选取对话框，确认后回写十六进制并刷新色块（按 #AARRGGBB 存取，保留透明度） */
    private fun pickColor(swatch: ImageView, current: String, apply: (String) -> Unit) {
        val init = runCatching { Color.parseColor(current) }.getOrDefault(Color.BLACK)
        ColorPickerDialog(requireContext(), init) { color ->
            // Int ARGB -> #AARRGGBB（透明度由选色器透明度滑条控制）
            val hex = String.format("#%08X", color)
            apply(hex)
            updateSwatch(swatch, hex)
        }.create().show()
    }

    companion object {
        private const val LABEL_FIELD_NONE = "不标注"
        /** 字号显示基准：磅值 = labelSize 缩放系数 × 14 */
        private const val LABEL_SIZE_BASE = 14
        private const val WIDTH_MIN = 1
        private const val WIDTH_MAX = 10
        private const val SIZE_MIN = 8
        private const val SIZE_MAX = 36

        private const val DEFAULT_FILL = "#FF4A90D9"
        private const val DEFAULT_OUTLINE = "#FF1A4FA0"
        private const val DEFAULT_LABEL = "#FF212121"
        private const val DEFAULT_LABEL_OUTLINE = "#FF000000"
    }
}
