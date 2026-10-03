package com.zys.mobilemap.ui.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.zys.mobilemap.R
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.media.MediaCaptureHelper
import com.zys.mobilemap.survey.SampleSurveyActivity
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.ui.dialog.LayerManageDialog
import com.zys.mobilemap.ui.dialog.MediaManageDialog
import com.zys.mobilemap.ui.dialog.LocationDetailDialog
import com.zys.mobilemap.ui.dialog.MoveToDialog
import com.zys.mobilemap.ui.dialog.PhotoSheetDialog
import com.zys.mobilemap.ui.map.BusinessOverlayManager
import com.zys.mobilemap.ui.map.CameraNavigator
import com.zys.mobilemap.ui.map.ExternalImportHandler
import com.zys.mobilemap.ui.map.FeaturePickController
import com.zys.mobilemap.ui.map.LocationFollowController
import com.zys.mobilemap.ui.map.MapLayerManager
import com.zys.mobilemap.ui.map.MeasureCaptureController
import com.zys.mobilemap.ui.map.PickHitRouter
import com.zys.mobilemap.ui.map.SqlQueryController
import com.zys.mobilemap.ui.map.StartupInitializer
import com.zys.mobilemap.ui.map.TrackCaptureController
import com.zys.mobilemap.ui.map.decodeIconArgb
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.PermissionUtil
import com.zys.mobilemap.util.UiUtil
import com.zys.globecore.NativeMapView

/**
 * MainActivity：程序主页（LAUNCHER）宿主装配层（globecore [NativeMapView] 渲染 2D 墨卡托瓦片）。
 *
 * 本类现仅承担「宿主」职责：View 装配与生命周期编排、对话框/Fragment 专属逻辑、各功能域协作类的创建与接线。
 * 具体功能已按域拆出至 `com.zys.mobilemap.ui.map` 十个协作类（行为零变化，函数体原样搬移）：
 *  - [StartupInitializer]：权限门 + proj 解压 + 启动加载遮罩；
 *  - [CameraNavigator]：缩放/四至/移动、状态栏轮询、相机独立视角持久化；
 *  - [MapLayerManager]：瓦片底图/注记 + 矢量 + 栅格层栈装配与可见性驱动、结构签名判定；
 *  - [BusinessOverlayManager]：测量/轨迹/拍照/样地业务叠加显示与命中分类（含 [MeasureLabels] 标注锚点）；
 *  - [SqlQueryController]：图层 SQL 查询高亮；
 *  - [FeaturePickController]：矢量拾取分发 + 选中高亮 + 详情 + 属性写回 + 样地入口（命中分流经 [PickHitRouter] 回本类）；
 *  - [MeasureCaptureController] / [TrackCaptureController]：测量/轨迹采集；
 *  - [LocationFollowController]：定位展示与跟随；
 *  - [ExternalImportHandler]：系统外部文件承接（矢量导入 / 拍照数据包）。
 *
 * 依赖单向：本类创建并接线各控制器；控制器之间只经回调或引用协作类，不反向引用本类私有成员
 * （对话框/Fragment 生命周期、挂起 recreate、权限申请器、拍照菜单留在本类）。
 *
 * 迁移史：原底层库主界面（同名 MainActivity）及其渲染链路已删除（原底层库→jni 迁移终点），权限门与启动初始化由本界面
 * 自行承担；相机独立保存为迁移期历史成因（原底层库界面 onPause 会覆盖共享文档相机），独立 prefs 保留以兼容既有视角。
 */
class MainActivity : AppCompatActivity(), DocumentManager.DocumentChangeListener, PickHitRouter {

    private lateinit var mapView: NativeMapView

    // 导航与采集控件（对齐原主界面）：级别/中心/定位文本、注记显隐、定位跟随、测量与轨迹采集子控件、启动遮罩
    private lateinit var levelText: TextView
    private lateinit var centerText: TextView
    private lateinit var locationText: TextView
    private lateinit var annotationButton: ImageView
    private lateinit var locateButton: ImageView

    /** 指北针复位按键（3D 双指旋转后 heading 非零时显示，点击回正北） */
    private lateinit var compassButton: TextView

