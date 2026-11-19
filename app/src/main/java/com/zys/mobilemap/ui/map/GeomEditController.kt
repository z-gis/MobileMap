package com.zys.mobilemap.ui.map

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 几何编辑控制器（gdb/shp 面/线/点要素，与鸿蒙 GeomEditController 同口径）：
 * 长按命中 gdb/shp 要素 → [enter] 进入编辑态（分割/删除/合并/退出），线/点层仅删除；
 * 分割=画折线（≥2 点）后按线切面（最大块留原 FID）；删除=二次确认后真删；
 * 合并=提示点选同一图层另一面图斑，确认后并入当前要素（保留当前 FID 与属性）。
 *
 * 写库前 native 自动整份备份（进程内首次写触发，backupDir=[android.content.Context.getFilesDir]/geom_backup）；
 * 成功后经 [MapLayerManager.reloadVectorLayer] 就地重载矢量层并重新高亮目标。
 * 采集态 [capturing] 由宿主组合进 BusinessOverlayManager 的 isCapturing 门控，保护分割线 live 层不被重建清除。
 */
internal class GeomEditController(
    private val activity: AppCompatActivity,
    private val mapView: NativeMapView,
    private val layerManager: MapLayerManager,
    private val pick: FeaturePickController,
    private val scope: CoroutineScope,
    private val controls: GeomEditControls,
    private val onPhaseChanged: (GeomEditPhase) -> Unit
) {

    enum class GeomEditPhase { NONE, EDIT, SPLIT_LINE, MERGE_PICK }

    private var phase = GeomEditPhase.NONE
    private var target: PickedFeature? = null
    private var targetIsPolygon = false

    // 分割线采集点串（[lat,lon,0]）+ live 叠加层索引（折线层 + 顶点圆点层，均 noPick）
    private val splitPoints = mutableListOf<DoubleArray>()
    private var splitLineLiveIndex = -1
    private var splitMarkerLiveIndex = -1

    val capturing: Boolean get() = phase != GeomEditPhase.NONE

    /**
     * 进入编辑态：长按已高亮的 [PickedFeature]（gdb/shp 由宿主菜单门控），
     * 经 native featureGeometry 判面/线/点（无文件 IO），面开放分割/合并，线/点仅删除。
     */
    fun enter(feature: PickedFeature) {
        val fg = mapView.featureGeometry(feature.layerIndex, feature.fid)
        if (fg == null) {
            Toast.makeText(activity, "无法读取要素几何", Toast.LENGTH_SHORT).show()
            return
        }
        target = feature
        targetIsPolygon = fg.type == 2
        setPhase(GeomEditPhase.EDIT)
        Toast.makeText(activity, "几何编辑：要素 #${feature.fid}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 编辑态单击消费（由 [FeaturePickController.onMapTap] 门控转发）：
     * 分割线态=屏幕点转经纬度加点并实时画线；合并态=拾取另一面图斑作合并靶；编辑态不响应。
     */
    fun handleTap(x: Float, y: Float) {
        when (phase) {
            GeomEditPhase.SPLIT_LINE -> {
                val geo = mapView.screenToGeo(x, y) ?: return
                splitPoints.add(doubleArrayOf(geo.latitude, geo.longitude, 0.0))
                renderSplitLive()
                updateHint()
                Toast.makeText(activity, "分割线：已点 ${splitPoints.size} 个点", Toast.LENGTH_SHORT).show()
            }
            GeomEditPhase.MERGE_PICK -> pickMergeTarget(x, y)
            else -> Unit
        }
    }

    /** 开始分割：进入画线态，建橙色 live 折线层 + 顶点圆点层（noPick，不参与拾取竞争） */
    fun startSplit() {
        if (phase != GeomEditPhase.EDIT || !targetIsPolygon) return
        splitPoints.clear()
        splitLineLiveIndex = addSplitLiveLayer(MapStyleColors.MEASURE_POINT_RADIUS_DP)
        splitMarkerLiveIndex = addSplitLiveLayer(MapStyleColors.MEASURE_POINT_RADIUS_DP)
        if (splitLineLiveIndex >= 0) mapView.setOverlayNoPick(splitLineLiveIndex, true)
        if (splitMarkerLiveIndex >= 0) mapView.setOverlayNoPick(splitMarkerLiveIndex, true)
        setPhase(GeomEditPhase.SPLIT_LINE)
    }

    private fun addSplitLiveLayer(pointRadiusDp: Float): Int = mapView.addOverlayLayer(
        NativeVectorStyle(
            fillColor = SPLIT_LINE_COLOR, outlineColor = SPLIT_LINE_COLOR, outlineWidth = MapStyleColors.MEASURE_LIVE_WIDTH,
            lineColor = SPLIT_LINE_COLOR, lineWidth = MapStyleColors.MEASURE_LIVE_WIDTH, pointColor = SPLIT_LINE_COLOR,
            pointRadiusDp = pointRadiusDp
        )
    )

    /** 撤销上一个分割线点并重绘（回退到 0 点即清空 live 层） */
    fun undoSplitPoint() {
        if (phase != GeomEditPhase.SPLIT_LINE || splitPoints.isEmpty()) return
        splitPoints.removeAt(splitPoints.size - 1)
        renderSplitLive()
        updateHint()
    }

    /**
     * 完成分割：≥2 点拼 WGS84 WKT LINESTRING(lon lat,…)，IO 线程调 native splitByLine
     * （首末段自动延长贯穿、面积闭合门禁、最大块留原 FID）；成功重载图层并重新高亮原 FID，
     * 失败 Toast 具体原因并留在画线态（点串保留可补点重试）。
     */
    fun finishSplit() {
        if (phase != GeomEditPhase.SPLIT_LINE) return
        if (splitPoints.size < 2) {
            Toast.makeText(activity, "至少需要两个点", Toast.LENGTH_SHORT).show()
            return
        }
        val t = target ?: return
        val wkt = splitPoints.joinToString(", ", prefix = "LINESTRING(", postfix = ")") {
            String.format(Locale.US, "%.8f %.8f", it[1], it[0])
        }
        val path = t.path
        val layerName = t.layerName
        val fid = t.fid
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                GdalVectorReader.splitByLine(path, fid, wkt, layerName, backupDir())
            }
            if (phase == GeomEditPhase.NONE) return@launch
            if (res.ok) {
                teardownSplitLive()
                setPhase(GeomEditPhase.EDIT)
                reloadAndRehighlight(fid)
                Toast.makeText(activity, "分割完成，共 ${res.count} 块", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, res.error.ifEmpty { "分割失败" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 删除要素：二次确认后 IO 线程真删（native 写前自动备份；shp 为标记删除），成功退出编辑态 */
    fun requestDelete() {
        val t = target ?: return
        if (phase != GeomEditPhase.EDIT) return
        AlertDialog.Builder(activity)
            .setTitle("删除要素")
            .setMessage("确定删除要素 #${t.fid}？删除前会自动备份数据。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val path = t.path
                val layerName = t.layerName
                val fid = t.fid
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        GdalVectorReader.deleteFeature(path, fid, layerName, backupDir())
                    }
                    if (phase == GeomEditPhase.NONE) return@launch
                    if (res.ok) {
                        pick.clearSelectedHighlight()
                        layerManager.reloadVectorLayer(path, layerName)
                        exit()
                        Toast.makeText(activity, "要素已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, res.error.ifEmpty { "删除失败" }, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    /** 进入合并选靶态：提示点击同一图层的另一面图斑 */
    fun startMerge() {
        if (phase != GeomEditPhase.EDIT || !targetIsPolygon) return
        setPhase(GeomEditPhase.MERGE_PICK)
        Toast.makeText(activity, "请点击要合并的另一个面图斑（同一图层）", Toast.LENGTH_LONG).show()
    }

    /**
     * 合并选靶：拾取命中须为同 path+layerName 的另一面要素（fid 不同），二次确认后 IO 线程
     * mergeFeatures（保留当前要素 FID 与属性，删除靶要素；不相离时结果为多部件）。
     */
    private fun pickMergeTarget(x: Float, y: Float) {
        val t = target ?: return
        val hit = mapView.pickVector(x, y) ?: return
        if (hit.size < 2) return
        val layerIndex = hit[0].toInt()
        val fid = hit[1]
        val info = layerManager.layerInfoByIndex(layerIndex) ?: return
        if (info.path != t.path || info.layerName != t.layerName) {
            Toast.makeText(activity, "请选择同一图层的图斑", Toast.LENGTH_SHORT).show()
            return
        }
        if (fid == t.fid) {
            Toast.makeText(activity, "不能与自身合并", Toast.LENGTH_SHORT).show()
            return
        }
        val fg = mapView.featureGeometry(layerIndex, fid)
        if (fg == null || fg.type != 2) {
            Toast.makeText(activity, "请选择面图斑", Toast.LENGTH_SHORT).show()
            return
        }
        val path = t.path
        val layerName = t.layerName
        val keepFid = t.fid
        AlertDialog.Builder(activity)
            .setTitle("合并图斑")
            .setMessage("将要素 #$fid 并入当前要素 #$keepFid？保留当前要素的属性，合并前会自动备份数据。")
            .setNegativeButton("取消", null)
            .setPositiveButton("合并") { _, _ ->
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        GdalVectorReader.mergeFeatures(path, keepFid, fid, layerName, backupDir())
                    }
                    if (phase == GeomEditPhase.NONE) return@launch
                    if (res.ok) {
                        setPhase(GeomEditPhase.EDIT)
                        reloadAndRehighlight(keepFid)
                        Toast.makeText(activity, "合并完成", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, res.error.ifEmpty { "合并失败" }, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    /** 取消子模式（画线/选靶）回编辑态；编辑态下调用即整体退出（返回键分档） */
    fun cancelSubMode() {
        when (phase) {
            GeomEditPhase.SPLIT_LINE -> {
                teardownSplitLive()
                setPhase(GeomEditPhase.EDIT)
            }
            GeomEditPhase.MERGE_PICK -> setPhase(GeomEditPhase.EDIT)
            GeomEditPhase.EDIT -> exit()
            else -> Unit
        }
    }

    /** 退出编辑态：拆 live 层、清高亮、清状态并通知宿主恢复 HUD */
    fun exit() {
        teardownSplitLive()
        pick.clearSelectedHighlight()
        target = null
        targetIsPolygon = false
        setPhase(GeomEditPhase.NONE)
    }

    /** 重载目标图层后重新高亮（native 层重建异步，featureGeometry 首试未就绪则 600ms 后重试一次） */
    private fun reloadAndRehighlight(fid: Long) {
        val t = target ?: return
        val path = t.path
        val layerName = t.layerName
        pick.clearSelectedHighlight()
        layerManager.reloadVectorLayer(path, layerName)
        val attempt = object : Runnable {
            var retried = false
            override fun run() {
                if (phase != GeomEditPhase.EDIT || target?.fid != fid) return
                val idx = layerManager.nativeIndexOfVector(path, layerName)
                if (idx >= 0 && mapView.featureGeometry(idx, fid) != null) {
                    pick.highlightSelectedFeature(idx, fid, path, layerName)
                    target = PickedFeature(idx, fid, path, layerName)
                } else if (!retried) {
                    retried = true
                    mapView.postDelayed(this, 600)
                }
            }
        }
        mapView.post(attempt)
    }

    /** 实时重绘分割线 live 层：顶点圆点逐点即时反馈，≥2 点连折线；点数不足推空几何清残留 */
    private fun renderSplitLive() {
        if (splitMarkerLiveIndex >= 0) {
            if (splitPoints.isEmpty()) {
                mapView.updateOverlayPoints(splitMarkerLiveIndex, DoubleArray(0))
            } else {
                mapView.updateOverlayPoints(splitMarkerLiveIndex, BusinessOverlayManager.flattenLonLat(splitPoints))
            }
        }
        if (splitLineLiveIndex >= 0) {
            if (splitPoints.size < 2) {
                mapView.updateOverlayLines(splitLineLiveIndex, DoubleArray(0), IntArray(0), LongArray(0), emptyArray())
            } else {
                mapView.updateOverlayLines(
                    splitLineLiveIndex, BusinessOverlayManager.flattenLonLat(splitPoints),
                    intArrayOf(splitPoints.size), longArrayOf(0L), emptyArray()
                )
            }
        }
    }

    private fun teardownSplitLive() {
        splitPoints.clear()
        if (splitLineLiveIndex >= 0) {
            mapView.removeOverlayLayer(splitLineLiveIndex)
            splitLineLiveIndex = -1
        }
        if (splitMarkerLiveIndex >= 0) {
            mapView.removeOverlayLayer(splitMarkerLiveIndex)
            splitMarkerLiveIndex = -1
        }
    }

    /** 几何写前自动备份目录（native 进程内首次写触发整份备份，失败拒写） */
    private fun backupDir(): String =
        File(activity.filesDir, "geom_backup").apply { mkdirs() }.absolutePath

    /** 切相位：刷新工具条按钮显隐与提示文本，并通知宿主门控 HUD */
    private fun setPhase(p: GeomEditPhase) {
        phase = p
        val edit = p == GeomEditPhase.EDIT
        val split = p == GeomEditPhase.SPLIT_LINE
        val merge = p == GeomEditPhase.MERGE_PICK
        controls.container.visibility = if (p == GeomEditPhase.NONE) View.GONE else View.VISIBLE
        controls.split.visibility = if (edit && targetIsPolygon) View.VISIBLE else View.GONE
        controls.delete.visibility = if (edit) View.VISIBLE else View.GONE
        controls.merge.visibility = if (edit && targetIsPolygon) View.VISIBLE else View.GONE
        controls.exit.visibility = if (edit) View.VISIBLE else View.GONE
        controls.undo.visibility = if (split) View.VISIBLE else View.GONE
        controls.finish.visibility = if (split) View.VISIBLE else View.GONE
        controls.cancel.visibility = if (split || merge) View.VISIBLE else View.GONE
        updateHint()
        onPhaseChanged(p)
    }

    private fun updateHint() {
        val t = target
        controls.hint.text = when (phase) {
            GeomEditPhase.SPLIT_LINE -> "画分割线：已点 ${splitPoints.size} 个点（至少 2 点后点「完成」）"
            GeomEditPhase.MERGE_PICK -> "请点击要合并的另一个面图斑（同一图层）"
            GeomEditPhase.EDIT -> "几何编辑：要素 #${t?.fid ?: -1}" +
                if (!targetIsPolygon) "（线/点仅支持删除）" else ""
            GeomEditPhase.NONE -> ""
        }
    }

    companion object {
        /** 分割线 live 层橙色（与测量默认色区分，明示临时构造线） */
        private const val SPLIT_LINE_COLOR = 0xFFFF6D00.toInt()
    }
}

/** 几何编辑工具条视图组（activity_main.xml 底部居中容器 + 提示文本 + 7 按钮），宿主 findViewById 后注入 */
internal class GeomEditControls(
    val container: LinearLayout,
    val hint: TextView,
    val split: Button,
    val delete: Button,
    val merge: Button,
    val exit: Button,
    val undo: Button,
    val finish: Button,
    val cancel: Button
)