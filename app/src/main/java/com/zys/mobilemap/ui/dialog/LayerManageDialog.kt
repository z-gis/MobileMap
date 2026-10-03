package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.doc.MapSource
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.ui.activity.OpenFileActivity
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.vector.VectorFileImporter
import com.zys.globecore.NativeLayerInfo
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 图层管理对话框。
 * [onZoomToExtent] 点击图层名称时回调图层四至范围，由宿主 Activity 完成地图缩放。
 * [layerRenderColors] 按图层路径查询当前实际渲染色（填充/描边），
 * 整层样式对话框色块回显所见即所得，图层未加载时返回 null。
 * [onSqlQuery] shp 图层 SQL 查询按键配置查询条件（可视化子句拼装 WHERE）后回调，由宿主执行查询并高亮命中要素。
 * [onClearSqlQuery] 清除地图上已有的查询结果。
 */
class LayerManageDialog(
    resource: Int,
    private val onZoomToExtent: ((minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) -> Unit)? = null,
    private val layerRenderColors: ((path: String) -> Pair<String?, String?>)? = null,
    private val onSqlQuery: ((path: String, sql: String) -> Unit)? = null,
    private val onClearSqlQuery: (() -> Unit)? = null
) : BaseBottomSheetDialog(resource) {

    private var rvLayerList: RecyclerView? = null
    private lateinit var adapter: LayerAdapter
    private var tabLayout: TabLayout? = null
    private var btnImport: ImageView? = null
    private var currentTab = TAB_VECTOR

    private val layerItems = mutableListOf<LayerItem>()

    /** 地图源页签当前展示的地图源（不含注记图层），与列表项位置一一对应 */
    private val mapSourceItems = mutableListOf<MapSource>()

    /** 矢量页签当前选中图层路径（点击名称高亮粗体），单选互斥，图层移除或切换页签时清除 */
    private var selectedVectorPath: String? = null

    private val docListener = object : DocumentManager.DocumentChangeListener {
        override fun onDocumentChanged(document: MapDocument) {
            syncFromDocument()
        }
    }

    /**
     * 打开文件结果回调，图层加载位于文档保存调用监听
     */
    private val openFileLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 得到导入文件路径
            val path = result.data?.getStringExtra(OpenFileActivity.RESULT_PATH) ?: return@registerForActivityResult
            AppLog.event("导入图层文件: $path (页签=$currentTab)")
            // 获取当前文档
            val doc = DocumentManager.getInstance().getDocument() ?: return@registerForActivityResult
            if (currentTab == TAB_VECTOR) {
                // 矢量导入：zip/kmz 经导入器解压，文档记录实际加载路径，保证重启可复现
                val imported = VectorFileImporter.importFile(requireContext(), path)
                if (imported == null) {
                    Toast.makeText(requireContext(),
                        R.string.vector_import_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                doc.vectorLayers.add(LayerInfo(imported.name, imported.path, LayerInfo.TYPE_VECTOR, true))
            } else if (currentTab == TAB_RASTER) {
                val name = AppDirectories.deriveBaseName(path)
                doc.rasterLayers.add(LayerInfo(name, path, LayerInfo.TYPE_RASTER, true))
            }
            // 立即保存到文档 -> 通知监听者（地图同步）-> 刷新列表
            DocumentManager.getInstance().save(requireContext())
            syncFromDocument()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        val sheet = sheetView ?: return dialog

        // 打开导入新图层
        btnImport = sheet.findViewById(R.id.layer_manage_iv_import)
        btnImport?.setOnClickListener { onImportClick() }

        // 图层列表
        rvLayerList = sheet.findViewById(R.id.rv_layer_list)
        adapter = LayerAdapter()
        rvLayerList?.layoutManager = LinearLayoutManager(requireContext())
        rvLayerList?.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        rvLayerList?.adapter = adapter

        // TAB 标签页
        tabLayout = sheet.findViewById(R.id.layer_manage_tab_layout)
        tabLayout?.let { tabs ->
            tabs.addTab(tabs.newTab().setText("矢量图层"))
            tabs.addTab(tabs.newTab().setText("栅格图层"))
            tabs.addTab(tabs.newTab().setText("地图源"))
        }

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                updateContentForTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        syncFromDocument()

        return dialog
    }

    private fun onImportClick() {
        if (currentTab == TAB_MAP_SOURCE) {
            showAddMapSourceDialog()
        } else {
            val fileTypes = if (currentTab == TAB_VECTOR) {
                // dwg/dxf 由 GDAL/OGR 内置驱动读取（DXF 驱动 + libopencad 的 CAD 驱动，只读）
                // gpkg 为 OGC GeoPackage（单文件 SQLite 容器 + R-tree 空间索引），GDAL 3.7 完整支持
                arrayOf("shp", "kml", "kmz", "zip", "dwg", "dxf", "gpkg")
            } else {
                // img 为 ERDAS Imagine 栅格，由 GDAL HFA 驱动读取（与 GeoTIFF 同一格式无关渲染链路）
                arrayOf("tif", "tiff", "img")
            }
            val intent = Intent(requireContext(), OpenFileActivity::class.java)
            intent.putExtra(OpenFileActivity.EXTRA_FILE_TYPES, fileTypes)
            // 默认目录：每次启动固定为 调查宝/layer
            intent.putExtra(
                OpenFileActivity.EXTRA_DEFAULT_DIR,
                AppDirectories.getLayerDir(requireContext()).absolutePath
            )
            openFileLauncher.launch(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        DocumentManager.getInstance().registerDocumentChangeListener(docListener)
    }

    override fun onStop() {
        super.onStop()
        DocumentManager.getInstance().unregisterDocumentChangeListener(docListener)
    }

    private fun syncFromDocument() {
        val doc = DocumentManager.getInstance().getDocument() ?: return

        layerItems.clear()
        mapSourceItems.clear()

        when (currentTab) {
            TAB_VECTOR -> for (li in doc.vectorLayers) {
                val item = LayerItem(li.name, li.path, li.type, li.visible, LayerItem.TYPE_LAYER)
                // 级别可见性生效值随列表同步（用户值优先、否则自动值）：驱动级别按键上的数字徽标回显
                item.minLevel = li.effectiveMinDisplayLevel
                layerItems.add(item)
            }
            TAB_RASTER -> for (li in doc.rasterLayers) {
                layerItems.add(LayerItem(li.name, li.path, li.type, li.visible, LayerItem.TYPE_LAYER))
            }
            TAB_MAP_SOURCE -> for (ms in doc.mapSources) {
                // 注记图层由主界面按钮控制，不在地图源列表中显示
                if (ms.isAnnotation) continue
                mapSourceItems.add(ms)
                val item = LayerItem(ms.name, ms.url,
                    "mapSource", ms.visible, LayerItem.TYPE_MAP_SOURCE)
                item.isSystem = ms.isSystem
                layerItems.add(item)
            }
        }

        // 选中图层已被移除或切换页签时清除选中态，避免残留高亮
        if (currentTab != TAB_VECTOR || layerItems.none { it.path == selectedVectorPath }) {
            selectedVectorPath = null
        }

        adapter.setItems(layerItems)
    }

    private fun updateContentForTab() {
        syncFromDocument()
    }

    /**
     * 添加自定义地图源：保存后立即生效，切换到新地图源。
     */
    private fun showAddMapSourceDialog() {
        MapSourceEditDialog(requireContext(), null, true) { name, url, imageFormat, token1, token2 ->
            val doc = DocumentManager.getInstance().getDocument() ?: return@MapSourceEditDialog
            // 地图源唯一性：原地图源可见性为假，新地图源可见性为真（不影响注记图层）
            for (s in doc.mapSources) {
                if (s.isAnnotation) continue
                s.visible = false
            }
            val ms = MapSource(name, url, true)
            ms.imageFormat = imageFormat
            ms.token1 = token1
            ms.token2 = token2
            doc.mapSources.add(ms)
            DocumentManager.getInstance().save(requireContext())
            syncFromDocument()
        }.create().show()
    }

    /**
     * 地图源设置：名称（系统自带地图源不可设置）、网址、类型、Token。
     * 天地图系列地图源 Token 共用：修改后同步到所有天地图图层（含注记图层）。
     */
    private fun onEditMapSource(position: Int) {
        val src = mapSourceItems.getOrNull(position) ?: return
        MapSourceEditDialog(requireContext(), src, !src.isSystem) { name, url, imageFormat, token1, token2 ->
            src.name = name
            src.url = url
            src.imageFormat = imageFormat
            src.token1 = token1
            src.token2 = token2
            // 天地图 Token 共用：同步到全部天地图图层（含注记层）
            DocumentManager.getInstance().getDocument()?.let {
                DocumentManager.getInstance().syncTiandituTokens(it, src)
            }
            DocumentManager.getInstance().save(requireContext())
            syncFromDocument()
        }.create().show()
    }

    /**
     * 地图源单选切换：只保持一个地图源可见性为真，
     * 保存后由地图监听者移除原地图源、加载新地图源。
     */
    private fun onSelectMapSource(position: Int) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val target = mapSourceItems.getOrNull(position) ?: return
        if (target.visible) {
            syncFromDocument()
            return
        }
        for (s in doc.mapSources) {
            if (s.isAnnotation) continue
            s.visible = false
        }
        target.visible = true
        DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    /**
     * 列表中按键
     * 切换图层可见性：保持列表可见性与文档一致，
     */
    private fun onToggleVisibility(position: Int) {
        if (position < 0 || position >= layerItems.size) return
        val item = layerItems[position]
        val doc = DocumentManager.getInstance().getDocument() ?: return

        item.visible = !item.visible

        when (currentTab) {
            TAB_VECTOR -> if (position < doc.vectorLayers.size) {
                doc.vectorLayers[position].visible = item.visible
            }
            TAB_RASTER -> if (position < doc.rasterLayers.size) {
                doc.rasterLayers[position].visible = item.visible
            }
        }

        // 立即保存到文档 -> 同步地图图层可见性
        DocumentManager.getInstance().save(requireContext())
        adapter.notifyItemChanged(position)
    }

    private fun onRemoveLayer(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("移除确认")
            .setMessage("确定移除图层：${displayLayerName(item)} ？（仅从地图移除，不删除源文件）")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ -> doRemoveLayer(position) }
            .show()
    }

    private fun doRemoveLayer(position: Int) {
        if (position < 0 || position >= layerItems.size) return
        val doc = DocumentManager.getInstance().getDocument() ?: return

        when (currentTab) {
            TAB_VECTOR -> if (position < doc.vectorLayers.size) {
                doc.vectorLayers.removeAt(position)
            }
            TAB_RASTER -> if (position < doc.rasterLayers.size) {
                doc.rasterLayers.removeAt(position)
            }
        }

        // 立即保存到文档 -> 通知监听者 -> 刷新列表 -> 从地图移除
        DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    /**
     * 点击图层名称：选中项名称高亮（粗体），读取图层四至范围并缩放到该范围。
     */
    private fun onZoomToLayer(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        selectLayer(position, item)
        val path = item.path
        if (path.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.layer_extent_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // kmz 压缩包通过 GDAL /vsizip/ 虚拟文件读取
        val openPath = if (path.lowercase(Locale.getDefault()).endsWith(".kmz")) "/vsizip/$path" else path
        val extent = NativeLayerInfo.getLayerExtent(openPath)
        if (extent == null || extent.size != 4) {
            Toast.makeText(requireContext(), R.string.layer_extent_failed, Toast.LENGTH_SHORT).show()
            return
        }
        onZoomToExtent?.invoke(extent[0], extent[1], extent[2], extent[3])
    }

    /**
     * 点击 shp 图层的 SQL 查询按键：弹出可视化查询条件配置对话框（子句行拼装 WHERE），
     * 应用后交由宿主执行查询并在地图上高亮命中要素。
     */
    private fun onSqlQueryClick(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        val path = item.path
        if (path.isNullOrEmpty()) return
        val handler = onSqlQuery
        if (handler == null) {
            Toast.makeText(requireContext(), R.string.layer_style_not_open, Toast.LENGTH_SHORT).show()
            return
        }
        // 字段名供语句书写提示（OGR 读 DBF 字段），读取失败不阻断查询
        val fields = runCatching { NativeLayerInfo.getVectorFieldNames(path)?.toList() }.getOrNull()
        SqlQueryDialog(
            requireContext(),
            path,
            displayLayerName(item),
            fields,
            object : SqlQueryDialog.Listener {
                override fun onExecute(sql: String) = handler.invoke(path, sql)
                override fun onClear() {
                    onClearSqlQuery?.invoke()
                }
            }
        ).create().show()
    }

    /**
     * 点击矢量图层「级别」按键：弹出级别可见性设置。
     * 显示自动计算值与当前生效值，用户可用 NumberPicker 指定 userMinDisplayLevel（低于该级别隐藏图层），
     * 或「恢复自动」将 userMinDisplayLevel 置 -1（生效回退自动值）。保存后经文档变更刷列表，
     * 按键上的级别数字徽标同步回显生效值。
     */
    private fun onLevelClick(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == item.path } ?: return

        val auto = layerInfo.autoMinDisplayLevel
        val autoText = if (auto >= 0) auto.toString() else getString(R.string.layer_info_size_unknown)
        val eff = layerInfo.effectiveMinDisplayLevel
        val effText = if (eff >= 0) eff.toString() else "不限制"

        val density = resources.displayMetrics.density
        val picker = NumberPicker(requireContext()).apply {
            minValue = 0
            maxValue = 20
            wrapSelectorWheel = false
            val init = if (layerInfo.userMinDisplayLevel >= 0) layerInfo.userMinDisplayLevel
            else if (auto >= 0) auto else 0
            value = init.coerceIn(0, 20)
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, pad)
            addView(
                picker,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.layer_level_visibility)
            .setMessage(
                """低于设定级别时隐藏该图层（整层入屏、要素密集成片无辨识意义）。
自动计算级别：$autoText
当前生效级别：$effText"""
            )
            .setView(container)
            .setNeutralButton("恢复自动") { _, _ ->
                layerInfo.userMinDisplayLevel = -1
                DocumentManager.getInstance().save(requireContext())
                syncFromDocument()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                layerInfo.userMinDisplayLevel = picker.value
                DocumentManager.getInstance().save(requireContext())
                syncFromDocument()
            }
            .show()
    }

    /**
     * 矢量图层单选：点击项名称高亮（粗体），原选中项恢复常规字重；
     * 重复点击同一项保持高亮不变。
     */
    private fun selectLayer(position: Int, item: LayerItem) {
        if (currentTab != TAB_VECTOR) return
        val oldPath = selectedVectorPath
        if (oldPath == item.path) return
        selectedVectorPath = item.path
        if (oldPath != null) {
            val oldPosition = layerItems.indexOfFirst { it.path == oldPath }
            if (oldPosition >= 0) adapter.notifyItemChanged(oldPosition)
        }
        adapter.notifyItemChanged(position)
    }

    /**
     * 点击矢量图层样式图标：弹出整层样式编辑（复用 [FeatureDetailDialog] 图层样式模式）。
     * shp 开放颜色/线宽/标注字段；kml/kmz 仅颜色（标注随源文件）；
     * dwg/dxf 不改样式（既定策略）。
     */
    private fun onStyleClick(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        val path = item.path
        if (path.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.layer_style_not_open, Toast.LENGTH_SHORT).show()
            return
        }
        val ext = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        when (ext) {
            "shp", "kml", "kmz", "gpkg" -> showLayerStyle(item, ext)
            else -> Toast.makeText(requireContext(), R.string.layer_style_not_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 整层样式编辑：保存写入 [LayerInfo.vectorStyle] 图层级字段（保留已有单要素覆盖），
     * 递增样式版本后保存文档，经文档变更监听重载图层生效（原数据不变）。
     * 恢复默认回传空样式，仅清除整层覆盖字段。
     */
    private fun showLayerStyle(item: LayerItem, ext: String) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == item.path } ?: return
        // shp/gpkg 均为 GDAL 索引格式，支持整层样式与标注字段下拉（DBF/GPKG 字段名）
        val isIndexed = ext == "shp" || ext == "gpkg"
        // 标注字段候选：shp 取 DBF 字段清单，gpkg 取 SQLite 表字段清单（均经 GDAL）；kml 标注随源文件不开放设置
        val fieldOptions = if (isIndexed) item.path?.let { NativeLayerInfo.getVectorFieldNames(it)?.toList() } else null
        // 所见即所得：优先取地图上该图层当前实际渲染色（已含文档样式效果），
        // 图层未加载时回退默认渲染色（与 MainActivity 矢量默认样式一致）
        val renderColors = item.path?.let { layerRenderColors?.invoke(it) }

        FeatureDetailDialog(
            R.layout.dialog_feature_detail,
            featureName = null,
            layerName = displayLayerName(item),
            featureId = -1,
            attributes = null,
            initialStyle = layerInfo.vectorStyle?.takeIf { !it.isEmpty() },
            styleEditable = true,
            fallbackFill = renderColors?.first ?: if (isIndexed) DEFAULT_SHAPE_FILL else null,
            fallbackOutline = renderColors?.second ?: if (isIndexed) DEFAULT_SHAPE_OUTLINE else null,
            layerStyleMode = true,
            labelFieldOptions = fieldOptions,
            labelSectionVisible = isIndexed,
            onSave = { style ->
                val vs = layerInfo.vectorStyle ?: VectorStyle().also { layerInfo.vectorStyle = it }
                // 整层字段整体替换（空样式即清除覆盖）；单要素覆盖不受影响，随渲染时优先级生效
                vs.fillColor = style.fillColor
                vs.fillOpacity = style.fillOpacity
                vs.fillEnabled = style.fillEnabled
                vs.outlineColor = style.outlineColor
                vs.outlineWidth = style.outlineWidth
                vs.labelField = style.labelField
                vs.labelColor = style.labelColor
                vs.labelSize = style.labelSize
                vs.labelOutline = style.labelOutline
                vs.labelOutlineColor = style.labelOutlineColor
                layerInfo.styleVersion++
                DocumentManager.getInstance().save(requireContext())
            }
        ).show(childFragmentManager, "LayerStyleDialog")
    }

    /**
     * 矢量图层样式预览图标：按文档整层样式（[LayerInfo.vectorStyle] 图层级字段）绘制，
     * 样式修改经文档变更刷新列表后随之重绘；无样式覆盖时取地图实际渲染色/默认渲染色，
     * 与所见即所得同构。标注字段已设置时在图形上按标注样式绘制字段名。
     */
    private fun drawStylePreview(item: LayerItem, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val doc = DocumentManager.getInstance().getDocument()
        val style = doc?.vectorLayers?.firstOrNull { it.path == item.path }?.vectorStyle

        // 有效颜色：文档样式 > 地图实际渲染色 > 默认渲染色（与整层样式对话框回显同序）
        val renderColors = item.path?.let { layerRenderColors?.invoke(it) }
        val fillHex = style?.fillColor ?: renderColors?.first ?: DEFAULT_SHAPE_FILL
        val outlineHex = style?.outlineColor ?: renderColors?.second ?: DEFAULT_SHAPE_OUTLINE

        // 位图尺寸跟随 ImageView 实际宽高（支持加宽的矩形预览区，避免正方形位图被等比压缩变窄）
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = resources.displayMetrics.density

        // 样式示意图形：圆角矩形，填充色 + 描边色/线宽（线宽按文档值缩放限幅保证可见）
        val strokeWidth = (style?.outlineWidth?.coerceIn(1f, 10f) ?: 1f) * density
        val inset = strokeWidth / 2 + 2 * density
        val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.color = parseColorOr(fillHex, Color.BLACK)
        style?.fillOpacity?.let { fillPaint.alpha = (it.coerceIn(0f, 1f) * 255).toInt() }
        // 填充开关关闭时预览只描边不填充（与渲染层同口径）
        if (style?.fillEnabled != false) {
            canvas.drawRoundRect(rect, 4 * density, 4 * density, fillPaint)
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth.coerceIn(density, 4 * density)
            color = parseColorOr(outlineHex, Color.BLACK)
        }
        canvas.drawRoundRect(rect, 4 * density, 4 * density, strokePaint)

        // 标注：文档/修改后的标注字段已设置时，按标注样式（颜色/缩放/轮廓）绘制字段名居中
        val labelField = style?.labelField
        if (!labelField.isNullOrEmpty()) {
            val base = 11 * density * (style.labelSize ?: 1f)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = base.coerceIn(8 * density, 16 * density)
                color = parseColorOr(style.labelColor ?: DEFAULT_LABEL_COLOR, Color.BLACK)
                textAlign = Paint.Align.CENTER
            }
            val cx = widthPx / 2f
            val cy = heightPx / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2
            if (style.labelOutline == true) {
                val outline = Paint(labelPaint).apply {
                    this.style = Paint.Style.STROKE
                    this.strokeWidth = textSize / 6
                    color = parseColorOr(style.labelOutlineColor ?: DEFAULT_LABEL_OUTLINE_COLOR, Color.BLACK)
                }
                canvas.drawText(labelField, cx, cy, outline)
            }
            canvas.drawText(labelField, cx, cy, labelPaint)
        }
        return bitmap
    }

    /**
     * 解析 `#AARRGGBB` 颜色串；为空或非法（document.json 可能被手改坏）时回退 [fallback]，
     * 保证样式预览绘制不因单个坏值中断。
     */
    private fun parseColorOr(hex: String?, fallback: Int): Int =
        if (hex.isNullOrEmpty()) {
            fallback
        } else {
            try {
                Color.parseColor(hex)
            } catch (ignored: IllegalArgumentException) {
                fallback
            }
        }

    /**
     * 图层显示名：文档名称 + 文件扩展名（如 kmz 解压后文档名为压缩包名，
     * 需补上实际加载文件扩展名）；名称已含该扩展名或无路径时原样返回。
     */
    private fun displayLayerName(item: LayerItem): String {
        val name = item.name ?: return ""
        val ext = item.path?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: ""
        return if (ext.isEmpty() || name.lowercase(Locale.getDefault()).endsWith(".$ext")) name else "$name.$ext"
    }

    /**
     * 查看图层信息：名称、类型、文件大小、路径。
     */
    private fun onShowInfo(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        val file = item.path?.let { File(it) }
        val sizeStr = if (file != null && file.exists()) formatFileSize(file.length())
        else getString(R.string.layer_info_size_unknown)
        val typeStr = when (item.type) {
            LayerInfo.TYPE_VECTOR -> getString(R.string.layer_info_type_vector)
            LayerInfo.TYPE_RASTER -> getString(R.string.layer_info_type_raster)
            else -> item.type ?: ""
        }
        // 坐标系：读取原始文件 SRS（原生返回“名称\nproj4”）；kmz 压缩包走 GDAL /vsizip/ 虚拟文件；
        // 界面只显示第一个名称（截取 proj4 前的首行），读取失败/无 SRS 显示未知
        val srsPath = item.path?.let {
            if (it.lowercase(Locale.getDefault()).endsWith(".kmz")) "/vsizip/$it" else it
        }
        val srsStr = srsPath?.let { NativeLayerInfo.getLayerSrs(it) }
            ?.substringBefore('\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.layer_info_srs_unknown)
        val infoView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_layer_info, null)
        infoView.findViewById<TextView>(R.id.layer_info_tv_name).text = displayLayerName(item)
        infoView.findViewById<TextView>(R.id.layer_info_tv_type).text = typeStr
        infoView.findViewById<TextView>(R.id.layer_info_tv_size).text = sizeStr
        infoView.findViewById<TextView>(R.id.layer_info_tv_srs).text = srsStr
        infoView.findViewById<TextView>(R.id.layer_info_tv_path).text = item.path ?: ""
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.layer_info_title)
            .setView(infoView)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun formatFileSize(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    internal class LayerItem(
        var name: String?,
        var path: String?,
        var type: String?,
        var visible: Boolean,
        var itemType: Int
    ) {
        var isSystem: Boolean = false

        /** 矢量图层级别可见性生效值（[LayerInfo.effectiveMinDisplayLevel]）：≥0 时级别按键显示数字徽标，-1 不限制无徽标 */
        var minLevel: Int = -1

        companion object {
            const val TYPE_LAYER = 0
            const val TYPE_MAP_SOURCE = 1
        }
    }

    private inner class LayerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<LayerItem> = emptyList()

        fun setItems(list: List<LayerItem>) {
            items = ArrayList(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return items[position].itemType
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == LayerItem.TYPE_MAP_SOURCE) {
                MapSourceViewHolder(inflater.inflate(R.layout.item_map_source, parent, false))
            } else {
                LayerViewHolder(inflater.inflate(R.layout.item_layer_manage, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is LayerViewHolder -> holder.bind(item)
                is MapSourceViewHolder -> holder.bind(item)
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }
    }

    /**
     * 矢量/栅格图层项：
     * 左侧样式图标（矢量可点击弹出样式设置，栅格统一图标无点击）；
     * 右侧上层名称（点击选中高亮），右侧下层 SQL 查询（仅 shp）/缩放到/可见性/信息/移除按键。
     */
    private inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon: ImageView = itemView.findViewById(R.id.item_layer_iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.item_layer_tv_name)
        private val ivSql: ImageView = itemView.findViewById(R.id.item_layer_iv_sql)
        private val flLevel: FrameLayout = itemView.findViewById(R.id.item_layer_fl_level)
        private val ivLevel: ImageView = itemView.findViewById(R.id.item_layer_iv_level)
        private val tvLevelBadge: TextView = itemView.findViewById(R.id.item_layer_tv_level_badge)
        private val ivZoomTo: ImageView = itemView.findViewById(R.id.item_layer_iv_zoom_to)
        private val ivVisibility: ImageView = itemView.findViewById(R.id.item_layer_iv_visibility)
        private val ivInfo: ImageView = itemView.findViewById(R.id.item_layer_iv_info)
        private val ivDelete: ImageView = itemView.findViewById(R.id.item_layer_iv_delete)

        fun bind(item: LayerItem) {
            // 图层项显示带扩展名的名称（地图源项不适用）
            tvName.text = displayLayerName(item)

            // 选中矢量图层名称粗体高亮，未选中恢复常规字重
            val selected = currentTab == TAB_VECTOR && item.path != null && item.path == selectedVectorPath
            tvName.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)

            val isVector = item.type == LayerInfo.TYPE_VECTOR
            if (isVector) {
                // 样式图标按图层当前样式实时绘制（文档样式优先），点击弹出样式设置；
                // 位图绘制时清除 XML 灰色 tint，避免污染样式色；首次绑定未测量用 36dp 兜底；
                // 绘制失败回退静态图标
                ivIcon.imageTintList = null
                val density = ivIcon.resources.displayMetrics.density
                // 位图按 ImageView 实际宽高绘制（首次绑定未测量时用布局声明的 72dp×36dp 兜底）
                val w = if (ivIcon.measuredWidth > 0) ivIcon.measuredWidth else (72 * density).roundToInt()
                val h = if (ivIcon.measuredHeight > 0) ivIcon.measuredHeight else (36 * density).roundToInt()
                val preview = drawStylePreview(item, w, h)
                if (preview != null) ivIcon.setImageBitmap(preview)
                else ivIcon.setImageResource(R.drawable.ic_layer_vector_style)
                ivIcon.setOnClickListener { onStyleClick(adapterPosition) }
            } else {
                // 回收复用防串色：栅格恢复 XML 同款灰色 tint 与静态图标
                ivIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.context, R.color.icon_gray)
                )
                ivIcon.setImageResource(R.drawable.ic_layer_raster)
                ivIcon.setOnClickListener(null)
                ivIcon.isClickable = false
            }

            tvName.setOnClickListener { layerItems.getOrNull(adapterPosition)?.let { selectLayer(adapterPosition, it) } }

            // SQL 查询对 shp/gpkg 开放（shp 表名与文件主名一致；gpkg 表名即图层名，语句预填可靠）；
            // 回收复用时需显式隐藏，避免栅格/其他格式项残留按键
            val ext = item.path?.substringAfterLast('.', "")?.lowercase(Locale.getDefault())
            val sqlQueryable = isVector && (ext == "shp" || ext == "gpkg")
            ivSql.visibility = if (sqlQueryable) View.VISIBLE else View.GONE
            ivSql.setOnClickListener { onSqlQueryClick(adapterPosition) }

            // 级别可见性对所有矢量图层开放（shp/kml/kmz/dwg/dxf），显隐切到外层容器（回收复用时需显式隐藏）；
            // 右下角徽标显示当前生效级别：有数字即级别可见性已启用，不限制（-1）时无徽标
            flLevel.visibility = if (isVector) View.VISIBLE else View.GONE
            ivLevel.setOnClickListener { onLevelClick(adapterPosition) }
            if (item.minLevel >= 0) {
                tvLevelBadge.text = item.minLevel.toString()
                tvLevelBadge.visibility = View.VISIBLE
            } else {
                tvLevelBadge.visibility = View.GONE
            }

            ivZoomTo.setOnClickListener { onZoomToLayer(adapterPosition) }

            ivVisibility.setImageResource(if (item.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            ivVisibility.setColorFilter(
                ContextCompat.getColor(
                    itemView.context,
                    if (item.visible) R.color.teal_700 else R.color.icon_gray
                )
            )
            ivVisibility.setOnClickListener { onToggleVisibility(adapterPosition) }

            ivInfo.setOnClickListener { onShowInfo(adapterPosition) }
            ivDelete.setOnClickListener { onRemoveLayer(adapterPosition) }
        }
    }

    /**
     * 地图源项：左侧设置按键，中间名称，右侧 RadioButton 单选切换地图源。
     */
    private inner class MapSourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivSettings: ImageView = itemView.findViewById(R.id.item_source_iv_settings)
        private val tvName: TextView = itemView.findViewById(R.id.item_source_tv_name)
        private val rbSelect: RadioButton = itemView.findViewById(R.id.item_source_rb_select)

        fun bind(item: LayerItem) {
            tvName.text = item.name ?: ""
            ivSettings.setOnClickListener { onEditMapSource(adapterPosition) }
            rbSelect.isChecked = item.visible
            rbSelect.setOnClickListener { onSelectMapSource(adapterPosition) }
        }
    }

    companion object {
        private const val TAB_VECTOR = 0
        private const val TAB_RASTER = 1
        private const val TAB_MAP_SOURCE = 2

        /** 无样式覆盖时面要素默认渲染色（与 MainActivity 矢量默认样式一致，色块所见即所得） */
        private const val DEFAULT_SHAPE_FILL = "#664A8FE3"
        private const val DEFAULT_SHAPE_OUTLINE = "#FFFFFFFF"

        /** 标注色 / 标注轮廓色未设置时的缺省色串（与 FeatureDetailDialog 默认标注色一致） */
        private const val DEFAULT_LABEL_COLOR = "#FF212121"
        private const val DEFAULT_LABEL_OUTLINE_COLOR = "#FF000000"
    }
}