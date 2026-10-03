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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.util.ColorHex
import com.zys.mobilemap.vector.FeatureIcons
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 要素详情 BottomSheet：点击矢量要素后弹出，样式编辑/属性表双标签页。
 *
 * 初始为展开态（样式页控件全量显示），高度档位由基类统一配置：下拉收到约四成屏高、
 * 上拉回到展开态；内容超出屏幕高度时页内滚动。
 *
 * [styleEditable] 为 false（kml/kmz 单要素待二期、dwg/dxf 不改样式）时隐藏样式页；
 * 保存经 [onSave] 写回 [VectorStyle]，由宿主写入文档并递增样式版本触发图层重载。
 *
 * [layerStyleMode] 图层样式模式（图层管理样式图标复用）：仅样式页无属性页，
 * 标注字段候选取 [labelFieldOptions]（整层字段清单），
 * [labelSectionVisible] 为 false 时（kml 标注随源文件）隐藏标注设置区。
 */
class FeatureDetailDialog(
    resource: Int,
    private val featureName: String?,
    private val layerName: String?,
    private val featureId: Long,
    // 属性表：宿主可先弹层后异步回填（见 [pushAttributes]），故为可变
    private var attributes: Map<String, String>?,
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
    /** KML/KMZ 要素 <description> HTML：非空时属性页以 WebView 渲染（对齐 2.5.3），否则显示键值属性表；随属性异步回填可变 */
    private var description: String? = null,
    private val onSave: ((VectorStyle) -> Unit)?,
    /** 调查模式：属性值可点击编辑，保存写回矢量源文件（仅 shp/dxf/gpkg/geojson/kml/kmz 可写） */
    private val attrsEditable: Boolean = false,
    /** 属性保存回调：入参为本次会话内修改过的字段键值对，返回写回是否成功 */
    private val onSaveAttributes: ((Map<String, String>) -> Boolean)? = null,
    /** 样地调查入口可见性：调查模式下 shp 面要素为 true */
    private val surveyEntryVisible: Boolean = false,
    /** 样地调查入口点击回调：由宿主弹出样地列表/新建样地对话框 */
    private val onSurveyEntry: (() -> Unit)? = null,
    /** 顶部“编辑”按键门控：为 true 时属性编辑与样地入口初始隐藏，点顶部“编辑”后才开放（取代原全局调查模式按键） */
    private val editButtonVisible: Boolean = false,
    /** 样地调查绕过编辑门控：为 true 时样地按钮始终可见（不受“编辑”按键与标签页切换影响），供 MainActivity 直接开始样地调查 */
    private val surveyBypassEdit: Boolean = false,
    /** 属性回取挂起：属性未就绪时先行弹出（属性页显示加载提示），宿主后台读完后经 [pushAttributes] 就地回填 */
    attrsPending: Boolean = false
) : BaseBottomSheetDialog(resource) {

    /** 属性回填是否仍在途（初值取构造参数；[pushAttributes] 后置 false，空属性才能如实显示“暂无属性表”） */
    private var attrsLoading = attrsPending

    /** 当前样式编辑状态（初始取文档已有样式，缺省用默认值） */
    private var fillColor = DEFAULT_FILL
    private var fillEnabled = true
    private var outlineColor = DEFAULT_OUTLINE
    private var outlineWidth = 1
    private var labelColor = DEFAULT_LABEL
    private var labelSizePt = LABEL_SIZE_BASE
    private var labelEnabled = false
    private var labelOutlineEnabled = false
    private var labelOutlineColor = DEFAULT_LABEL_OUTLINE

    /** 点要素当前选中的标识图标 key（见 [FeatureIcons]） */
    private var selectedIconKey = FeatureIcons.DEFAULT_KEY

    /** 图标网格视图（key -> ImageView），供选中态高亮刷新 */
    private val iconViews = mutableListOf<Pair<String, ImageView>>()

    /** 调查模式：本次会话内修改过的属性键值对（仅含被编辑过的字段） */
    private val pendingAttrs = LinkedHashMap<String, String>()

    /** 调查模式：属性是否有改动（控制保存按键可见性） */
    private var attrsDirty = false

    /** 属性页保存按键引用（fillAttributePage 中初始化，编辑后刷新可见性） */
    private var btnSaveAttrs: Button? = null

    /** 顶部“编辑”按键门控态：editButtonVisible 时初始 false，点“编辑”后置 true 开放属性编辑与样地入口 */
    private var editing = false

    /** 顶部“样地调查”按键引用（位于编辑旁，始终可见不受编辑门控与标签页切换影响） */
    private var btnSurveyEntry: TextView? = null

    private lateinit var spLabelField: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 圆角背景与可手势调整的高度档位由基类统一配置（见 BaseBottomSheetDialog）：
        // 展开态全量显示控件，下拉收到约四成屏高；列表内手势滚列表、列表外拖拽收放弹层
        // （嵌套滚动边界由 BottomSheetBehavior 自动分发）

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

        // 顶部"编辑"按键引用：仅在属性 Tab 可见时显示，切换到样式等其他 Tab 隐藏
        val btnEdit = sheet.findViewById<TextView>(R.id.feature_detail_btn_edit)
        val editAllowed = editButtonVisible && !layerStyleMode
        
        // 标签页：样式 / 属性
        val pageStyle = sheet.findViewById<View>(R.id.feature_detail_page_style)
        val pageAttrs = sheet.findViewById<View>(R.id.feature_detail_page_attrs)
        val tabLayout = sheet.findViewById<TabLayout>(R.id.feature_detail_tab_layout)
        tabLayout.addTab(tabLayout.newTab().setText(TAB_STYLE))
        tabLayout.addTab(tabLayout.newTab().setText(TAB_ATTRS))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // 按标题而非 tab.position 判定：下面两个分支会 removeTabAt(0/1)，
                // 剩下的页签 position 会前移，用位置判定会把属性页当成样式页
                val isStyle = tab.text == TAB_STYLE
                pageStyle.visibility = if (isStyle) View.VISIBLE else View.GONE
                pageAttrs.visibility = if (isStyle) View.GONE else View.VISIBLE
                // 编辑按钮仅属性页可见（且未进入编辑态）
                if (editAllowed && !editing) {
                    btnEdit.visibility = if (!isStyle) View.VISIBLE else View.GONE
                }
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
        
        // 编辑按钮点击：进入可编辑态后隐藏自身，开放属性编辑
        if (editAllowed) {
            // 初始可见性：若无样式页（styleEditable=false）则属性页已展示，编辑按钮可见
            if (!styleEditable || layerStyleMode) {
                btnEdit.visibility = if (editAllowed && !layerStyleMode) View.VISIBLE else View.GONE
            } else {
                // 有样式页时初始选中样式页，编辑按钮隐藏（等切到属性页再显示）
                btnEdit.visibility = View.GONE
            }
            btnEdit.setOnClickListener {
                editing = true
                btnEdit.visibility = View.GONE
            }
        } else {
            btnEdit.visibility = View.GONE
        }

        // 顶部“样地调查”按键：位于编辑旁（左侧），始终可见不受标签页切换与编辑门控影响。
        btnSurveyEntry = sheet.findViewById<TextView>(R.id.feature_detail_btn_survey_entry)
        if (surveyEntryVisible) {
            btnSurveyEntry?.setOnClickListener {
                onSurveyEntry?.invoke()
                dismiss()
            }
            btnSurveyEntry?.visibility = View.VISIBLE
        }
        return dialog
    }

    /**
     * 属性异步回填：KML/DXF 等格式经 [com.zys.mobilemap.vector.GdalVectorReader.getFeatureAttributes]
     * 回取需重开文件解析（首次可达秒级），宿主先弹层（attributes=null + attrsPending，属性页显示加载提示），
     * 读取完成后调本方法就地重建属性页。属性页为固定高度内滚，重建行不改变弹层高度（无跳动）；
     * 样式页标注字段下拉同步按新属性字段刷新。弹层已关闭时调用安全（直接忽略）。
     */
    fun pushAttributes(attrs: Map<String, String>?, desc: String?) {
        attributes = attrs
        description = desc
        attrsLoading = false
        val sheet = sheetView ?: return
        refreshLabelFieldOptions()
        sheet.findViewById<LinearLayout>(R.id.feature_detail_ll_attr_rows).removeAllViews()
        fillAttributePage(sheet)
    }

    /** 属性回填后刷新样式页标注字段下拉（单要素模式候选取属性字段；图层模式/外部注入字段清单时不刷新） */
    private fun refreshLabelFieldOptions() {
        if (layerStyleMode || labelFieldOptions != null || !::spLabelField.isInitialized) return
        val options = attributes?.keys?.toList() ?: emptyList()
        spLabelField.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        val initField = initialStyle?.labelField
        if (initField != null) {
            val idx = options.indexOf(initField)
            if (idx >= 0) spLabelField.setSelection(idx)
        }
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
        fillEnabled = initialStyle?.fillEnabled ?: true
        outlineColor = initialStyle?.outlineColor ?: fallbackOutline ?: DEFAULT_OUTLINE
        initialStyle?.outlineWidth?.let { outlineWidth = it.toInt().coerceIn(WIDTH_MIN, WIDTH_MAX) }
        labelColor = initialStyle?.labelColor ?: labelColor
        labelEnabled = initialStyle?.labelField != null
        initialStyle?.labelSize?.let {
            labelSizePt = (it * LABEL_SIZE_BASE).roundToInt().coerceIn(SIZE_MIN, SIZE_MAX)
        }
        labelOutlineEnabled = initialStyle?.labelOutline == true
        initialStyle?.labelOutlineColor?.let { labelOutlineColor = it }

        val ivFill = sheet.findViewById<ImageView>(R.id.feature_detail_iv_fill_color)
        val ivOutline = sheet.findViewById<ImageView>(R.id.feature_detail_iv_outline_color)
        updateSwatch(ivFill, fillColor)
        updateSwatch(ivOutline, outlineColor)
        // 不填充时色块淡出提示（开关可回）
        ivFill.alpha = if (fillEnabled) 1f else 0.3f

        ivFill.setOnClickListener { pickColor(ivFill, fillColor) { fillColor = it } }
        ivOutline.setOnClickListener { pickColor(ivOutline, outlineColor) { outlineColor = it } }

        // 填充开关：关闭时面仅描边不填充（渲染层将填充色 alpha 置 0）；先设状态再挂监听避免回显误触
        val cbFill = sheet.findViewById<CheckBox>(R.id.feature_detail_cb_fill_enabled)
        cbFill.isChecked = fillEnabled
        cbFill.setOnCheckedChangeListener { _, checked ->
            fillEnabled = checked
            ivFill.alpha = if (checked) 1f else 0.3f
        }

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

        // 标注字段候选（单要素属性表字段或整层字段清单），不再含「不标注」项（启停改由标注开关控制）
        spLabelField = sheet.findViewById(R.id.feature_detail_sp_label_field)
        val fields = labelFieldOptions ?: attributes?.keys?.toList() ?: emptyList()
        spLabelField.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, fields)
        val initField = initialStyle?.labelField
        if (initField != null) {
            val idx = fields.indexOf(initField)
            if (idx >= 0) spLabelField.setSelection(idx)
        }

        // 标注开关：关闭时隐藏标注控件（字段/颜色/大小状态保持不变，重开恢复），保存时不写 labelField
        // 先设状态再挂监听：回显已有值时不触发监听
        val labelFields = sheet.findViewById<View>(R.id.feature_detail_ll_label_fields)
        val cbLabel = sheet.findViewById<CheckBox>(R.id.feature_detail_cb_label_enabled)
        cbLabel.isChecked = labelEnabled
        labelFields.visibility = if (labelEnabled) View.VISIBLE else View.GONE
        cbLabel.setOnCheckedChangeListener { _, checked ->
            labelEnabled = checked
            labelFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // 字体轮廓开关（字号步进控件已在样式页主体绑定）
        // 先设状态再挂监听：回显已有值时不触发监听，与列表项开关的处理一致
        val cbLabelOutline = sheet.findViewById<CheckBox>(R.id.feature_detail_cb_label_outline)
        cbLabelOutline.isChecked = labelOutlineEnabled
        cbLabelOutline.setOnCheckedChangeListener { _, checked ->
            labelOutlineEnabled = checked
        }
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
            // 仅在关闭时落盘（null 默认即填充，避免无谓改动样式版本）
            if (!fillEnabled) s.fillEnabled = false
            s.outlineColor = outlineColor
            s.outlineWidth = outlineWidth.toFloat()
        }
        if (labelSectionVisible) {
            val field = spLabelField.selectedItem?.toString()
            s.labelField = if (labelEnabled) field else null
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
     * 调查模式（[attrsEditable]=true）下值列可点击弹出编辑框，修改后显示保存按键。
     */
    private fun fillAttributePage(sheet: View) {
        val hint = sheet.findViewById<TextView>(R.id.feature_detail_tv_attr_hint)
        val header = sheet.findViewById<View>(R.id.feature_detail_ll_attr_header)
        val container = sheet.findViewById<LinearLayout>(R.id.feature_detail_ll_attr_rows)

        // 样地调查入口按键已移至 onCreateDialog（在布局中位于 TabLayout 之下，不受属性页 early return 影响）

        // 保存修改按键：调查模式下属性有改动时可见
        btnSaveAttrs = sheet.findViewById<Button>(R.id.feature_detail_btn_save_attrs).also { btn ->
            if (attrsEditable) {
                btn.setOnClickListener {
                    val ok = onSaveAttributes?.invoke(pendingAttrs) ?: false
                    if (ok) dismiss()
                }
            }
        }

        // KML/KMZ 描述优先：WebView 渲染 HTML（隐藏键值表头）；局部 val 捕获：var 属性无法智能转换
        val desc = description
        if (!desc.isNullOrBlank()) {
            hint.visibility = View.GONE
            header.visibility = View.GONE
            addDescriptionWebView(container, desc)
            return
        }

        // 局部 val 捕获后判空：var 属性无法智能转换，非空结果直接供下方遍历
        val attrs = attributes
        if (attrs.isNullOrEmpty()) {
            hint.text = if (attrsLoading) "正在读取属性…" else "该要素暂无属性表"
            hint.visibility = View.VISIBLE
            header.visibility = View.GONE
            return
        }
        hint.visibility = View.GONE
        header.visibility = View.VISIBLE // 回填路径：pending 时曾被隐藏，重建行前恢复
        val density = resources.displayMetrics.density
        // 内边距与分隔线高度在循环外算一次，避免每个属性行重复换算
        val rowPadH = (ATTR_ROW_PADDING_H_DP * density).toInt()
        val rowPadV = (ATTR_ROW_PADDING_V_DP * density).toInt()
        val dividerHeight = (ATTR_DIVIDER_HEIGHT_DP * density).toInt()
        for ((k, v) in attrs) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
            }
            val tvKey = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, ATTR_KEY_WEIGHT)
                text = k
                setTextColor(ATTR_KEY_COLOR)
                textSize = ATTR_TEXT_SIZE_SP
            }
            val tvVal = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, ATTR_VALUE_WEIGHT)
                text = v.ifEmpty { "<空>" }
                setTextColor(ATTR_VALUE_COLOR)
                textSize = ATTR_TEXT_SIZE_SP
            }
            // 属性可编辑：值列可点击弹出编辑框，保存后更新本地状态并标脏。
            // 门控态（editButtonVisible）下需先点顶部“编辑”（editing=true）才响应，否则忽略点击。
            if (attrsEditable) {
                tvVal.setOnClickListener {
                    if (editButtonVisible && !editing) return@setOnClickListener
                    val current = pendingAttrs[k] ?: v
                    AttributeEditDialog.show(requireActivity(), k, current) { newValue ->
                        pendingAttrs[k] = newValue
                        tvVal.text = newValue.ifEmpty { "<空>" }
                        if (!attrsDirty) {
                            attrsDirty = true
                            btnSaveAttrs?.visibility = View.VISIBLE
                        }
                    }
                }
            }
            row.addView(tvKey)
            row.addView(tvVal)
            container.addView(row)
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dividerHeight)
                setBackgroundColor(ATTR_DIVIDER_COLOR)
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

    /** 色块同步当前颜色；颜色串非法（document.json 可能被手改坏）时保留色块原样，不中断弹层 */
    private fun updateSwatch(iv: ImageView, hex: String) {
        runCatching { iv.setBackgroundColor(Color.parseColor(hex)) }
    }

    /** 打开颜色选取对话框，确认后回写十六进制并刷新色块（按 #AARRGGBB 存取，保留透明度） */
    private fun pickColor(swatch: ImageView, current: String, apply: (String) -> Unit) {
        val init = runCatching { Color.parseColor(current) }.getOrDefault(Color.BLACK)
        ColorPickerDialog(requireContext(), init) { color ->
            // Int ARGB -> #AARRGGBB（透明度由选色器透明度滑条控制）
            val hex = ColorHex.toArgbHex(color)
            apply(hex)
            updateSwatch(swatch, hex)
        }.create().show()
    }

    companion object {
        /** 页签标题；也用于 onTabSelected 判定当前页（见该方法内注释） */
        private const val TAB_STYLE = "样式"
        private const val TAB_ATTRS = "属性"

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

        /** 属性表配色与字号（对齐 2.5.3 属性页）；颜色解析一次复用，避免逐行 parseColor */
        private val ATTR_KEY_COLOR = Color.parseColor("#424242")
        private val ATTR_VALUE_COLOR = Color.parseColor("#616161")
        private val ATTR_DIVIDER_COLOR = Color.parseColor("#E5E5E5")
        private const val ATTR_TEXT_SIZE_SP = 14f
        private const val ATTR_ROW_PADDING_H_DP = 10
        private const val ATTR_ROW_PADDING_V_DP = 8
        private const val ATTR_DIVIDER_HEIGHT_DP = 1

        /** 属性名/属性值两列的宽度权重 */
        private const val ATTR_KEY_WEIGHT = 1f
        private const val ATTR_VALUE_WEIGHT = 2f
    }
}