    private lateinit var loadingOverlay: View
    private lateinit var measureUndoButton: Button
    private lateinit var measureCenterButton: Button
    private lateinit var measureFinishButton: Button
    private lateinit var trackButton: ImageView
    private lateinit var trackStartButton: ImageView
    private lateinit var trackPauseButton: ImageView
    private lateinit var trackStopButton: ImageView

    // ── 功能域协作类（setupMapUi 中创建并接线）────────────────────────────────────────
    private lateinit var startup: StartupInitializer
    private lateinit var navigator: CameraNavigator
    private lateinit var layerManager: MapLayerManager
    private lateinit var overlayManager: BusinessOverlayManager
    private lateinit var queryController: SqlQueryController
    private lateinit var measureController: MeasureCaptureController
    private lateinit var trackController: TrackCaptureController
    private lateinit var pickController: FeaturePickController
    private lateinit var locationController: LocationFollowController
    private lateinit var importHandler: ExternalImportHandler

    /** 拍照会话（复用原主界面 [MediaCaptureHelper]，不依赖原底层库）：拍照/连拍/水印/入库/编辑全由其处理，变更回调刷新叠加层 */
    private var mediaCaptureHelper: MediaCaptureHelper? = null

    /**
     * 挂起的 recreate 标记：结构/样式变更到达时若图层管理对话框处于打开态则置 true，
     * 由 [layerDialogLifecycleCallback] 在对话框销毁后触发一次 dismissOpenDialogs + recreate 并清零。
     */
    private var pendingRecreateOnLayerDialogDismiss = false

