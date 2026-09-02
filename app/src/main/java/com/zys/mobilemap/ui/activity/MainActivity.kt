package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.zys.mobilemap.R
import com.zys.mobilemap.databinding.ActivityMainBinding
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.location.LocationModel
import com.zys.mobilemap.location.NavigatorModel
import com.zys.mobilemap.measure.MeasureModel
import com.zys.mobilemap.media.MediaCaptureHelper
import com.zys.mobilemap.media.MediaOverlayModel
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.track.TrackModel
import com.zys.mobilemap.ui.dialog.FeatureDetailDialog
import com.zys.mobilemap.ui.dialog.LayerManageDialog
import com.zys.mobilemap.ui.dialog.MediaManageDialog
import com.zys.mobilemap.ui.dialog.MoveToDialog
import com.zys.mobilemap.ui.dialog.PhotoSheetDialog
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.GeoCalc
import com.zys.mobilemap.vector.LocalVectorLoader
import com.zys.mobilemap.vector.PickedFeatureInfo
import com.zys.mobilemap.vector.VectorFileImporter
import com.zys.mobilemap.vector.VectorPickModel
import earth.worldwind.PickedObjectList
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : BaseMapActivity() {

    lateinit var binding: ActivityMainBinding

    private var savedCenterLon = Double.Companion.NaN
    private var savedCenterLat = Double.Companion.NaN

    private var locationDisplayManager: LocationModel? = null
    private var navigatorModel: NavigatorModel? = null
    private var vectorPickModel: VectorPickModel? = null

    private var mediaCaptureHelper: MediaCaptureHelper? = null
    private var mediaOverlayModel: MediaOverlayModel? = null
    private var trackModel: TrackModel? = null
    private var measureModel: MeasureModel? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1002
        private const val TAG = "MainActivity"

        /** 拍照标识"缩放至"目标相机高度（米） */
        private const val MEDIA_ZOOM_ALTITUDE = 1200.0
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 拍照会话：尽早注册 Activity 结果回调（拍照完成后刷新标注图层）
        mediaCaptureHelper = MediaCaptureHelper(this) { mediaOverlayModel?.reload() }
        mediaCaptureHelper?.register()

        binding.globe.addView(createWorldWindow())

        // 设置导航器
        navigatorModel = NavigatorModel(this, getWorldWindow())
        navigatorModel?.setup()

        // 测量工具模块：测量模式下拦截地图点击加点（优先于矢量拾取）
        measureModel = MeasureModel(this, binding, getWorldWindow(), lifecycleScope)
        measureModel?.setup()
        measureModel?.reloadSaved()

        // 拍照标注图层：默认读取媒体数据库渲染，点击点位查看照片列表（优先于矢量要素）
        mediaOverlayModel = MediaOverlayModel(this, getWorldWindow())
        mediaOverlayModel?.attach()
        mediaOverlayModel?.reload()

        // 轨迹记录模块：开始/暂停/停止 + 历史轨迹渲染
        trackModel = TrackModel(this, binding, getWorldWindow())
        trackModel?.setup()
        trackModel?.reloadSavedTracks()

        // 矢量要素点击拾取：高亮选中并弹出要素详情（样式编辑/属性显示）；
        // 测量模式拦截点击，已保存测量几何/媒体标注优先于矢量要素
        vectorPickModel = VectorPickModel(
            lifecycleScope,
            getWorldWindow(),
            onFeaturePicked = { info ->
                if (info != null) showFeatureDetail(info)
            },
            onBeforePick = { x, y -> measureModel?.onMapTap(x, y) ?: false },
            onPicked = { x, y, picked ->
                measureModel?.handlePickedGeometry(x, y, picked) == true || handleMediaPicked(picked)
            }
        )
        vectorPickModel?.setup(this)

        // 读取文档
        val doc = DocumentManager.Companion.getInstance().getDocument()
        if (doc != null) {
            syncMapLayers(doc)
        }
        DocumentManager.Companion.getInstance().registerDocumentChangeListener(this)

        // 初始化定位
        locationDisplayManager = LocationModel(this, getWorldWindow()) {
            permissions, requestCode -> requestPermissions(permissions, requestCode)
        }
        locationDisplayManager?.init()

        updateCenterCrossVisibility()
        updateAnnotationIcon()

        binding.mainIvZoomIn.setOnClickListener { navigatorModel?.zoomIn() }
        binding.mainIvZoomOut.setOnClickListener { navigatorModel?.zoomOut() }

        // 注记图层开关：控制天地图-注记图层可见性
        binding.mainIvIsCIA.setOnClickListener { toggleAnnotationLayer() }

        binding.mainIvSetting.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.mainIvGotoLocation.setOnClickListener({ v -> gotoLocation() })
        binding.mainTvLocation.setOnClickListener({ v -> showMoveToDialog() })

        binding.mainIvLayerManage.setOnClickListener { v ->
            LayerManageDialog(
                R.layout.dialog_layer_manage,
                onZoomToExtent = { minLon, minLat, maxLon, maxLat ->
                    zoomToExtent(minLon, minLat, maxLon, maxLat)
                },
                layerRenderColors = { path -> layerRenderColorsOf(path) }
            ).show(supportFragmentManager, "LayerManageDialog")
        }

        // 拍照入口：列表对话框（拍照/设置）
        binding.mainIvPoto.setOnClickListener { showPhotoMenu() }

        // 系统外部打开矢量数据（文件管理器分享/打开）
        handleViewIntent(intent)
    }

    /**
     * 拍照入口菜单：选择拍照进入拍摄会话（连拍），选择设置管理在库拍照标识
     * （重命名/可见性/缩放至/删除）。
     */
    private fun showPhotoMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("拍照", "设置")) { _, which ->
                when (which) {
                    0 -> mediaCaptureHelper?.launchPhotoCapture()
                    1 -> MediaManageDialog.show(
                        this,
                        onChanged = { mediaOverlayModel?.reload() },
                        onZoomTo = { placemark -> zoomToMediaPlacemark(placemark) }
                    )
                }
            }
            .show()
    }

    /**
     * 缩放至拍照点位：相机移至点位坐标（保持当前姿态），重绘地图。
     */
    private fun zoomToMediaPlacemark(placemark: MediaPlacemark) {
        val camera = getWorldWindow().engine.camera
        camera.set(
            Angle.Companion.fromDegrees(placemark.latitude),
            Angle.Companion.fromDegrees(placemark.longitude),
            MEDIA_ZOOM_ALTITUDE,
            AltitudeMode.ABSOLUTE,
            camera.heading,
            camera.tilt,
            camera.roll
        )
        getWorldWindow().requestRedraw()
    }

    /**
     * 媒体标注拾取优先：命中拍照点位时弹出该点位照片列表，并消费该点击。
     */
    private fun handleMediaPicked(picked: PickedObjectList): Boolean {
        val model = mediaOverlayModel ?: return false
        if (!model.layer.isEnabled) return false
        val po = picked.objects.firstOrNull {
            it.layer?.getUserProperty(MediaOverlayModel.MEDIA_LAYER_KEY) as? Boolean == true
        } ?: return false
        val placemark = model.findPlacemark(po.userObject as? Renderable) ?: return false
        PhotoSheetDialog.show(supportFragmentManager, placemark.id, placemark.name)
        return true
    }

    /**
     * 要素详情对话框：样式编辑 + 属性表显示。
     * 样式保存写入图层 [com.zys.mobilemap.doc.LayerInfo.vectorStyle] 的单要素覆盖（[com.zys.mobilemap.doc.VectorStyle.featureStyles]），
     * 递增样式版本后保存文档，经文档变更监听重载图层生效（原数据不变）。
     * kml/kmz 无要素 id（待二期）、dwg/dxf 不改样式，仅属性表可见。
     */
    private fun showFeatureDetail(info: PickedFeatureInfo) {
        val doc = DocumentManager.Companion.getInstance().getDocument() ?: return
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == info.layerPath }
        val ext = info.layerPath?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: ""
        val styleEditable = ext == "shp" && info.featureId >= 0

        // 样式初值：单要素覆盖优先，缺省继承整层样式（供控件回显）
        val layerStyle = layerInfo?.vectorStyle
        val featureStyle = layerStyle?.featureStyles?.get(info.featureId)
        val merged = VectorStyle().apply {
            fillColor = featureStyle?.fillColor ?: layerStyle?.fillColor
            fillOpacity = featureStyle?.fillOpacity ?: layerStyle?.fillOpacity
            outlineColor = featureStyle?.outlineColor ?: layerStyle?.outlineColor
            outlineWidth = featureStyle?.outlineWidth ?: layerStyle?.outlineWidth
            labelField = featureStyle?.labelField ?: layerStyle?.labelField
            labelColor = featureStyle?.labelColor ?: layerStyle?.labelColor
            labelSize = featureStyle?.labelSize ?: layerStyle?.labelSize
            labelOutline = featureStyle?.labelOutline ?: layerStyle?.labelOutline
            labelOutlineColor = featureStyle?.labelOutlineColor ?: layerStyle?.labelOutlineColor
            iconKey = featureStyle?.iconKey ?: layerStyle?.iconKey
        }

        FeatureDetailDialog(
            R.layout.dialog_feature_detail,
            featureName = info.name,
            layerName = info.layerName,
            featureId = info.featureId,
            attributes = info.attributes,
            description = info.description,
            initialStyle = merged.takeIf { !it.isEmpty() },
            styleEditable = styleEditable,
            fallbackFill = renderColorsOf(info.renderable).first,
            fallbackOutline = renderColorsOf(info.renderable).second,
            isPointFeature = info.renderable is Placemark,
            geometryText = featureGeometryText(info.renderable)
        ) { style ->
            val li = layerInfo ?: return@FeatureDetailDialog
            val vs = li.vectorStyle ?: VectorStyle().also { li.vectorStyle = it }
            if (style.isEmpty()) vs.featureStyles.remove(info.featureId)
            else vs.featureStyles[info.featureId] = style
            li.styleVersion++
            // 保存后图层将重载，旧渲染对象失效，先取消选中态
            vectorPickModel?.clearSelection()
            DocumentManager.Companion.getInstance().save(this)
        }.show(supportFragmentManager, "FeatureDetailDialog")
    }

    /**
     * 渲染对象当前实际渲染色（#AARRGGBB）：无文档样式覆盖时色块回显所见即所得。
     * 面/线取填充/描边色；点取图标着色，无描边色。
     */
    private fun renderColorsOf(renderable: Renderable): Pair<String?, String?> = when (renderable) {
        is AbstractShape -> colorToHex(renderable.attributes.interiorColor) to
                colorToHex(renderable.attributes.outlineColor)
        is Placemark -> colorToHex(renderable.attributes.imageColor) to null
        else -> null to null
    }

    /**
     * 要素几何量算：面要素返回面积+周长（带洞多边形按各环带符号面积代数和），
     * 线要素返回长度；点要素无量算信息返回 null（对话框隐藏该行）。
     */
    private fun featureGeometryText(renderable: Renderable): String? {
        if (renderable is Polygon) {
            var signedArea = 0.0
            var perimeter = 0.0
            var hasBoundary = false
            for (i in 0 until renderable.boundaryCount) {
                val ring = renderable.getBoundary(i)
                if (ring.size < 2) continue
                hasBoundary = true
                val degrees = ring.map { doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees) }
                signedArea += GeoCalc.polygonSignedAreaSquareMeters(degrees)
                perimeter += GeoCalc.ringPerimeterMeters(degrees)
            }
            if (!hasBoundary) return null
            return "面积：" + GeoCalc.formatArea(kotlin.math.abs(signedArea)) +
                    "    周长：" + GeoCalc.formatDistance(perimeter)
        }
        if (renderable is Path) {
            val degrees = renderable.positions.map { doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees) }
            if (degrees.size < 2) return null
            return "长度：" + GeoCalc.formatDistance(GeoCalc.polylineLengthMeters(degrees))
        }
        return null
    }

    /**
     * 矢量图层当前实际渲染色（填充/描边）：按 [com.zys.mobilemap.vector.LocalVectorLoader.LAYER_PATH_KEY]
     * 定位已加载图层，取首个面/线/点渲染对象的真实颜色（已含文档样式效果），
     * 供整层样式对话框色块回显所见即所得；图层未加载返回 null。
     */
    private fun layerRenderColorsOf(path: String): Pair<String?, String?> {
        for (layer in getWorldWindow().engine.layers) {
            if (layer.getUserProperty(LocalVectorLoader.LAYER_PATH_KEY) != path) continue
            // 矢量图层均为 RenderableLayer（实现 Iterable<Renderable>），逐对象采样真实颜色
            if (layer !is RenderableLayer) continue
            for (r in layer) {
                val colors = renderColorsOf(r)
                if (colors.first != null || colors.second != null) return colors
            }
        }
        return null to null
    }

    /** wwd 颜色转 #AARRGGBB 十六进制字符串 */
    private fun colorToHex(c: Color): String {
        val a = (c.alpha * 255).roundToInt().coerceIn(0, 255)
        val r = (c.red * 255).roundToInt().coerceIn(0, 255)
        val g = (c.green * 255).roundToInt().coerceIn(0, 255)
        val b = (c.blue * 255).roundToInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X%02X", a, r, g, b)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * 处理系统外部打开（ACTION_VIEW）：content:// 先复制到本地，
     * 经矢量导入器导入（zip/kmz 解压），写入文档同步地图后缩放到图层范围。
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        lifecycleScope.launch {
            val localPath = withContext(Dispatchers.IO) { resolveToLocalPath(uri) }
            if (localPath == null) {
                Toast.makeText(this@MainActivity, R.string.vector_open_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val imported = VectorFileImporter.importFile(this@MainActivity, localPath)
            if (imported == null) {
                Toast.makeText(this@MainActivity, R.string.vector_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val doc = DocumentManager.Companion.getInstance().getDocument() ?: return@launch
            if (doc.vectorLayers.none { it.path == imported.path }) {
                doc.vectorLayers.add(LayerInfo(imported.name, imported.path, "vector", true))
                DocumentManager.Companion.getInstance().save(this@MainActivity)
            }
            zoomToLayerExtent(imported.path)
        }
    }

    /**
     * 外部 Uri 转本地可读路径：file:// 直接使用；
     * content:// 经 ContentResolver 复制到 /调查宝/layer/import_<时间戳>/。
     */
    private fun resolveToLocalPath(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    var name: String? = null
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                    }
                    if (name.isNullOrEmpty()) {
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "import_file"
                    }
                    val dir = File(
                        AppDirectories.Companion.getLayerDir(this),
                        "import_${System.currentTimeMillis()}"
                    )
                    dir.mkdirs()
                    val target = File(dir, name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return null
                    target.absolutePath
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "外部矢量文件复制到本地失败: $uri", e)
            null
        }
    }

    /**
     * 缩放到图层四至范围（GDAL 读取，kmz 走 /vsizip/ 虚拟文件）。
     */
    private fun zoomToLayerExtent(path: String) {
        val openPath = if (path.lowercase(Locale.getDefault()).endsWith(".kmz")) "/vsizip/$path" else path
        val extent = nativeGetLayerExtent(openPath)
        if (extent != null && extent.size == 4) {
            zoomToExtent(extent[0], extent[1], extent[2], extent[3])
        }
    }

    /**
     * 缩放到图层四至范围
     */
    private fun zoomToExtent(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        val camera = getWorldWindow().engine.camera
        val span = maxOf(maxLon - minLon, maxLat - minLat).coerceAtLeast(0.01)
        val altitude = span * 111320.0 * 1.5
        camera.set(
            Angle.Companion.fromDegrees((minLat + maxLat) / 2.0),
            Angle.Companion.fromDegrees((minLon + maxLon) / 2.0),
            altitude,
            AltitudeMode.ABSOLUTE,
            Angle.Companion.fromDegrees(0.0),
            Angle.Companion.fromDegrees(0.0),
            Angle.Companion.fromDegrees(0.0)
        )
        getWorldWindow().requestRedraw()
    }

    // JNI 符号为 Java_com_zys_mobilemap_ui_activity_MainActivity_nativeGetLayerExtent，
    // 与 LayerManageDialog 共用 C++ 层四至计算实现（不能放入 companion object）
    private external fun nativeGetLayerExtent(path: String): DoubleArray?

    /**
     * 按系统配置显示/隐藏中心十字
     */
    private fun updateCenterCrossVisibility() {
        val show = DocumentManager.Companion.getInstance().getDocument()?.systemConfig?.showCenterCross ?: true
        binding.mainCross.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 切换天地图-注记图层可见性，保存后由文档监听同步地图。
     */
    private fun toggleAnnotationLayer() {
        val doc = DocumentManager.Companion.getInstance().getDocument() ?: return
        val src = doc.mapSources.firstOrNull { it.isAnnotation } ?: return
        src.visible = !src.visible
        DocumentManager.Companion.getInstance().save(this)
        updateAnnotationIcon()
    }

    /**
     * 按注记图层可见性更新主界面开关图标。
     */
    private fun updateAnnotationIcon() {
        val visible = DocumentManager.Companion.getInstance().getDocument()
            ?.mapSources?.firstOrNull { it.isAnnotation }?.visible ?: false
        binding.mainIvIsCIA.setImageResource(
            if (visible) R.drawable.nav_ic_cia_open else R.drawable.nav_ic_cia_close
        )
    }



    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationDisplayManager?.handlePermissionResult(requestCode, grantResults)
    }

    private fun gotoLocation() {
        val loc: Location? = LocationManager.Companion.getInstance().getLastLocation()
        if (loc == null) {
            Toast.makeText(this, "尚未获取到定位信息", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存当前位置，用于移动到位置对话框初始坐标使用
        val camera = getWorldWindow().engine.camera
        savedCenterLon = camera.position.longitude.inDegrees
        savedCenterLat = camera.position.latitude.inDegrees

        // 设置相机位置，并重绘地图
        val alt = camera.position.altitude
        camera.set(
            Angle.Companion.fromDegrees(loc.latitude),
            Angle.Companion.fromDegrees(loc.longitude),
            alt,
            AltitudeMode.ABSOLUTE,
            camera.heading,
            camera.tilt,
            camera.roll
        )
        getWorldWindow().requestRedraw()
    }

    private fun showMoveToDialog() {
        var initLon = savedCenterLon
        var initLat = savedCenterLat

        // 尚无地图中心点时，优先使用定位坐标
        if (java.lang.Double.isNaN(initLon) || java.lang.Double.isNaN(initLat)) {
            val loc: Location? = LocationManager.Companion.getInstance().getLastLocation()
            if (loc != null) {
                initLon = loc.longitude
                initLat = loc.latitude
            }
        }

        // 仍然没有可用坐标时，使用当前相机位置兜底
        if (java.lang.Double.isNaN(initLon) || java.lang.Double.isNaN(initLat)) {
            val pos = getWorldWindow().engine.camera.position
            initLon = pos.longitude.inDegrees
            initLat = pos.latitude.inDegrees
        }

        MoveToDialog(this, initLon, initLat, { lon: Double, lat: Double ->
            val camera = getWorldWindow().engine.camera
            val alt = camera.position.altitude
            camera.set(
                Angle.Companion.fromDegrees(lat),
                Angle.Companion.fromDegrees(lon),
                alt,
                AltitudeMode.ABSOLUTE,
                camera.heading,
                camera.tilt,
                camera.roll
            )
            getWorldWindow().requestRedraw()
        }).create().show()
    }

    override fun onResume() {
        super.onResume()
        getWorldWindow().onResume()
        updateCenterCrossVisibility()
        applyModuleVisibility()
    }

    /**
     * 按系统配置驱动拍照标注/轨迹/测量图层可见性（设置页返回后生效）。
     */
    private fun applyModuleVisibility() {
        val config = DocumentManager.Companion.getInstance().getDocument()?.systemConfig ?: return
        mediaOverlayModel?.setEnabled(config.showMediaLayer)
        trackModel?.setEnabled(config.showTrackLayer)
        measureModel?.setEnabled(config.showMeasureLayer)
    }

}