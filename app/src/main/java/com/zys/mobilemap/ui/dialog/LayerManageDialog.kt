package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerGroup
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.doc.MapSource
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.ui.activity.OpenFileActivity
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.LayerFileDeleter
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.mobilemap.vector.ImportResult
import com.zys.mobilemap.vector.LayerType
import com.zys.mobilemap.vector.VectorFileImporter
import com.zys.mobilemap.vector.VectorFormats
import com.zys.mobilemap.vector.VectorMetaCache
import com.zys.globecore.NativeLayerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
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
    private val layerRenderColors: ((path: String, layerName: String) -> Pair<String?, String?>)? = null,
    private val onSqlQuery: ((path: String, layerName: String, sql: String) -> Unit)? = null,
    private val onClearSqlQuery: (() -> Unit)? = null
) : BaseBottomSheetDialog(resource) {

    private var rvLayerList: RecyclerView? = null
    private lateinit var adapter: LayerAdapter
    private var tabLayout: TabLayout? = null
    private var btnImport: TextView? = null
    private var btnNewGroup: TextView? = null
    private var currentTab = TAB_VECTOR

    private val layerItems = mutableListOf<LayerItem>()

    /**
     * 折叠的分组 id 集合（含未分组虚拟节 -1）。与拍照的展开白名单相反：
     * 图层管理现状是全展开平铺，默认保持全展开。
     */
    private val collapsedIds = mutableSetOf<Int>()

    /** 拖拽排序进行中：期间冻结文档驱动的列表重建（避免 DiffUtil 打断手势），抬手时一次提交 */
    private var dragging = false

    /** 长按拖拽排序（仅矢量页签的图层行；组头/栅格/地图源不可拖） */
    private var dragHelper: ItemTouchHelper? = null

    /** 地图源页签当前展示的地图源（不含注记图层），与列表项位置一一对应 */
    private val mapSourceItems = mutableListOf<MapSource>()

    /** 矢量页签当前选中图层路径（点击名称高亮粗体），单选互斥，图层移除或切换页签时清除 */
    private var selectedVectorPath: String? = null
    private var selectedVectorLayerName: String = ""

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
            if (currentTab == TAB_VECTOR) {
                // GDB 目录复制/解压及 OGR 图层枚举可能耗时，后台完成后再回主线程选择要素类。
                lifecycleScope.launch {
                    val importedList = withContext(Dispatchers.IO) {
                        VectorFileImporter.importFiles(requireContext(), path)
                    }
                    if (importedList.isEmpty()) {
                        Toast.makeText(requireContext(),
                            R.string.vector_import_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        addImportedVectorLayers(importedList)
                    }
                }
                return@registerForActivityResult
            }
            val doc = DocumentManager.getInstance().getDocument() ?: return@registerForActivityResult
            if (currentTab == TAB_RASTER) {
                val name = AppDirectories.deriveBaseName(path)
                doc.rasterLayers.add(LayerInfo(name, path, LayerInfo.TYPE_RASTER, true))
            }
            // 立即保存到文档 -> 通知监听者（地图同步）-> 刷新列表
            DocumentManager.getInstance().save(requireContext())
            syncFromDocument()
        }
    }

    /** GDB 多要素类先由用户选择；普通文件与 shp 压缩包保持原有全部导入行为。 */
    private fun addImportedVectorLayers(importedList: List<ImportResult>) {
        val gdbLayers = importedList.filter { it.layerName.isNotEmpty() }
        if (gdbLayers.size > 1) {
            val labels = gdbLayers.map { "${File(it.path).name} / ${it.layerName}" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("选择 GDB 图层")
                .setItems(labels) { _, which -> persistImportedVectorLayers(listOf(gdbLayers[which])) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        persistImportedVectorLayers(importedList)
    }

    private fun persistImportedVectorLayers(importedList: List<ImportResult>) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        var added = false
        for (imported in importedList) {
            if (doc.vectorLayers.none { it.path == imported.path && it.layerName == imported.layerName }) {
                doc.vectorLayers.add(
                    LayerInfo(
                        imported.name, imported.path, LayerInfo.TYPE_VECTOR, true, imported.layerName
                    )
                )
                added = true
            }
        }
        if (added) DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        val sheet = sheetView ?: return dialog

        // 打开导入新图层（标题栏右侧「图标+文字」键）
        btnImport = sheet.findViewById(R.id.layer_manage_tv_import)
        btnImport?.setOnClickListener { onImportClick() }

        // 新建分组（地图源页签不分组，随页签切显隐）
        btnNewGroup = sheet.findViewById(R.id.layer_manage_tv_new_group)
        btnNewGroup?.setOnClickListener { onNewGroup() }

        // 图层列表
        rvLayerList = sheet.findViewById(R.id.rv_layer_list)
        adapter = LayerAdapter()
        rvLayerList?.layoutManager = LinearLayoutManager(requireContext())
        rvLayerList?.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        rvLayerList?.adapter = adapter
        dragHelper = ItemTouchHelper(LayerDragCallback()).also { helper ->
            rvLayerList?.let { helper.attachToRecyclerView(it) }
        }

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
                // 能力主表统一导出导入筛选（含 shp 族附属文件，否则系统选择器选不中完整 shp 族）
                VectorFormats.pickFilter(LayerType.VECTOR).toTypedArray()
            } else {
                VectorFormats.pickFilter(LayerType.RASTER).toTypedArray()
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
        if (dragging) return // 拖拽中冻结重建：DiffUtil 派发会打断手势，抬手时统一提交并重建
        val doc = DocumentManager.getInstance().getDocument() ?: return

        layerItems.clear()
        mapSourceItems.clear()
        btnNewGroup?.visibility = if (currentTab == TAB_MAP_SOURCE) View.GONE else View.VISIBLE

        when (currentTab) {
            TAB_VECTOR -> buildLayerRows(doc, doc.vectorLayers, true)
            TAB_RASTER -> buildLayerRows(doc, doc.rasterLayers, false)
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
        if (currentTab != TAB_VECTOR || layerItems.none {
                it.path == selectedVectorPath && it.layerName == selectedVectorLayerName
            }
        ) {
            selectedVectorPath = null
            selectedVectorLayerName = ""
        }

        adapter.setItems(layerItems)
    }

    /**
     * 分节扁平化：真实组（创建序）在前、未分组虚拟节恒在最后；组头 + 展开组的成员行。
     * 页签内只显示含本类型成员的组 + 完全空的组（空组两个页签都可管理）。
     * 无图层且无组时不产生任何行（维持原空列表表现）。
     *
     * 成员**倒序显示**（doc 数组首=最下层，故列表顶=最上层，与 PC TOC 口径一致）；
     * 仅矢量页签倒序（压盖序本轮只做矢量，栅格/地图源显示序保持原样以免误导）。
     * 拖拽排序只改 doc 数组位置，落盘后由 sync 尾循环下发压盖序（见 [commitDragOrder]）。
     */
    private fun buildLayerRows(doc: MapDocument, list: List<LayerInfo>, vector: Boolean) {
        if (list.isEmpty() && doc.groups.isEmpty()) return
        for (g in doc.groups) {
            val members = list.filter { it.groupId == g.id }
            if (members.isEmpty() && !isGroupEmpty(doc, g.id)) continue
            addHeaderRow(g.id, g.name, members)
            if (!collapsedIds.contains(g.id)) {
                for (li in if (vector) members.asReversed() else members) addLayerRow(li, vector)
            }
        }
        val rest = list.filter { it.groupId == LayerInfo.UNGROUPED_ID }
        addHeaderRow(LayerInfo.UNGROUPED_ID, "未分组", rest)
        if (!collapsedIds.contains(LayerInfo.UNGROUPED_ID)) {
            for (li in if (vector) rest.asReversed() else rest) addLayerRow(li, vector)
        }
    }

    /** 组在矢量 + 栅格两侧都无成员 */
    private fun isGroupEmpty(doc: MapDocument, groupId: Int): Boolean =
        doc.vectorLayers.none { it.groupId == groupId } && doc.rasterLayers.none { it.groupId == groupId }

    private fun addHeaderRow(groupId: Int, name: String, members: List<LayerInfo>) {
        val item = LayerItem(name, null, null, false, LayerItem.TYPE_GROUP_HEADER)
        item.groupId = groupId
        item.groupName = name
        item.count = members.size
        item.allVisible = members.isNotEmpty() && members.all { it.visible }
        item.collapsed = collapsedIds.contains(groupId)
        layerItems.add(item)
    }

    private fun addLayerRow(li: LayerInfo, vector: Boolean) {
        val item = LayerItem(li.name, li.path, li.type, li.visible, LayerItem.TYPE_LAYER)
        item.layerName = li.layerName
        item.groupId = li.groupId
        item.styleVersion = li.styleVersion
        // 级别可见性生效值随列表同步（用户值优先、否则自动值）：驱动级别按键上的数字徽标回显
        if (vector) item.minLevel = li.effectiveMinDisplayLevel
        layerItems.add(item)
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
     * 切换到天地图图源且文档中没有密钥时，先弹密钥获取引导对话框，保存密钥后才执行切换。
     */
    private fun onSelectMapSource(position: Int) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val target = mapSourceItems.getOrNull(position) ?: return
        if (target.visible) {
            syncFromDocument()
            return
        }
        if (target.isTiandituSource && !DocumentManager.getInstance().hasTiandituKey(doc)) {
            TiandituKeyDialog(requireContext()) { key ->
                val d = DocumentManager.getInstance().getDocument() ?: return@TiandituKeyDialog
                DocumentManager.getInstance().applyTiandituKey(d, key)
                selectMapSource(d, target)
            }.show()
            return
        }
        selectMapSource(doc, target)
    }

    /** 执行地图源切换：其余非注记源置为不可见、目标源置为可见并落盘 */
    private fun selectMapSource(doc: MapDocument, target: MapSource) {
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
     * 切换图层可见性：按 path 定位文档层（分节列表 position 含组头，不再等于文档数组下标），
     * 保存后整体重建列表（组头 allVisible 随成员联动）。
     */
    private fun onToggleVisibility(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        if (item.itemType != LayerItem.TYPE_LAYER) return
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val list = if (currentTab == TAB_VECTOR) doc.vectorLayers else doc.rasterLayers
        val li = list.firstOrNull { it.path == item.path && it.layerName == item.layerName } ?: return
        li.visible = !li.visible

        // 立即保存到文档 -> 同步地图图层可见性
        DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    private fun onRemoveLayer(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        // 「同时删除图层文件」选项：源文件存在且无其他图层共享删除目标（同 GDB 要素类/同 gpkg 层/同压缩包 shp）才提供
        val doc = DocumentManager.getInstance().getDocument()
        val others = doc?.let { d ->
            (d.vectorLayers + d.rasterLayers)
                .filter { !(it.path == item.path && it.layerName == item.layerName) }
                .mapNotNull { it.path }
        } ?: emptyList()
        val offer = LayerFileDeleter.offerDelete(requireContext(), item.path, others)
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("移除确认")
            .setNegativeButton("取消", null)
        val cbDelete = if (offer) {
            CheckBox(requireContext()).apply {
                text = "同时删除图层文件（不可恢复）"
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
        } else null
        if (cbDelete != null) {
            builder.setMessage("确定移除图层：${displayLayerName(item)} ？")
            builder.setView(cbDelete)
        } else {
            builder.setMessage("确定移除图层：${displayLayerName(item)} ？（仅从地图移除，不删除源文件）")
        }
        builder.setPositiveButton("移除") { _, _ ->
            doRemoveLayer(position)
            if (cbDelete?.isChecked == true) {
                LayerFileDeleter.deleteSource(requireContext(), item.path)
            }
        }.show()
    }

    private fun doRemoveLayer(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        if (item.itemType != LayerItem.TYPE_LAYER) return
        val doc = DocumentManager.getInstance().getDocument() ?: return
        // 按 path 定位（分节列表 position 含组头，不再等于文档数组下标）
        val list = if (currentTab == TAB_VECTOR) doc.vectorLayers else doc.rasterLayers
        val idx = list.indexOfFirst { it.path == item.path && it.layerName == item.layerName }
        if (idx >= 0) list.removeAt(idx)

        // 立即保存到文档 -> 通知监听者 -> 刷新列表 -> 从地图移除
        DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    // ---- 分组动作（组可见性扇出写成员 visible，渲染层不认组，与拍照分组同口径）----

    private fun onNewGroup() {
        NameEditDialog.show(requireActivity(), "新建分组", "", "分组名称") { name ->
            DocumentManager.getInstance().addLayerGroup(requireContext(), name)
            syncFromDocument()
        }
    }

    private fun onRenameGroup(item: LayerItem) {
        NameEditDialog.show(requireActivity(), "重命名分组", item.groupName, "分组名称") { name ->
            DocumentManager.getInstance().renameLayerGroup(requireContext(), item.groupId, name)
            syncFromDocument()
        }
    }

    /** 删除分组：组内图层不删除，移到"未分组"。 */
    private fun onRemoveGroup(item: LayerItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除分组")
            .setMessage("删除分组\"${item.groupName}\"后，组内图层不会被移除，将移到\"未分组\"。是否继续？")
            .setPositiveButton("删除") { _, _ ->
                DocumentManager.getInstance().removeLayerGroup(requireContext(), item.groupId)
                collapsedIds.remove(item.groupId)
                syncFromDocument()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 整组显隐：批量写组内每层 visible（含未分组虚拟节），一次 save 一次广播。 */
    private fun onToggleGroupVisible(item: LayerItem, visible: Boolean) {
        DocumentManager.getInstance().setLayerGroupVisible(requireContext(), item.groupId, visible)
        syncFromDocument()
    }

    private fun onToggleCollapse(groupId: Int) {
        if (!collapsedIds.remove(groupId)) collapsedIds.add(groupId)
        syncFromDocument()
    }

    /** 移入组：单选列表（未分组 + 各组 + 新建分组…），选中即写 groupId 落盘。 */
    private fun onMoveGroup(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        if (item.itemType != LayerItem.TYPE_LAYER) return
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val groups = doc.groups
        val labels = mutableListOf("未分组")
        labels.addAll(groups.map { it.name })
        labels.add("新建分组…")
        AlertDialog.Builder(requireContext())
            .setTitle("图层分组")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> moveLayer(item, LayerInfo.UNGROUPED_ID, "")
                    which <= groups.size -> moveLayer(item, groups[which - 1].id, "")
                    else -> NameEditDialog.show(requireActivity(), "新建分组", "", "分组名称") { name ->
                        moveLayer(item, LayerInfo.UNGROUPED_ID, name)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveLayer(item: LayerItem, groupId: Int, newGroupName: String) {
        val path = item.path ?: return
        DocumentManager.getInstance()
            .moveLayerToGroup(
                requireContext(), path, currentTab == TAB_VECTOR, groupId, newGroupName, item.layerName
            )
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
        // 四至优先文档元数据缓存（指纹新鲜即免开 GDAL），未命中回落直读（kmz 走 /vsizip/）
        val extent = VectorMetaCache.extentOf(path, item.layerName)
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
        val fields = runCatching {
            NativeLayerInfo.getVectorFieldNames(path, item.layerName)?.toList()
        }.getOrNull()
        SqlQueryDialog(
            requireContext(),
            path,
            displayLayerName(item),
            item.layerName,
            fields,
            object : SqlQueryDialog.Listener {
                override fun onExecute(sql: String) = handler.invoke(path, item.layerName, sql)
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
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == item.path && it.layerName == item.layerName } ?: return

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
        val oldLayerName = selectedVectorLayerName
        if (oldPath == item.path && oldLayerName == item.layerName) return
        selectedVectorPath = item.path
        selectedVectorLayerName = item.layerName
        if (oldPath != null) {
            val oldPosition = layerItems.indexOfFirst { it.path == oldPath && it.layerName == oldLayerName }
            if (oldPosition >= 0) adapter.notifyItemChanged(oldPosition)
        }
        adapter.notifyItemChanged(position)
    }

    /**
     * 点击矢量图层样式图标：弹出整层样式编辑（复用 [FeatureDetailDialog] 图层样式模式）。
     * 开放范围以能力主表 [VectorFormats.styleEditable] 为准（shp/gpkg/dxf/geojson/json/gdb）；
     * kml/kmz 自带样式、dwg 只读，不开放整层样式编辑。
     */
    private fun onStyleClick(position: Int) {
        val item = layerItems.getOrNull(position) ?: return
        val path = item.path
        if (path.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.layer_style_not_open, Toast.LENGTH_SHORT).show()
            return
        }
        if (VectorFormats.styleEditable(path)) {
            showLayerStyle(item)
        } else {
            Toast.makeText(requireContext(), R.string.layer_style_not_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 整层样式编辑：保存写入 [LayerInfo.vectorStyle] 图层级字段（保留已有单要素覆盖），
     * 递增样式版本后保存文档，经文档变更监听重载图层生效（原数据不变）。
     * 恢复默认回传空样式，仅清除整层覆盖字段。
     */
    private fun showLayerStyle(item: LayerItem) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == item.path && it.layerName == item.layerName } ?: return
        // GDAL 索引格式（shp/gpkg/gdb）支持标注字段下拉（DBF/GPKG 字段名）；其余仅颜色覆盖
        val isIndexed = item.path?.let { VectorFormats.featureStyleEditable(it) } ?: false
        // 标注字段候选：shp 取 DBF 字段清单，gpkg 取 SQLite 表字段清单（均经 GDAL）；kml 标注随源文件不开放设置
        val fieldOptions = if (isIndexed) {
            item.path?.let { NativeLayerInfo.getVectorFieldNames(it, item.layerName)?.toList() }
        } else null
        // 所见即所得：优先取地图上该图层当前实际渲染色（已含文档样式效果），
        // 图层未加载时回退默认渲染色（与 MainActivity 矢量默认样式一致）
        val renderColors = item.path?.let { layerRenderColors?.invoke(it, item.layerName) }
        // 几何类型：样式页按点/线/面分支（线层隐藏填充、描边改名线色）；未加载兜底色线层取线通道默认橙
        val geomType = item.path?.let { VectorMetaCache.geomTypeOf(it, item.layerName) } ?: -1
        // 几何构成掩码：KML 层声明 wkbUnknown（geomType=-1），按要素采样出点/线/面节
        val geomMask = item.path?.let { GdalVectorReader.geomMaskOf(it, item.layerName) } ?: 0
        val hasLine = geomType == 1 || (geomMask and 2) != 0
        val defaultOutline = if (hasLine) DEFAULT_SHAPE_LINE else DEFAULT_SHAPE_OUTLINE

        FeatureDetailDialog(
            R.layout.dialog_feature_detail,
            featureName = null,
            layerName = displayLayerName(item),
            featureId = -1,
            attributes = null,
            initialStyle = layerInfo.vectorStyle?.takeIf { !it.isEmpty() },
            styleEditable = true,
            fallbackFill = renderColors?.first ?: if (isIndexed) DEFAULT_SHAPE_FILL else null,
            fallbackOutline = renderColors?.second ?: if (isIndexed) defaultOutline else null,
            layerStyleMode = true,
            geomType = geomType,
            geomMask = geomMask,
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
                vs.iconKey = style.iconKey
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
     * 矢量图层样式预览图标：取文档整层样式 + 实际渲染色后交统一控件 [LayerStylePreview] 绘制
     * （点/线/面分支与标注叠绘口径全部集中在控件内，与鸿蒙 LayerStylePreview.ets 同构）。
     */
    private fun drawStylePreview(item: LayerItem, widthPx: Int, heightPx: Int): Bitmap? {
        val doc = DocumentManager.getInstance().getDocument()
        val style = doc?.vectorLayers?.firstOrNull { it.path == item.path && it.layerName == item.layerName }?.vectorStyle
        val renderColors = item.path?.let { layerRenderColors?.invoke(it, item.layerName) }
        val geom = item.path?.let { VectorMetaCache.geomTypeOf(it, item.layerName) } ?: -1
        val isKml = item.path?.let { VectorFormats.isKml(it) } ?: false
        val showKmlLabel = doc?.systemConfig?.showKmlLabel ?: true
        // 示例文本与地图渲染内容同口径：取数据首条非空注记值（指纹缓存），取不到回退字段名
        val labelField = LayerStylePreview.effectiveLabelField(style, isKml, showKmlLabel)
        val labelText = if (labelField.isNotEmpty() && !item.path.isNullOrEmpty())
            VectorMetaCache.labelSampleOf(item.path, item.layerName, labelField) else ""
        return LayerStylePreview.draw(requireContext(), style, geom, renderColors, widthPx, heightPx, isKml, showKmlLabel, labelText)
    }

    /**
     * 图层显示名：文档名称 + 文件扩展名（如 kmz 解压后文档名为压缩包名，
     * 需补上实际加载文件扩展名）；名称已含该扩展名或无路径时原样返回。
     */
    private fun displayLayerName(item: LayerItem): String {
        val name = item.name ?: return ""
        val ext = item.path?.let { VectorFormats.extOf(it) } ?: ""
        val base = if (ext.isEmpty() || name.lowercase(Locale.getDefault()).endsWith(".$ext")) {
            name
        } else {
            "$name.$ext"
        }
        return if (item.layerName.isEmpty()) base else "$base / ${item.layerName}"
    }

    /** 矢量图层几何子类型文本：点/线/面要素；混合几何（KML 常见）按构成列组合；未知回退「矢量图层」 */
    private fun vectorGeomTypeText(item: LayerItem): String {
        val path = item.path ?: return getString(R.string.layer_info_type_vector)
        return when (VectorMetaCache.geomTypeOf(path, item.layerName)) {
            0 -> getString(R.string.layer_info_type_point)
            1 -> getString(R.string.layer_info_type_line)
            2 -> getString(R.string.layer_info_type_polygon)
            else -> {
                val mask = GdalVectorReader.geomMaskOf(path, item.layerName)
                val parts = mutableListOf<String>()
                if (mask and 1 != 0) parts.add("点")
                if (mask and 2 != 0) parts.add("线")
                if (mask and 4 != 0) parts.add("面")
                if (parts.isEmpty()) getString(R.string.layer_info_type_vector)
                else parts.joinToString("/") + "要素"
            }
        }
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
            LayerInfo.TYPE_VECTOR -> vectorGeomTypeText(item)
            LayerInfo.TYPE_RASTER -> getString(R.string.layer_info_type_raster)
            else -> item.type ?: ""
        }
        // 坐标系：读取原始文件 SRS（原生返回“名称\nproj4”）；kmz 压缩包走 GDAL /vsizip/ 虚拟文件；
        // 界面只显示第一个名称（截取 proj4 前的首行），读取失败/无 SRS 显示未知
        val srsPath = item.path?.let { VectorFormats.virtualPathOf(it) }
        val srsStr = srsPath?.let { NativeLayerInfo.getLayerSrs(it, item.layerName) }
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

    /**
     * 拖拽排序（三-b）：长按矢量图层行上下拖动改压盖序，**列表顶 = 最上层**。
     * 组头不可拖；拖到组头 = 落入该组（跨组拖动自动移组 + 改序），折叠组被落入时提交后自动展开。
     * 拖动期间只动显示列表（不触文档、不重绘样式预览），抬手一次提交 + 一次 save。
     */
    private inner class LayerDragCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = currentTab == TAB_VECTOR

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val item = adapter.currentItems().getOrNull(viewHolder.adapterPosition)
            val draggable = currentTab == TAB_VECTOR && item != null && item.itemType == LayerItem.TYPE_LAYER
            return if (draggable) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
            val items = adapter.currentItems()
            val dragged = items.getOrNull(from) ?: return false
            if (dragged.itemType != LayerItem.TYPE_LAYER) return false
            val dest = items.getOrNull(to) ?: return false
            if (dest.itemType == LayerItem.TYPE_GROUP_HEADER) {
                // 落到组头 = 进入该组：折叠组恒落组内显示首位（提交后展开）；
                // 展开组向下拖 = 显示首位（组内最上层），向上拖 = 显示末位（组内最下层）
                val insert = if (collapsedIds.contains(dest.groupId)) {
                    if (to > from) to else to + 1
                } else {
                    val end = sectionEnd(items, to)
                    if (to > from) to else (if (from < end) end - 1 else end)
                }
                adapter.moveItem(from, insert)
                return true
            }
            if (dest.itemType != LayerItem.TYPE_LAYER) return false
            adapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                viewHolder?.itemView?.alpha = 0.75f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            dragging = false
            commitDragOrder()
        }

        /** 组头节在显示列表中的结束下标（下一个组头或列表尾，不含） */
        private fun sectionEnd(items: List<LayerItem>, headerIndex: Int): Int {
            var i = headerIndex + 1
            while (i < items.size && items[i].itemType != LayerItem.TYPE_GROUP_HEADER) i++
            return i
        }
    }

    /**
     * 拖拽落盘：按显示序反推 doc 数组（**数组首 = 最下层**）。分节是连续块，故「节逆序 × 节内逆序」
     * 等价于全部显示行逆序；折叠节无显示行，其成员按既有数组序原样插回，被拖入者置其上并展开该节。
     * 跨节落点的行 groupId 改为落点节 id。一次 save → 既有监听广播 → sync 尾循环下发
     * setVectorLayerOrder（不 removeVectorLayer、不重建界面）。
     */
    private fun commitDragOrder() {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val oldOrder = ArrayList(doc.vectorLayers)
        val rows = adapter.currentItems()
        // 显示行按节归拢（节 id + 节内自上而下的行）
        val sectionIds = ArrayList<Int>()
        val sectionRows = ArrayList<MutableList<LayerItem>>()
        for (r in rows) {
            when (r.itemType) {
                LayerItem.TYPE_GROUP_HEADER -> {
                    sectionIds.add(r.groupId)
                    sectionRows.add(ArrayList())
                }
                LayerItem.TYPE_LAYER -> sectionRows.lastOrNull()?.add(r)
            }
        }
        val byKey = HashMap<String, LayerInfo>()
        for (li in doc.vectorLayers) byKey["${li.path}|${li.layerName}"] = li
        val placed = HashSet<LayerInfo>()
        val reordered = ArrayList<LayerInfo>(doc.vectorLayers.size)
        var groupChanged = false
        // 节内显示行（自上而下）→ 数组（自下而上）；跨节落点即移组
        fun placeRows(gid: Int, displayed: List<LayerItem>) {
            for (r in displayed.asReversed()) {
                val li = byKey["${r.path}|${r.layerName}"] ?: continue
                if (!placed.add(li)) continue
                if (li.groupId != gid) {
                    li.groupId = gid
                    groupChanged = true
                }
                reordered.add(li)
            }
        }
        for (s in sectionIds.indices.reversed()) {
            val gid = sectionIds[s]
            val displayed = sectionRows[s]
            if (collapsedIds.contains(gid)) {
                // 折叠节其余成员未显示：保持既有数组序（本就是下→上），被拖入者置其上，提交后展开该节
                for (li in oldOrder) {
                    if (li.groupId == gid && placed.add(li)) reordered.add(li)
                }
                placeRows(gid, displayed)
                if (displayed.isNotEmpty()) collapsedIds.remove(gid)
            } else {
                placeRows(gid, displayed)
            }
        }
        // 兜底：不属于任何显示节的层（孤儿 groupId）保持原序置于最下层，绝不丢层
        val orphans = ArrayList<LayerInfo>()
        for (li in oldOrder) {
            if (placed.add(li)) orphans.add(li)
        }
        if (orphans.isNotEmpty()) reordered.addAll(0, orphans)
        if (!groupChanged && reordered == oldOrder) {
            syncFromDocument()
            return
        }
        doc.vectorLayers.clear()
        doc.vectorLayers.addAll(reordered)
        AppLog.i("LayerManageDialog", "拖拽排序落盘：${reordered.size} 层，移组=$groupChanged")
        DocumentManager.getInstance().save(requireContext())
        syncFromDocument()
    }

    internal class LayerItem(
        var name: String?,
        var path: String?,
        var type: String?,
        var visible: Boolean,
        var itemType: Int
    ) {
        var isSystem: Boolean = false
        var layerName: String = ""

        /** 样式版本用于 DiffUtil 感知图标预览所需的文档样式变化。 */
        var styleVersion: Int = 0

        /** 矢量图层级别可见性生效值（[LayerInfo.effectiveMinDisplayLevel]）：≥0 时级别按键显示数字徽标，-1 不限制无徽标 */
        var minLevel: Int = -1

        /** 所属分组 id（图层行）；组头行即组 id（-1 = 未分组虚拟节） */
        var groupId: Int = LayerInfo.UNGROUPED_ID

        // ---- 组头行字段 ----
        var groupName: String = ""
        var count: Int = 0
        var allVisible: Boolean = true
        var collapsed: Boolean = false

        companion object {
            const val TYPE_LAYER = 0
            const val TYPE_MAP_SOURCE = 1
            const val TYPE_GROUP_HEADER = 2
        }
    }

    private inner class LayerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: ArrayList<LayerItem> = ArrayList()

        /** 拖拽期间的显示序（落盘前只在内存里动，clearView 时一次提交） */
        fun currentItems(): List<LayerItem> = items

        /** 拖拽移动一行：只动显示列表 + notifyItemMoved，不触文档、不重绘预览 */
        fun moveItem(from: Int, to: Int) {
            if (from == to || from !in items.indices || to !in items.indices) return
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }

        fun setItems(list: List<LayerItem>) {
            val newItems = ArrayList(list)
            val oldItems = items
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = newItems[newItemPosition]
                    if (oldItem.itemType != newItem.itemType) return false
                    return when (oldItem.itemType) {
                        LayerItem.TYPE_GROUP_HEADER -> oldItem.groupId == newItem.groupId
                        LayerItem.TYPE_MAP_SOURCE -> oldItem.path == newItem.path
                        else -> oldItem.path == newItem.path &&
                                oldItem.layerName == newItem.layerName && oldItem.type == newItem.type
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = newItems[newItemPosition]
                    return oldItem.name == newItem.name && oldItem.path == newItem.path &&
                            oldItem.type == newItem.type && oldItem.visible == newItem.visible &&
                            oldItem.itemType == newItem.itemType && oldItem.isSystem == newItem.isSystem &&
                            oldItem.layerName == newItem.layerName &&
                            oldItem.styleVersion == newItem.styleVersion &&
                            oldItem.minLevel == newItem.minLevel && oldItem.groupId == newItem.groupId &&
                            oldItem.groupName == newItem.groupName && oldItem.count == newItem.count &&
                            oldItem.allVisible == newItem.allVisible && oldItem.collapsed == newItem.collapsed
                }
            })
            items = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int): Int {
            return items[position].itemType
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                LayerItem.TYPE_MAP_SOURCE ->
                    MapSourceViewHolder(inflater.inflate(R.layout.item_map_source, parent, false))
                LayerItem.TYPE_GROUP_HEADER ->
                    GroupViewHolder(inflater.inflate(R.layout.item_layer_group, parent, false))
                else ->
                    LayerViewHolder(inflater.inflate(R.layout.item_layer_manage, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is LayerViewHolder -> holder.bind(item)
                is MapSourceViewHolder -> holder.bind(item)
                is GroupViewHolder -> holder.bind(item)
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
        private val ivGroup: ImageView = itemView.findViewById(R.id.item_layer_iv_group)
        private val ivVisibility: ImageView = itemView.findViewById(R.id.item_layer_iv_visibility)
        private val ivInfo: ImageView = itemView.findViewById(R.id.item_layer_iv_info)
        private val ivDelete: ImageView = itemView.findViewById(R.id.item_layer_iv_delete)

        fun bind(item: LayerItem) {
            // 图层项显示带扩展名的名称（地图源项不适用）
            tvName.text = displayLayerName(item)

            // 选中矢量图层名称粗体高亮，未选中恢复常规字重
            val selected = currentTab == TAB_VECTOR && item.path != null &&
                item.path == selectedVectorPath && item.layerName == selectedVectorLayerName
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
            val sqlQueryable = isVector && (item.path?.let { VectorFormats.sqlQueryable(it) } ?: false)
            ivSql.visibility = if (sqlQueryable) View.VISIBLE else View.GONE
            ivSql.setOnClickListener { onSqlQueryClick(adapterPosition) }

            // 级别可见性对所有矢量图层开放（shp/kml/kmz/dwg/dxf），显隐切到外层容器（回收复用时需显式隐藏）；
            // 右下角徽标显示当前生效级别：有数字即级别可见性已启用，不限制（-1）时无徽标。
            // 栅格行不再占用该槽位（「全幅/分瓦」模式切换已下线，恒走分瓦路）。
            flLevel.visibility = if (isVector) View.VISIBLE else View.GONE
            ivLevel.setOnClickListener { onLevelClick(adapterPosition) }
            if (item.minLevel >= 0) {
                tvLevelBadge.text = String.format(Locale.getDefault(), "%d", item.minLevel)
                tvLevelBadge.visibility = View.VISIBLE
            } else {
                tvLevelBadge.visibility = View.GONE
            }

            ivZoomTo.setOnClickListener { onZoomToLayer(adapterPosition) }

            ivGroup.setOnClickListener { onMoveGroup(adapterPosition) }

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
     * 分组头行：折叠箭头 + 组名 + 计数 + 整组显隐开关；真实组另有重命名/删除
     * （照 MediaManageDialog.HeaderHolder，未分组虚拟组不可重命名/删除）。
     */
    private inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val arrow: ImageView = itemView.findViewById(R.id.item_layer_group_arrow)
        private val tvName: TextView = itemView.findViewById(R.id.item_layer_group_name)
        private val tvCount: TextView = itemView.findViewById(R.id.item_layer_group_count)
        private val switchVisible: SwitchCompat = itemView.findViewById(R.id.item_layer_group_visible)
        private val tvRename: TextView = itemView.findViewById(R.id.item_layer_group_rename)
        private val ivDelete: ImageView = itemView.findViewById(R.id.item_layer_group_delete)

        fun bind(item: LayerItem) {
            tvName.text = item.groupName
            tvCount.text = if (item.count > 0) "${item.count} 个图层" else "无图层"
            // 折叠箭头：展开指向下（-90°），折叠指向右（180°）
            arrow.rotation = if (item.collapsed) 180f else -90f
            arrow.setOnClickListener { onToggleCollapse(item.groupId) }
            tvName.setOnClickListener { onToggleCollapse(item.groupId) }

            val ungrouped = item.groupId == LayerInfo.UNGROUPED_ID
            tvRename.visibility = if (ungrouped) View.GONE else View.VISIBLE
            ivDelete.visibility = if (ungrouped) View.GONE else View.VISIBLE
            tvRename.setOnClickListener { onRenameGroup(item) }
            ivDelete.setOnClickListener { onRemoveGroup(item) }

            // 先设状态再挂监听，避免回显触发批量写库
            switchVisible.setOnCheckedChangeListener(null)
            switchVisible.isChecked = item.allVisible
            switchVisible.isEnabled = item.count > 0
            switchVisible.setOnCheckedChangeListener { _, isChecked ->
                onToggleGroupVisible(item, isChecked)
            }
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

        /** 无样式覆盖时的默认渲染色：单一真源在统一预览控件 [LayerStylePreview] */
        private const val DEFAULT_SHAPE_FILL = LayerStylePreview.DEFAULT_SHAPE_FILL
        private const val DEFAULT_SHAPE_OUTLINE = LayerStylePreview.DEFAULT_SHAPE_OUTLINE
        private const val DEFAULT_SHAPE_LINE = LayerStylePreview.DEFAULT_SHAPE_LINE
    }
}