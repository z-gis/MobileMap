package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
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
import com.zys.mobilemap.media.MediaPackage
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.survey.SampleSurveyActivity
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.survey.SurveyOverlayModel
import com.zys.mobilemap.track.TrackModel
import com.zys.mobilemap.ui.dialog.FeatureDetailDialog
import com.zys.mobilemap.ui.dialog.LayerManageDialog
import com.zys.mobilemap.ui.dialog.MediaManageDialog
import com.zys.mobilemap.ui.dialog.MoveToDialog
import com.zys.mobilemap.ui.dialog.PhotoSheetDialog
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.ColorHex
import com.zys.mobilemap.util.GeoCalc
import com.zys.mobilemap.util.PermissionUtil
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.mobilemap.vector.LocalVectorLoader
import com.zys.mobilemap.vector.PickedFeatureInfo
import com.zys.mobilemap.vector.VectorFileImporter
import com.zys.mobilemap.vector.VectorPickModel
import com.zys.mobilemap.vector.VectorQueryModel
import earth.worldwind.PickedObjectList
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
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : BaseMapActivity() {

    lateinit var binding: ActivityMainBinding

    private var locationDisplayManager: LocationModel? = null
    private var navigatorModel: NavigatorModel? = null
    private var vectorPickModel: VectorPickModel? = null

    /** 矢量 SQL 查询：命中要素以高亮图层叠加在源图层之上（临时结果，不写入文档） */
    private var vectorQueryModel: VectorQueryModel? = null

    private var mediaCaptureHelper: MediaCaptureHelper? = null
    private var mediaOverlayModel: MediaOverlayModel? = null
    private var trackModel: TrackModel? = null
    private var measureModel: MeasureModel? = null
    private var surveyOverlayModel: SurveyOverlayModel? = null

    /** 调查模式（运行时会话状态，不落文档，退出应用即复位）：
     *  开启后隐藏测量/拍照/轨迹按键，矢量要素属性可编辑，面要素弹出样地调查入口 */
    private var surveyMode = false

    /** 加载遮罩安全超时兜底：瓦片长时间未就绪也强制淡出，避免卡在启动画面 */
    private val loadingOverlayTimeout = Runnable { hideLoadingOverlay() }

    /** 启动初始化是否完成：native/proj/文档加载与地图 UI 装配均完成后为 true */
    private var initialized = false

    /** 等待系统“所有文件访问”授权返回：跳系统页时置 true，onResume 复检并续跑初始化 */
    private var waitingForAllFilesPermission = false

    companion object {
        private const val TAG = "MainActivity"

        /** 拍照标识"缩放至"目标相机高度（米） */
        private const val MEDIA_ZOOM_ALTITUDE = 1200.0

        /** 加载遮罩安全超时（毫秒）：首屏瓦片迟迟未就绪时兜底淡出 */
        private const val LOADING_OVERLAY_TIMEOUT_MS = 8000L

        /** 支持属性写回的矢量格式（GDAL 驱动能力矩阵：dwg 只读） */
        private val WRITABLE_FORMATS = setOf("shp", "dxf", "gpkg", "geojson", "kml", "kmz")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLog.i(TAG, "MainActivity onCreate")

        // 启动加载遮罩：盖住底图瓦片异步加载的白屏，首屏就绪（或安全超时）后淡出；
        // 合并启动页后，遮罩同时覆盖初始化阶段（native/proj/文档加载），初始化未完成前不淡出
        binding.root.postDelayed(loadingOverlayTimeout, LOADING_OVERLAY_TIMEOUT_MS)

        // 权限门：Android 11+ 需“管理所有文件”权限，未授予则跳系统授权页，onResume 复检后续跑
        if (!hasAllFilesPermission()) {
            waitingForAllFilesPermission = true
            Toast.makeText(this, R.string.open_file_permission_required, Toast.LENGTH_SHORT).show()
            PermissionUtil.goManagerFileAccess(this)
            return
        }

        initializeAndSetup()
    }

    /**
     * 启动初始化 + 地图 UI 装配（合并自原 SplashActivity）：
     * native 库、外部目录、日志目录切换、proj 数据解压、文档加载，随后装配地图与各模块，最后处理外部 VIEW intent。
     * 幂等：已初始化直接返回，避免权限页返回后重复装配。
     */
    private fun initializeAndSetup() {
        if (initialized) return
        initialized = true

        loadNativeLibrary()
        AppDirectories.ensureAll(this)
        // 全文件访问权限已就绪，将日志目录切到外部 调查宝/log，便于用户取出反馈
        AppLog.refreshLogDir(this)
        // 解压 proj 数据（PROJ 坐标转换依赖 proj.db 与格网改正数文件）
        extractProjData()
        // 加载或创建文档
        DocumentManager.getInstance().loadOrCreate(this)
        AppLog.i(TAG, "初始化完成，文档已加载")

        setupMapUi()

        // 系统外部打开数据（文件管理器/微信分享）：初始化完成后立即处理
        handleViewIntent(intent)
    }

    /**
     * 地图 UI 与各功能模块装配（原 onCreate 主体，抽为独立方法以便权限授予后续跑）。
     */
    private fun setupMapUi() {
        // 拍照会话：尽早注册 Activity 结果回调（拍照完成后刷新标注图层）
        mediaCaptureHelper = MediaCaptureHelper(this) { mediaOverlayModel?.reload() }
        mediaCaptureHelper?.register()

        binding.globe.addView(createWorldWindow())

        // 首屏就绪回调：淡出加载遮罩
        (getWorldWindow() as? MapWorldWindow)?.onFirstFrameSettled = { hideLoadingOverlay() }

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
                measureModel?.handlePickedGeometry(x, y, picked) == true ||
                        trackModel?.handlePickedGeometry(x, y, picked) == true ||
                        handleMediaPicked(picked)
            }
        )
        vectorPickModel?.setup(this)

        // 矢量 SQL 查询：查询结果高亮图层叠加到地图，并缩放到命中要素四至
        vectorQueryModel = VectorQueryModel(getWorldWindow(), lifecycleScope) { minLon, minLat, maxLon, maxLat ->
            zoomToExtent(minLon, minLat, maxLon, maxLat)
        }

        // 读取文档
        val doc = DocumentManager.getInstance().getDocument()
        if (doc != null) {
            syncMapLayers(doc)
        }
        DocumentManager.getInstance().registerDocumentChangeListener(this)

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

        // 定位锁定开关：点击锁定并跟随定位，再次点击解锁（参照 0.2.5.3 MainLocationModule）
        binding.mainIvGotoLocation.setOnClickListener { locationDisplayManager?.toggleLocationLock() }
        binding.mainTvLocation.setOnClickListener { showMoveToDialog() }
        // 北朝向按键：点击动画纠正到正北
        binding.mainIvNorth.setOnClickListener { (getWorldWindow() as? MapWorldWindow)?.orientNorth() }

        binding.mainIvLayerManage.setOnClickListener {
            LayerManageDialog(
                R.layout.dialog_layer_manage,
                onZoomToExtent = { minLon, minLat, maxLon, maxLat ->
                    zoomToExtent(minLon, minLat, maxLon, maxLat)
                },
                layerRenderColors = { path -> layerRenderColorsOf(path) },
                onSqlQuery = { path, sql -> runSqlQuery(path, sql) },
                onClearSqlQuery = { clearSqlQuery() }
            ).show(supportFragmentManager, "LayerManageDialog")
        }

        // 拍照入口：列表对话框（拍照/管理），菜单用词与轨迹菜单一致
        binding.mainIvPoto.setOnClickListener { showPhotoMenu() }

        // 调查模式切换：点击进入/退出调查模式，隐藏与调查无关的按键
        binding.mainIvSurvey.setOnClickListener { toggleSurveyMode() }

        // 样地调查覆盖图层：调查模式下显示已保存样地的 Placemark
        surveyOverlayModel = SurveyOverlayModel(this, getWorldWindow())
        surveyOverlayModel?.attach()
    }

    /** 切换调查模式：翻转状态、刷新按键可见性与图标着色，Toast 提示 */
    private fun toggleSurveyMode() {
        surveyMode = !surveyMode
        applySurveyModeUi()
        val msg = if (surveyMode) R.string.main_survey_enter_toast else R.string.main_survey_exit_toast
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        AppLog.i(TAG, "调查模式切换：$surveyMode")
    }

    /**
     * 应用调查模式 UI：
     * - 调查模式下隐藏测量/拍照/轨迹按键，避免与调查作业冲突；退出后恢复
     * - 调查按键图标着色（琥珀色）作为视觉提示，退出时清除
     */
    private fun applySurveyModeUi() {
        val vis = if (surveyMode) android.view.View.GONE else android.view.View.VISIBLE
        binding.mainIvMeasure.visibility = vis
        binding.mainIvPoto.visibility = vis
        binding.mainIvTrk.visibility = vis
        if (surveyMode) {
            binding.mainIvSurvey.setColorFilter(0xFFFFC107.toInt())
            surveyOverlayModel?.reload()
            surveyOverlayModel?.setEnabled(true)
        } else {
            binding.mainIvSurvey.clearColorFilter()
            surveyOverlayModel?.setEnabled(false)
        }
    }

    /** 供 FeatureDetailDialog 判定属性编辑与样地调查入口可见性 */
    fun isSurveyMode(): Boolean = surveyMode

    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("mobilemap")
            AppLog.i(TAG, "原生库 mobilemap 加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'mobilemap'", e)
            AppLog.e(TAG, "原生库 mobilemap 加载失败", e)
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || PermissionUtil.isExternalStorageManager()
    }

    private fun extractProjData() {
        try {
            val projDir = File(filesDir, "proj")
            if (!projDir.exists()) {
                projDir.mkdirs()
            }
            copyAssetFolder("proj", projDir)
            initProjDataPath(projDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proj data", e)
            AppLog.e(TAG, "解压 proj 数据失败", e)
        }
    }

    /**
     * 递归复制 assets 目录到 [destDir]：子目录建同名目录后递归（保持层级），
     * 文件已存在时跳过，避免每次启动重复解压。
     */
    private fun copyAssetFolder(assetPath: String, destDir: File) {
        try {
            val children = assets.list(assetPath)
            // 空清单即叶子节点（assets 对文件返回空数组），按文件复制
            if (children.isNullOrEmpty()) {
                val leaf = File(destDir, assetPath.substringAfterLast('/'))
                if (!leaf.exists()) copyAssetFile(assetPath, leaf)
                return
            }
            if (!destDir.exists()) destDir.mkdirs()
            for (child in children) {
                val subPath = "$assetPath/$child"
                val subChildren = assets.list(subPath)
                if (subChildren.isNullOrEmpty()) {
                    val destFile = File(destDir, child)
                    if (!destFile.exists()) copyAssetFile(subPath, destFile)
                } else {
                    copyAssetFolder(subPath, File(destDir, child))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    // JNI 符号为 Java_com_zys_mobilemap_ui_activity_MainActivity_initProjDataPath（合并自 SplashActivity）
    private external fun initProjDataPath(projDataPath: String?)

    /**
     * 淡出并隐藏加载遮罩（首屏就绪或安全超时触发，幂等）。
     */
    private fun hideLoadingOverlay() {
        binding.root.removeCallbacks(loadingOverlayTimeout)
        val overlay = binding.mainLoadingOverlay
        if (overlay.visibility == View.GONE) return
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "MainActivity onDestroy")
        binding.root.removeCallbacks(loadingOverlayTimeout)
        // 未完成初始化时 WorldWindow 尚未创建，跳过相关清理（lateinit 未赋值会抛异常）
        if (initialized) {
            (getWorldWindow() as? MapWorldWindow)?.onFirstFrameSettled = null
            vectorQueryModel?.clear()
            // 关闭轨迹落库线程（采集点已实时写库，下次启动可恢复未完成的记录）
            trackModel?.shutdown()
        }
        super.onDestroy()
    }

    /**
     * 执行 shp 图层 SQL 查询：命中要素高亮叠加并缩放到结果范围，
     * 语句错误时按 GDAL/OGR 上报的原因长时间提示（便于阅读完整错误）。
     */
    private fun runSqlQuery(path: String, sql: String) {
        Toast.makeText(this, R.string.sql_query_running, Toast.LENGTH_SHORT).show()
        vectorQueryModel?.query(path, sql) { success, count, error ->
            val message = when {
                !success -> getString(R.string.sql_query_failed, error ?: "")
                count == 0 -> getString(R.string.sql_query_result_empty)
                else -> getString(R.string.sql_query_result_count, count)
            }
            Toast.makeText(this, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 清除地图上的 SQL 查询高亮结果。
     */
    private fun clearSqlQuery() {
        vectorQueryModel?.clear()
        Toast.makeText(this, R.string.sql_query_cleared, Toast.LENGTH_SHORT).show()
    }

    /**
     * 拍照入口菜单：选择拍照进入拍摄会话（连拍），选择管理打开"采集数据管理"界面
     * 管理在库拍照标识（重命名/可见性/缩放至/删除）；用词与轨迹菜单（开始轨迹/管理）一致。
     */
    private fun showPhotoMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("拍照", "管理")) { _, which ->
                when (which) {
                    0 -> mediaCaptureHelper?.launchPhotoCapture()
                    1 -> MediaManageDialog(
                        onChanged = { mediaOverlayModel?.reload() },
                        onZoomTo = { placemark -> zoomToMediaPlacemark(placemark) }
                    ).show(supportFragmentManager, "MediaManageDialog")
                }
            }
            .show()
    }

    /**
     * 缩放至拍照点位：相机移至点位坐标（保持当前姿态），重绘地图。
     */
    private fun zoomToMediaPlacemark(placemark: MediaPlacemark) {
        val camera = getWorldWindow().engine.camera
        // 动画缩放至拍照点位：保持当前航向，远距离自动走飞越弧线
        (getWorldWindow() as? MapWorldWindow)?.smoothMoveTo(
            placemark.latitude, placemark.longitude, MEDIA_ZOOM_ALTITUDE, camera.heading.inDegrees
        )
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
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val layerInfo = doc.vectorLayers.firstOrNull { it.path == info.layerPath }
        val ext = info.layerPath?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: ""
        // shp/gpkg 均为 GDAL 索引矢量，支持整层/单要素样式覆盖；kml/kmz 无 fid（待二期）、dwg/dxf 只读
        val styleEditable = (ext == "shp" || ext == "gpkg") && info.featureId >= 0
        // 调查模式：属性可编辑（仅可写格式 + 有效 FID）
        val attrsEditable = surveyMode && info.featureId >= 0 && isWritableFormat(ext)

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

        // 渲染色只采样一次（填充/描边两个兜底色块共用同一结果）
        val renderColors = renderColorsOf(info.renderable)
        FeatureDetailDialog(
            R.layout.dialog_feature_detail,
            featureName = info.name,
            layerName = info.layerName,
            featureId = info.featureId,
            attributes = info.attributes,
            description = info.description,
            initialStyle = merged.takeIf { !it.isEmpty() },
            styleEditable = styleEditable,
            fallbackFill = renderColors.first,
            fallbackOutline = renderColors.second,
            isPointFeature = info.renderable is Placemark,
            geometryText = featureGeometryText(info.renderable),
            attrsEditable = attrsEditable,
            onSaveAttributes = if (attrsEditable) {{ pending -> persistAttributes(info, pending) }} else null,
            surveyEntryVisible = surveyMode && ext == "shp" && info.renderable is Polygon && info.featureId >= 0,
            onSurveyEntry = if (surveyMode && ext == "shp" && info.renderable is Polygon && info.featureId >= 0)
                {{ showSurveyEntryDialog(info) }} else null,
            onSave = { style ->
                val li = layerInfo
                if (li != null) {
                    val vs = li.vectorStyle ?: VectorStyle().also { li.vectorStyle = it }
                    if (style.isEmpty()) vs.featureStyles.remove(info.featureId)
                    else vs.featureStyles[info.featureId] = style
                    li.styleVersion++
                    // 保存后图层将重载，旧渲染对象失效，先取消选中态
                    vectorPickModel?.clearSelection()
                    DocumentManager.getInstance().save(this)
                }
            }
        ).show(supportFragmentManager, "FeatureDetailDialog")
    }

    /**
     * 调查模式属性写回：IO 线程调 GDAL 写回源文件，成功后就地更新 renderable 的
     * FEATURE_ATTRS_KEY userProperty（避免整层重载引起样式抖动），并刷新地图。
     * @return 写回是否成功（供对话框决定是否 dismiss）
     */
    private fun persistAttributes(info: PickedFeatureInfo, pending: Map<String, String>): Boolean {
        val path = info.layerPath ?: return false
        if (pending.isEmpty()) return true
        var success = false
        // 同步写回（对话框等待结果决定是否关闭），GDAL SetField+SetFeature 耗时可忽略
        try {
            success = GdalVectorReader.updateAttributes(path, info.featureId, pending)
        } catch (e: Exception) {
            AppLog.e(TAG, "属性写回异常", e)
        }
        if (success) {
            // 就地更新 renderable 属性缓存，避免整层重载
            @Suppress("UNCHECKED_CAST")
            val existing = info.renderable.getUserProperty(LocalVectorLoader.FEATURE_ATTRS_KEY) as? MutableMap<String, String>
            if (existing != null) {
                existing.putAll(pending)
            } else {
                val merged = LinkedHashMap(info.attributes ?: emptyMap())
                merged.putAll(pending)
                info.renderable.putUserProperty(LocalVectorLoader.FEATURE_ATTRS_KEY, merged)
            }
            getWorldWindow().requestRedraw()
            Toast.makeText(this, R.string.main_survey_edit_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.main_survey_edit_failed, Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /** 可写格式判定：shp/dxf/gpkg/geojson/kml/kmz 支持属性写回，dwg 只读 */
    private fun isWritableFormat(ext: String): Boolean =
        ext in WRITABLE_FORMATS

    /**
     * 样地调查入口对话框：查询该要素已有样地记录，
     * 空则直接新建样地调查，非空则弹出单选列表（已有样地 + 新建）。
     * 定位未就绪时 Toast 提示并中止。
     */
    private fun showSurveyEntryDialog(info: PickedFeatureInfo) {
        val loc = LocationManager.getInstance().getLastLocation()
        if (loc == null) {
            Toast.makeText(this, R.string.main_survey_location_required, Toast.LENGTH_SHORT).show()
            return
        }
        val path = info.layerPath ?: return
        val fid = info.featureId
        val plots = SampleSurveyStore.loadPlots(this, path, fid)
        // 尝试从属性表提取小班编号（常见字段名）
        val compartmentNo = info.attributes?.get("小班号")
            ?: info.attributes?.get("XBH")
            ?: info.attributes?.get("小班")
            ?: info.name
            ?: "未命名小班"

        if (plots.isEmpty()) {
            SampleSurveyActivity.start(this, path, fid, compartmentNo, loc.latitude, loc.longitude)
            return
        }
        // 弹出单选列表：已有样地 + 新建样地调查
        val items = plots.map { "样地#${it.plotNo}" }.toMutableList()
        items.add("新建样地调查")
        AlertDialog.Builder(this)
            .setTitle("样地调查")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < plots.size) {
                    // 编辑已有样地
                    SampleSurveyActivity.start(this, path, fid, compartmentNo,
                        loc.latitude, loc.longitude, plots[which].id)
                } else {
                    // 新建样地
                    SampleSurveyActivity.start(this, path, fid, compartmentNo,
                        loc.latitude, loc.longitude)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            return "面积：" + GeoCalc.formatArea(abs(signedArea)) +
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

    /** wwd 颜色转 #AARRGGBB 十六进制字符串（分量先限幅到 0..255） */
    private fun colorToHex(c: Color): String = ColorHex.fromArgb(
        alpha = (c.alpha * 255).roundToInt().coerceIn(0, 255),
        red = (c.red * 255).roundToInt().coerceIn(0, 255),
        green = (c.green * 255).roundToInt().coerceIn(0, 255),
        blue = (c.blue * 255).roundToInt().coerceIn(0, 255)
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 未初始化完成前（如权限页返回前）外部 VIEW intent 先暂存，初始化完成后统一处理
        if (!initialized) return
        handleViewIntent(intent)
    }

    /**
     * 处理系统外部打开（ACTION_VIEW）：先按显示名分流——拍照数据包（.mphoto）导入新建点位，
     * 其余按矢量数据 content:// 复制到本地经矢量导入器导入（zip/kmz 解压），写入文档同步地图后缩放到图层范围。
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        lifecycleScope.launch {
            // 拍照数据包与矢量数据共用同一外部打开入口，先按文件名后缀分流，避免误当矢量导入
            val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
            if (MediaPackage.isPackageFile(displayName)) {
                importMediaPackage(uri)
                return@launch
            }
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
            val doc = DocumentManager.getInstance().getDocument() ?: return@launch
            if (doc.vectorLayers.none { it.path == imported.path }) {
                doc.vectorLayers.add(LayerInfo(imported.name, imported.path, LayerInfo.TYPE_VECTOR, true))
                DocumentManager.getInstance().save(this@MainActivity)
            }
            zoomToLayerExtent(imported.path)
        }
    }

    /**
     * 导入拍照数据包：复制到应用私有临时目录 → 解包新建点位 → 刷新标注图层并缩放到首个新点位。
     * 与矢量导入一致，IO 均在工作线程；临时副本导入后清理。
     */
    private suspend fun importMediaPackage(uri: Uri) {
        val staged = withContext(Dispatchers.IO) { stagePackageFile(uri) }
        if (staged == null) {
            Toast.makeText(this@MainActivity, "拍照数据包读取失败", Toast.LENGTH_SHORT).show()
            return
        }
        val file = staged.first
        val imported = try {
            withContext(Dispatchers.IO) { MediaPackage.importPackage(this@MainActivity, file) }
        } finally {
            // 仅清理为导入复制出的临时副本（file:// 原件不动）
            if (staged.second) runCatching { file.delete() }
        }
        if (imported.isEmpty()) {
            Toast.makeText(this@MainActivity, "拍照数据包导入失败", Toast.LENGTH_SHORT).show()
            return
        }
        mediaOverlayModel?.reload()
        Toast.makeText(this@MainActivity, "已导入 ${imported.size} 个拍照点位", Toast.LENGTH_SHORT).show()
        zoomToMediaPlacemark(imported.first())
    }

    /** 取外部 Uri 的显示文件名（content:// 查 DISPLAY_NAME，file:// 取路径末段）。 */
    private fun resolveDisplayName(uri: Uri): String? = try {
        when (uri.scheme) {
            "file" -> uri.path?.substringAfterLast('/')
            "content" -> queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "解析外部文件名失败: $uri", e)
        null
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    /**
     * 拍照数据包落地为本地文件：file:// 直接用（非临时，勿删）；content:// 复制到应用私有
     * getExternalFilesDir/media_import（临时副本，导入后删）。返回 (文件, 是否临时副本)。
     */
    private fun stagePackageFile(uri: Uri): Pair<File, Boolean>? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.let { it to false }
                "content" -> {
                    val name = queryDisplayName(uri) ?: "package.${MediaPackage.EXT}"
                    val dir = File(getExternalFilesDir(null) ?: cacheDir, "media_import")
                    dir.mkdirs()
                    val target = File(dir, name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return null
                    target to true
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "拍照数据包复制到本地失败: $uri", e)
            null
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
                        AppDirectories.getLayerDir(this),
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
        val span = maxOf(maxLon - minLon, maxLat - minLat).coerceAtLeast(0.01)
        val altitude = span * 111320.0 * 1.5
        // 动画缩放到图层四至：北向归零，远距离自动走飞越弧线
        (getWorldWindow() as? MapWorldWindow)?.smoothMoveTo(
            (minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0, altitude, 0.0
        )
    }

    // 四至读取 nativeGetLayerExtent 已上提到基类 BaseMapActivity（protected external），此处继承使用

    /**
     * 按系统配置显示/隐藏中心十字
     */
    private fun updateCenterCrossVisibility() {
        val show = DocumentManager.getInstance().getDocument()?.systemConfig?.showCenterCross ?: true
        binding.mainCross.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 切换天地图-注记图层可见性，保存后由文档监听同步地图。
     */
    private fun toggleAnnotationLayer() {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        val src = doc.mapSources.firstOrNull { it.isAnnotation } ?: return
        src.visible = !src.visible
        DocumentManager.getInstance().save(this)
        updateAnnotationIcon()
    }

    /**
     * 按注记图层可见性更新主界面开关图标。
     */
    private fun updateAnnotationIcon() {
        val visible = DocumentManager.getInstance().getDocument()
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

    /**
     * “移动到”对话框初值：优先取当前定位（由定位文本点开，与上下文一致），
     * 无定位时回退到当前相机中心。
     */
    private fun showMoveToDialog() {
        val loc = LocationManager.getInstance().getLastLocation()
        var initLon = loc?.longitude ?: Double.NaN
        var initLat = loc?.latitude ?: Double.NaN

        if (initLon.isNaN() || initLat.isNaN()) {
            val pos = getWorldWindow().engine.camera.position
            initLon = pos.longitude.inDegrees
            initLat = pos.latitude.inDegrees
        }

        MoveToDialog(this, initLon, initLat) { lon: Double, lat: Double ->
            val camera = getWorldWindow().engine.camera
            // 动画移动到输入坐标：保持当前高度与航向，远距离自动走飞越弧线
            (getWorldWindow() as? MapWorldWindow)?.smoothMoveTo(
                lat, lon, camera.position.altitude, camera.heading.inDegrees
            )
        }.create().show()
    }

    override fun onResume() {
        super.onResume()
        // 权限页返回复检：“所有文件访问”已授予则续跑启动初始化（幂等）
        if (waitingForAllFilesPermission && hasAllFilesPermission()) {
            waitingForAllFilesPermission = false
            initializeAndSetup()
        }
        // 未完成初始化时地图尚未创建，跳过依赖 WorldWindow 的刷新逻辑
        if (!initialized) return
        getWorldWindow().onResume()
        updateCenterCrossVisibility()
        applyModuleVisibility()
    }

    /**
     * 按系统配置驱动拍照标注/轨迹/测量图层可见性与坐标文本格式（设置页返回后生效）。
     */
    private fun applyModuleVisibility() {
        val config = DocumentManager.getInstance().getDocument()?.systemConfig ?: return
        mediaOverlayModel?.setEnabled(config.showMediaLayer)
        mediaOverlayModel?.reload()
        trackModel?.setEnabled(config.showTrackLayer)
        measureModel?.setEnabled(config.showMeasureLayer)
        // 坐标显示格式（十进制度 / 米）可能已变更，立即重算中心与定位坐标文本
        navigatorModel?.refresh()
        locationDisplayManager?.refresh()
    }
}