    /**
     * 图层管理对话框生命周期观察：捕获其销毁时机，若期间有挂起的结构变更（[pendingRecreateOnLayerDialogDismiss]）
     * 则执行延迟的 recreate。用 post 到主消息队列尾，避开 Fragment 生命周期回调中直接触发 Activity 重建的时序风险。
     * 只观察根 FragmentManager（recursive=false），子层的样式对话框销毁不参与判定。
     */
    private val layerDialogLifecycleCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.tag != TAG_LAYER_MANAGE_DIALOG) return
            if (!pendingRecreateOnLayerDialogDismiss) return
            pendingRecreateOnLayerDialogDismiss = false
            AppLog.i(TAG, "图层管理对话框已关闭，执行挂起的重建")
            mapView.post {
                if (isFinishing || isDestroyed) return@post
                dismissOpenDialogs()
                recreate()
            }
        }
    }

    /** 定位权限申请（现代 Activity Result API，须在 onCreate 前作为属性注册）：授予后转定位控制器启动定位 */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (::locationController.isInitialized) locationController.startLocating()
        } else {
            AppLog.w(TAG, "定位权限被拒绝，定位标记不可用")
        }
    }

    /** 启动初始化是否完成：native/proj/文档加载与地图 UI 装配均完成后为 true；onResume/onPause/onDestroy 据此守卫 */
    private var initialized = false

    /** 冷启动首装遮罩已显示标记：仅此时才在装配完成后挂图层加载门控驱动淡出（recreate/恢复场景不启动） */
    private var coldStartOverlay = false

    /** 冷启动图层加载轮询任务（主线程 postDelayed）：onDestroy 据移除去抖，界面销毁后不再触碰 View */
    private var startupLoadPoll: Runnable? = null

    /** 等待系统“所有文件访问”授权返回：跳系统页时置 true，onResume 复检并续跑初始化 */
    private var waitingForAllFilesPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏沉浸式，与原主界面一致
        UiUtil.enableFullscreen(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.jni_map_view)
        levelText = findViewById(R.id.jni_map_level)
        centerText = findViewById(R.id.jni_map_center)
        locationText = findViewById(R.id.jni_map_location)
        annotationButton = findViewById(R.id.jni_map_annotation)
        locateButton = findViewById(R.id.jni_map_locate)
        compassButton = findViewById(R.id.jni_map_compass)
        loadingOverlay = findViewById(R.id.jni_map_loading_overlay)
        measureUndoButton = findViewById(R.id.jni_map_measure_undo)
        measureCenterButton = findViewById(R.id.jni_map_measure_center)
        measureFinishButton = findViewById(R.id.jni_map_measure_finish)
        trackButton = findViewById(R.id.jni_map_track)
        trackStartButton = findViewById(R.id.jni_map_track_start)
        trackPauseButton = findViewById(R.id.jni_map_track_pause)
        trackStopButton = findViewById(R.id.jni_map_track_stop)

        startup = StartupInitializer(this)

        // 退出确认：拦截返回键弹确认框，需再点「退出」二次操作才退出（对齐 OpenFileActivity 的 dispatcher 惯例）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        // 启动加载遮罩：盖住 native/proj/文档初始化与首屏瓦片异步加载的白屏，初始化完成（或安全超时）后淡出。
        // recreate（图源/栅格结构变更触发）时跳过遮罩：避免重建闪动（新实例瓦片多已在磁盘缓存）。
        // savedInstanceState != null 视为重建/恢复场景。
        if (savedInstanceState == null) {
            startup.beginLoadingOverlay(loadingOverlay)
            coldStartOverlay = true
        } else {
            startup.skipLoadingOverlay(loadingOverlay)
        }

        // 权限门：Android 11+ 需“管理所有文件”权限，未授予则跳系统授权页，onResume 复检后续跑初始化
        if (!startup.hasAllFilesPermission()) {
            waitingForAllFilesPermission = true
            Toast.makeText(this, R.string.open_file_permission_required, Toast.LENGTH_SHORT).show()
            PermissionUtil.goManagerFileAccess(this)
            return
        }

        initializeAndSetup()
    }

    /**
     * 启动初始化 + 地图 UI 装配（作为主入口自行承担，复刻原主界面的 initializeAndSetup）：
     * native 库、外部目录、日志目录切换、proj 数据解压、文档加载，随后装配地图与各模块，最后处理外部 VIEW intent。
     * 幂等：已初始化直接返回，避免权限页返回后重复装配。
     */
    private fun initializeAndSetup() {
        if (initialized) return
        initialized = true

        // 启动链耗时打点：主线程串行初始化各阶段逐项记录，便于真机核对卡顿分解
        // native 库由 globecore 门面类（NativeSrs/NativeLayerInfo/NativeVector）首次触达时自动加载
        var t = SystemClock.elapsedRealtime()
        AppDirectories.ensureAll(this)
        // 全文件访问权限已就绪，将日志目录切到外部 调查宝/log，便于用户取出反馈
        AppLog.refreshLogDir(this)
        // 解压 proj 数据（PROJ 坐标转换依赖 proj.db 与格网改正数文件）
        t = SystemClock.elapsedRealtime()
        startup.extractProjData()
        AppLog.i(TAG, "启动耗时 proj解压=${SystemClock.elapsedRealtime() - t}ms")
        // 加载或创建文档
        t = SystemClock.elapsedRealtime()
        DocumentManager.getInstance().loadOrCreate(this)
        AppLog.i(TAG, "初始化完成，文档已加载 启动耗时 文档=${SystemClock.elapsedRealtime() - t}ms")

        t = SystemClock.elapsedRealtime()
        setupMapUi()
        AppLog.i(TAG, "启动耗时 UI装配合计=${SystemClock.elapsedRealtime() - t}ms")

        // 系统外部打开数据（文件管理器/微信分享）：初始化完成后立即处理
        importHandler.handleViewIntent(intent)

        // 应用 2D/3D 视图模式（改由设置页控制系统配置 viewMode3d，默认 2D）
        applyViewMode()

        // 加载遮罩淡出：装配完成后启动冷启动图层加载门控，异步轮询 native 矢量加载状态，加载完成即淡出；
        // onCreate 挂的安全超时（LOADING_OVERLAY_TIMEOUT_MS）仅作兼底（长时间未就绪时强制淡出）。
        if (coldStartOverlay) {
            coldStartOverlay = false
            beginStartupLayerLoadGate()
        }
    }

    /**
     * 冷启动图层加载门控：异步轮询 [NativeMapView.hasVectorLoading]；遮罩底部文案为固定模式（布局静态指定，不随加载图层动态变化）。
     * **遮罩最少展示 [STARTUP_MIN_DISPLAY_MS] 毫秒**：未加载完成继续等待；加载完成但未达最少时长继续等待；
     * 两者均满足（加载完成且已够 3 秒）才淡出。
     *
     * 完成判定仅覆盖矢量层（现成聚合信号，无 native 改动）；瓦片底图/栅格层不纳入门控（首帧即视为就绪）。
     * 走 `postDelayed` 异步轮询而非末尾同步淡出，规避首帧未绘完时遮罩提前淡出而从不显示的时序问题。
     */
    private fun beginStartupLayerLoadGate() {
        // 计时起点：门控启动即近似遮罩实际上屏时刻（首帧在 onCreate 返回后数帧内绘出）
        val startAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val elapsed = SystemClock.elapsedRealtime() - startAt
                // 仍在加载，或已加载完但未达最少展示时长：继续等待
                if (mapView.hasVectorLoading() || elapsed < STARTUP_MIN_DISPLAY_MS) {
                    loadingOverlay.postDelayed(this, STARTUP_LOAD_POLL_MS)
                } else {
                    startupLoadPoll = null
                    startup.hideLoadingOverlay()
                }
            }
        }
        startupLoadPoll = poll
        loadingOverlay.postDelayed(poll, STARTUP_LOAD_POLL_MS)
    }

    /**
     * 地图 UI 与各功能模块装配（原 onCreate 主体，抽为独立方法以便权限授予后续跑）：创建各协作类并接线到控件/回调。
     */
    private fun setupMapUi() {
        // 拍照会话：尽早注册 Activity 结果回调（复用原主界面 [MediaCaptureHelper]），变更回调刷新业务叠加层
        mediaCaptureHelper = MediaCaptureHelper(this) { overlayManager.rebuild() }
        mediaCaptureHelper?.register()

        val doc = DocumentManager.getInstance().getDocument()

        // 矢量标注字体：传空串让 native 自动探测系统 CJK 字体（零 APK 增量）。UI 线程加载，避开 GL 线程文件 IO 卡顿。
        mapView.setFontPath("")

        // 定位标记罗盘图标（对齐原主界面 LocationModel ic_compass 方式）：解码 drawable → ARGB 像素，传 native 作纹理。
        decodeIconArgb(this, R.drawable.ic_compass)?.let { (pixels, w, h) ->
            mapView.setLocationMarkerIcon(pixels, w, h)
        }

        // ── 创建并接线各功能域协作类（构造参数即依赖注入，回调在此闭包捕获尚未赋值的 lateinit 属安全：均在装配完成后触发）──
        navigator = CameraNavigator(this, mapView, levelText, centerText)
        layerManager = MapLayerManager(this, mapView, annotationButton,
            findViewById(R.id.jni_map_vector_loading)) { overlayManager.rebuild() }
        // 矢量屏幕过滤渐进加载接线：layerManager 取当前相机可见范围作初始 extent（大数据层首屏按视口加载）；
        // 相机静止（连续≥静止阈值不变）后按新范围重载可见大数据层（native 内 Swap-on-ready，停下即有数据、无空窗）。
        layerManager.extentProvider = { mapView.getCamera()?.let { navigator.computeVisibleExtent(it) } }
        navigator.onCameraSettled = { cam -> layerManager.onCameraSettled(navigator.computeVisibleExtent(cam)) }
        overlayManager = BusinessOverlayManager(
            this, mapView,
            isCapturing = { measureController.capturing || trackController.capturing },
            onOverlayLayersCleared = {
                queryController.onOverlayLayersCleared()
                pickController.onOverlayLayersCleared()
            },
            restoreQueryHighlight = { queryController.restoreAfterRebuild() }
        )
        queryController = SqlQueryController(this, mapView, navigator, lifecycleScope)
        measureController = MeasureCaptureController(
            this, mapView, supportFragmentManager,
            measureUndoButton, measureCenterButton, measureFinishButton
        ) { overlayManager.rebuild() }
        trackController = TrackCaptureController(
            this, mapView, supportFragmentManager, navigator,
            trackButton, trackStartButton, trackPauseButton, trackStopButton
        ) { overlayManager.rebuild() }
        pickController = FeaturePickController(
            this, mapView, layerManager, overlayManager, queryController, measureController,
            this, supportFragmentManager, lifecycleScope
        )
        locationController = LocationFollowController(this, mapView, locationText, locateButton, navigator)
        locationController.onTrackLocation = { lat, lon, alt -> trackController.onLocation(lat, lon, alt) }
        // 轨迹记录态驱动定位跟随：开始/恢复记录→锁定跟随并换锁定图标，停止记录→解锁（对齐需求：轨迹模式下地图中心随定位移动）
        trackController.onRecordingStateChanged = { recording -> locationController.setFollowEnabled(recording) }
        importHandler = ExternalImportHandler(this, navigator, lifecycleScope) { overlayManager.rebuild() }

        // 装配瓦片图层栈 + 栅格层 + 矢量层（懒加入、可见性驱动）+ 业务叠加层
        var t = SystemClock.elapsedRealtime()
        layerManager.buildLayers(doc)
        AppLog.i(TAG, "启动耗时 瓦片层装配=${SystemClock.elapsedRealtime() - t}ms")
        t = SystemClock.elapsedRealtime()
        layerManager.syncRasterLayers(doc)
        AppLog.i(TAG, "启动耗时 栅格层装配=${SystemClock.elapsedRealtime() - t}ms")

        // 相机先于矢量层就位：使 layerManager 加入大数据矢量层时 extentProvider 能取到正确的首屏范围。
        // 相机优先级：本界面上次保存的独立视角 > 文档相机 > 兜底默认（由 navigator 判定并返回）
        val cam = navigator.initialCamera(doc)
        mapView.setCamera(cam)
        AppLog.i(TAG, "相机 lon=${cam.longitude} lat=${cam.latitude} altitude=${cam.altitude}")

        t = SystemClock.elapsedRealtime()
        layerManager.syncVectorLayers(doc)
        AppLog.i(TAG, "启动耗时 矢量层装配=${SystemClock.elapsedRealtime() - t}ms")
        t = SystemClock.elapsedRealtime()
        overlayManager.rebuild()
        AppLog.i(TAG, "启动耗时 叠加层重建(读DB)=${SystemClock.elapsedRealtime() - t}ms")

        // 导航控件接线：缩放（高度乘系数）、注记显隐开关、定位跟随锁定
        findViewById<ImageView>(R.id.jni_map_zoom_in).setOnClickListener { navigator.zoomIn() }
        findViewById<ImageView>(R.id.jni_map_zoom_out).setOnClickListener { navigator.zoomOut() }
        annotationButton.setOnClickListener { layerManager.toggleAnnotation() }
        // 定位键：非记录中仅单次回到定位位置不锁定；记录中切换锁定/解锁（锁定中点击解锁不调位，未锁点击锁定并调位）
        locateButton.setOnClickListener { locationController.onLocateClicked(trackController.capturing) }

        // 指北针复位：heading 非零且 3D（旋转唯一消费模式）时显示，点击经既有 setCamera 通道归零。
        // 显隐由 CameraNavigator 的 ~250ms 状态轮询回调驱动，本处无需主动刷
        compassButton.setOnClickListener { navigator.resetHeading() }
        navigator.onHeadingChanged = { deg ->
            compassButton.visibility =
                if (kotlin.math.abs(deg) > HEADING_SHOW_THRESHOLD_DEG &&
                    mapView.getViewMode() == NativeMapView.ViewMode.THREE_D
                ) View.VISIBLE else View.GONE
        }

        // 交互入口（对齐原主界面）：设置页、图层管理（地图源页签切换底图）、点定位文本「移动到」
        findViewById<ImageView>(R.id.jni_map_setting).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageView>(R.id.jni_map_layer_manage).setOnClickListener { showLayerManageDialog() }
        locationText.setOnClickListener { LocationDetailDialog.show(this) }
        // 中心坐标文本点击：弹出「移动到」对话框（原左下 GO 按键入口迁移至此）
        centerText.setOnClickListener { showMoveToDialog() }

        // 采集控件接线（对齐原主界面 MeasureModel/TrackModel.setup 的按钮绑定）
        findViewById<ImageView>(R.id.jni_map_measure).setOnClickListener { measureController.showMeasureMenu() }
        measureUndoButton.setOnClickListener { measureController.undoLastMeasurePoint() }
        measureCenterButton.setOnClickListener { measureController.addMeasureCenterPoint() }
        measureFinishButton.setOnClickListener { measureController.finishMeasurement() }
        findViewById<ImageView>(R.id.jni_map_photo).setOnClickListener { showPhotoMenu() }
        trackButton.setOnClickListener { trackController.onTrackButtonClick() }
        trackStartButton.setOnClickListener { trackController.resumeTrackRecording() }
        trackPauseButton.setOnClickListener { trackController.pauseTrackRecording() }
        trackStopButton.setOnClickListener { trackController.stopTrackRecording() }

        // 单击拾取矢量要素：native 命中检测 → 测量拦截 → 叠加命中分流 → 文件矢量拾取（原序由 pickController 保证）
        mapView.setOnTapListener { x, y -> pickController.onMapTap(x, y) }

        navigator.updateStatusText()

        // 监听文档变更：底图切换 / 注记显隐 / 图源增删改经此即时同步（对齐原主界面文档驱动）
        DocumentManager.getInstance().registerDocumentChangeListener(this)

        // 定位：已授权则直接开始，否则申请权限（授权结果在 locationPermissionLauncher 回调中启动）
        locationController.initLocation {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        // 上次轨迹采集未结束（进程被回收/异常退出）时恢复草稿：读回落库采集点重建活动轨迹 live 层并恢复记录状态
        trackController.restoreDraftTrack()

        // 监听图层管理对话框销毁：结构/样式变更触发的 recreate 若因对话框打开被挂起，待其关闭后再执行
        supportFragmentManager.registerFragmentLifecycleCallbacks(layerDialogLifecycleCallback, false)
    }

    /**
     * 打开图层管理对话框（BottomSheet）。缩放/回显/SQL 查询分别接线到 navigator/layerManager/queryController。
     */
    private fun showLayerManageDialog() {
        LayerManageDialog(
            R.layout.dialog_layer_manage,
            // 点图层名称/缩放键时回调四至，定位到该图层范围（对齐原主界面 zoomToExtent）
            onZoomToExtent = { minLon, minLat, maxLon, maxLat -> navigator.zoomToExtent(minLon, minLat, maxLon, maxLat) },
            // 样式所见即所得：回显图层当前实际渲染色（复用矢量层同款解析）
            layerRenderColors = { path -> layerManager.layerRenderColorsOf(path) },
            // shp/gpkg 图层 SQL 查询：复用原主界面数据入口执行查询并高亮命中要素，支持清除
            onSqlQuery = { path, sql -> queryController.runSqlQuery(path, sql) },
            onClearSqlQuery = { queryController.clearSqlQuery() }
        ).show(supportFragmentManager, TAG_LAYER_MANAGE_DIALOG)
    }

    /**
     * 依据系统配置应用 2D/3D 视图模式（默认 2D，模式改由设置页控制，主界面不再放切换按键）。
     * native 两模式共用同一相机状态，即时互换无需重建；切换后刷一轮询回调使指北针显隐随模式即时跟进。
     */
    private fun applyViewMode() {
        val threeD = DocumentManager.getInstance().getDocument()?.systemConfig?.viewMode3d == true
        mapView.setViewMode(if (threeD) NativeMapView.ViewMode.THREE_D else NativeMapView.ViewMode.TWO_D)
        if (::navigator.isInitialized) navigator.updateStatusText()
    }

    /**
     * 「移动到」对话框（复用原主界面 [MoveToDialog]，支持经纬度 / 投影平面坐标双输入 + 坐标系换算）：
     * 初值优先取当前定位，无则回退相机中心，再无则兜底默认；确认后相机平移到目标（保留当前缩放级别）。
     */
    private fun showMoveToDialog() {
        val loc = LocationManager.getInstance().getLastLocation()
        val cam = mapView.getCamera()
        var initLon = loc?.longitude ?: Double.NaN
        var initLat = loc?.latitude ?: Double.NaN
        if ((initLon.isNaN() || initLat.isNaN()) && cam != null) {
            initLon = cam.longitude
            initLat = cam.latitude
        }
        if (initLon.isNaN() || initLat.isNaN()) {
            initLon = CameraNavigator.DEFAULT_LON
            initLat = CameraNavigator.DEFAULT_LAT
        }
        MoveToDialog(this, initLon, initLat) { lon, lat ->
            navigator.moveToLocation(lat, lon, notify = false)
        }.create().show()
    }

    /** 拍照菜单入口：拍照（启动相机会话）/ 管理（弹出采集数据管理），对齐原主界面 showPhotoMenu */
    private fun showPhotoMenu() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(arrayOf("拍照", "管理")) { _, which ->
                when (which) {
                    0 -> mediaCaptureHelper?.launchPhotoCapture()
                    1 -> MediaManageDialog(
                        onChanged = { overlayManager.rebuild() },
                        onZoomTo = { placemark -> navigator.zoomToMediaPlacemark(placemark) }
                    ).show(supportFragmentManager, "MediaManageDialog")
                }
            }
            .show()
    }

    /** 当前显示的退出确认对话框（非空且展示中时不重复弹） */
    private var exitConfirmDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * 退出确认对话框：首次返回键弹出，需再点「退出」（二次操作）才退出应用。
     * 点「取消」/点击外部/再按一次返回（对话框自行 cancel）均留在应用——不拦截对话框内返回键
     * （predictive-back 规范），故退出动作唯一入口为「退出」按钮。
     */
    private fun confirmExit() {
        if (exitConfirmDialog?.isShowing == true) return
        exitConfirmDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("退出确认")
            .setMessage("是否退出应用？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .create()
        exitConfirmDialog?.show()
    }

    // ── 文档变更驱动（结构变化判定交 layerManager，recreate 编排留本类）─────────────────────

    /**
     * 文档变更响应（对齐原主界面 BaseMapActivity 的文档驱动，回调在主线程）：图源/栅格结构变化交 [MapLayerManager] 判定，
     * 需重建时——图层管理对话框打开态则挂起至其关闭（[layerDialogLifecycleCallback]），否则先 dismiss 弹层再 recreate；
     * 非结构变化则由 layerManager 就地同步可见性/矢量/栅格层并刷新叠加层。
     */
    override fun onDocumentChanged(document: MapDocument) {
        if (!layerManager.onDocumentChanged(document)) return
        if (supportFragmentManager.findFragmentByTag(TAG_LAYER_MANAGE_DIALOG) != null) {
            AppLog.i(TAG, "文档图源/栅格结构变化，图层管理对话框打开中，挂起重建至对话框关闭")
            pendingRecreateOnLayerDialogDismiss = true
            return
        }
        AppLog.i(TAG, "文档图源/栅格结构变化，重建界面")
        // 重建前先移除已打开的弹层：recreate() 会复原仍存在的带参构造 BottomSheetDialogFragment（无公共无参构造），
        // 反射实例化失败会崩溃；结构变更常由弹层自身触发，故此处同步移除确保其不进入被保存的状态。
        dismissOpenDialogs()
        recreate()
    }

    /**
     * 同步移除当前已打开的全部 [DialogFragment] 弹层（供 recreate 前调用，避免不可复原的带参构造弹层被反射重建而崩溃）。
     */
    private fun dismissOpenDialogs() {
        val fm = supportFragmentManager
        for (f in fm.fragments) {
            if (f is DialogFragment) runCatching { fm.beginTransaction().remove(f).commitNowAllowingStateLoss() }
        }
    }

    // ── 拾取命中分流路由（FeaturePickController → 本类）：读记录 + 弹层/启动编辑（对话框/Fragment 专属留宿主）──

    /** 测量叠加命中：读记录后弹测量详情（记录可能已被外部删除，空安全） */
    override fun onMeasureHit(recordId: Long) {
        val record = MapDataStore.getInstance(this).loadMeasurements().firstOrNull { it.id == recordId }
        if (record != null) measureController.showMeasureDetail(record)
    }

    /** 轨迹叠加命中：读记录后弹轨迹详情 */
    override fun onTrackHit(recordId: Long) {
        val record = MapDataStore.getInstance(this).loadTracks().firstOrNull { it.id == recordId }
        if (record != null) trackController.showTrackDetail(record)
    }

    /** 拍照点位命中：弹出照片管理（[PhotoSheetDialog]） */
    override fun onMediaHit(placemark: com.zys.mobilemap.media.MediaPlacemark) {
        PhotoSheetDialog.show(supportFragmentManager, placemark.id, placemark.name)
    }

    /** 样地点位命中：按 fid=样地主键打开样地调查编辑（对齐原主界面 SurveyOverlayModel 点击识别） */
    override fun onSurveyHit(plotId: Long) {
        val plot = SampleSurveyStore.loadPlot(this, plotId) ?: return
        SampleSurveyActivity.start(
            this, plot.sourcePath, plot.sourceFid, plot.compartmentNo,
            plot.centerLatitude, plot.centerLongitude, plot.id
        )
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 未初始化完成前（如权限页返回前）外部 VIEW intent 先暂存，初始化完成后统一处理
        if (!initialized) return
        importHandler.handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 权限页返回复检：“所有文件访问”已授予则续跑启动初始化（幂等）
        if (waitingForAllFilesPermission && startup.hasAllFilesPermission()) {
            waitingForAllFilesPermission = false
            initializeAndSetup()
        }
        // 未完成初始化时地图尚未装配（mapView 无图层），跳过依赖它的刷新逻辑
        if (!initialized) return
        // GLSurfaceView 要求：驱动 GL 渲染线程
        mapView.onResume()
        // 刷新业务叠加层：数据签名变化才重建（捕获设置开关切换 / 外部数据库变更 / 样地调查返回后新增样地）
        overlayManager.rebuild()
        // 启动级别/中心文本轮询刷新
        navigator.startPolling()
        // 从设置页返回：读回系统配置应用 2D/3D 视图模式（开关即时生效，无需重建）
        applyViewMode()
    }

    override fun onPause() {
        super.onPause()
        navigator.stopPolling()
        // 未完成初始化时地图尚未装配，跳过相机保存与 GL 暂停
        if (!initialized) return
        // 把当前视角存到本界面独立的 SharedPreferences（不碰共享文档相机），最后暂停 GL 线程
        navigator.saveCameraState()
        mapView.onPause()
    }

    override fun onDestroy() {
        if (::navigator.isInitialized) navigator.stopPolling()
        if (::startup.isInitialized) startup.cancelLoadingOverlayTimeout()
        // 移除冷启动图层加载轮询的挂起任务（主线程 postDelayed，界面销毁后不可再触碰 View/native）
        if (::loadingOverlay.isInitialized) startupLoadPoll?.let { loadingOverlay.removeCallbacks(it) }
        startupLoadPoll = null
        // 取消矢量加载提示胶囊的挂起任务（延迟显示/轮询均为主线程 postDelayed，界面销毁后不可再触碰 View）
        if (::layerManager.isInitialized) layerManager.cancelVectorLoadingPill()
        // 注销图层管理对话框生命周期观察（避免界面销毁后仍被回调）
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(layerDialogLifecycleCallback)
        // 注销文档变更监听（避免界面销毁后仍被回调）
        DocumentManager.getInstance().unregisterDocumentChangeListener(this)
        // 注销定位监听（不调 stopLocation：定位由 LocationManager 单例统一管理，其他模块可能仍在使用）
        if (::locationController.isInitialized) locationController.onDestroy()
        // 关闭轨迹落库线程（已入队的采集点写入仍会执行完毕；采集点已实时写库，下次启动可恢复未完成记录）
        if (::trackController.isInitialized) trackController.onDestroy()
        // 释放 native 地图实例（GL 资源在 onDetachedFromWindow 经 GL 线程释放）；未初始化时 mapView 无图层亦安全
        if (::mapView.isInitialized) mapView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 指北针复位按键显示阈值（度）：|heading| 超过此值才显示（手势旋转归零的尾差不足半度视为正北） */
        private const val HEADING_SHOW_THRESHOLD_DEG = 0.5

        /** 图层管理对话框 FragmentManager tag（判定当前是否处于打开态、挂起 recreate 用） */
        private const val TAG_LAYER_MANAGE_DIALOG = "LayerManageDialog"

        /** 冷启动图层加载轮询间隔（毫秒）：首帧后按此间隔查 native 矢量加载状态，完成且达最少展示时长即淡出遮罩 */
        private const val STARTUP_LOAD_POLL_MS = 200L

        /** 冷启动遮罩最少展示时长（毫秒）：即使图层秒级加载完成，也至少停留此秒数再淡出，避免启动画面一闪而过 */
        private const val STARTUP_MIN_DISPLAY_MS = 3000L
    }
}
