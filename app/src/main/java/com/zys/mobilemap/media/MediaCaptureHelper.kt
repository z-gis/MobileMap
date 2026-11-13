package com.zys.mobilemap.media

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore as ProviderMediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.project.ProjectManager
import com.zys.mobilemap.ui.dialog.MediaEditDialog
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照会话管理（简单版，参照上一版：调起系统相机拍照）。
 * 一次会话对应一个拍照点位：拍照后不退出，可继续拍照，
 * 多张照片作为同一点位的多个附件保存。
 * 附件文件保存在 /调查宝/Media/<placemark_id>/ 目录，记录写入当前工程。
 */
class MediaCaptureHelper(
    private val activity: AppCompatActivity,
    private val onMediaChanged: () -> Unit
) {

    /** 当前会话 Placemark 主键，为 -1 表示无会话 */
    private var sessionPlacemarkId = -1L

    /** 待处理拍摄的文件路径与坐标 */
    private var pendingCapturePath: String? = null
    private var pendingCaptureLat = Double.NaN
    private var pendingCaptureLon = Double.NaN
    private var pendingPermissionRequest = false

    /**
     * 相机权限往返期间暂存的落点坐标（NaN = 无落点，授权后按定位口径重放）。
     * 授权回调若写死回 [launchPhotoCapture]，长按落点会在这次异步往返里丢掉。
     */
    private var pendingStartLat = Double.NaN
    private var pendingStartLon = Double.NaN

    private lateinit var photoCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    /** 相册多选（Photo Picker，13 以下自动退化为 ACTION_OPEN_DOCUMENT），免存储权限 */
    private lateinit var albumPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    /** 相册补图所属点位：选择是异步往返，回调时按它落附件 */
    private var albumSessionId = -1L

    /**
     * 注册 Activity 结果回调，必须在 Activity onCreate 中尽早调用。
     */
    fun register() {
        photoCaptureLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleCaptureResult(result.resultCode == Activity.RESULT_OK)
        }
        albumPickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(ALBUM_MAX_SELECT)
        ) { uris -> handleAlbumPicked(uris) }
        cameraPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val shouldLaunch = pendingPermissionRequest
            pendingPermissionRequest = false
            if (granted && shouldLaunch) {
                // 按暂存坐标重放：有落点走兴趣点口径，无落点仍是定位拍照口径
                startCapture(pendingStartLat, pendingStartLon)
            } else if (!granted) {
                Toast.makeText(activity, "未获得相机权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 启动拍照（定位坐标口径）：相机权限检查 → 当前定位坐标 → 建会话（首张）→ 调起系统相机。
     */
    fun launchPhotoCapture() {
        startCapture(Double.NaN, Double.NaN)
    }

    /**
     * 以地图落点坐标启动拍照（兴趣点）：坐标由调用方给定，与是否定位成功无关。
     */
    fun launchPhotoCaptureAt(lat: Double, lon: Double) {
        startCapture(lat, lon)
    }

    /**
     * 从相册选图建/补兴趣点：不调相机、不依赖定位与相机权限，选中照片作为该点位的附件入库。
     * 已在连拍会话中则并入当前会话点位（与连拍同归属口径）。
     * 相册原图不带本应用坐标，故不加水印、capturedAt 记本次入库时刻。
     */
    fun pickPhotosFromAlbum(lat: Double, lon: Double) {
        val id = if (sessionPlacemarkId >= 0) sessionPlacemarkId else ensureSessionAt(lat, lon, "兴趣点")
        if (id < 0) return
        albumSessionId = id
        albumPickerLauncher.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

    /** 相册选择回调：逐张把 content URI 拷进会话目录再入库；取消/全失败则清理空点位 */
    private fun handleAlbumPicked(uris: List<Uri>) {
        val placemarkId = albumSessionId
        albumSessionId = -1L
        if (placemarkId < 0) return
        if (uris.isEmpty()) {
            cleanupEmptySessionPlacemark()
            return
        }
        val placemarkDir = File(AppDirectories.getMediaDir(activity), placemarkId.toString())
        if (!placemarkDir.exists() && !placemarkDir.mkdirs()) {
            Toast.makeText(activity, "创建媒体目录失败", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = ProjectManager.get(activity)
        var saved = 0
        for (uri in uris) {
            val time = String.format(Locale.US, "%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS", Date())
            val ext = extensionOf(uri)
            val outFile = File(placemarkDir, "ALB_$time${if (ext.isEmpty()) ".jpg" else ext}")
            if (!copyTo(uri, outFile)) {
                if (outFile.exists()) outFile.delete()
                continue
            }
            pm.addPhotoAttachment(placemarkId, outFile.name, outFile.absolutePath, System.currentTimeMillis())
            saved++
        }
        if (saved == 0) {
            cleanupEmptySessionPlacemark()
            Toast.makeText(activity, "相册照片读取失败", Toast.LENGTH_SHORT).show()
            return
        }
        pm.saveNow()
        Toast.makeText(activity, "已添加 $saved 张相册照片", Toast.LENGTH_SHORT).show()
        if (sessionPlacemarkId == placemarkId) {
            // 由相册流程建的会话：结束连拍并把点位交给编辑面板，语义与拍完点「完成」一致
            finishSession()
        } else {
            onMediaChanged()
        }
    }

    /** content URI 的扩展名（小写、带点，取不到返回空串）；相册图常见 jpeg/heic，入库后按源扩展名命名 */
    private fun extensionOf(uri: Uri): String {
        val mime = activity.contentResolver.getType(uri) ?: return ""
        val ext = when {
            mime.endsWith("jpeg") || mime.endsWith("jpg") -> "jpg"
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("heic") -> "heic"
            else -> ""
        }
        return if (ext.isEmpty()) "" else ".$ext"
    }

    /** content URI → 沙箱文件直拷（不经 Bitmap 重编码，保住原图与 EXIF） */
    private fun copyTo(uri: Uri, dest: File): Boolean = try {
        activity.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } != null && dest.length() > 0
    } catch (e: Exception) {
        Log.e(TAG, "相册照片拷贝失败: $uri", e)
        false
    }

    /**
     * 相册入口的建点：只建点位、不调相机。失败已提示并返回 -1。
     */
    private fun ensureSessionAt(lat: Double, lon: Double, prefix: String): Long {
        val now = System.currentTimeMillis()
        val id = ProjectManager.get(activity).addPhoto(formatPlacemarkName(now, prefix), lat, lon, now)
        if (id < 0) {
            Toast.makeText(activity, "创建兴趣点失败", Toast.LENGTH_SHORT).show()
            return -1L
        }
        pendingCaptureLat = lat
        pendingCaptureLon = lon
        sessionPlacemarkId = id
        return id
    }

    /**
     * 两个入口的共用体。
     *
     * 坐标优先级：调用方落点（[lat]/[lon] 非 NaN）> 当前定位；两者皆无则不建点位（保持原提示口径）。
     * 相机权限未授予时把落点暂存进 [pendingStartLat]/[pendingStartLon]，由授权回调按暂存重放——
     * 权限是异步往返，回调里若写死 [launchPhotoCapture] 会把长按落点退化成定位点。
     */
    private fun startCapture(lat: Double, lon: Double) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartLat = lat
            pendingStartLon = lon
            pendingPermissionRequest = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        pendingStartLat = Double.NaN
        pendingStartLon = Double.NaN

        if (sessionPlacemarkId >= 0) {
            // 已在连拍会话中：定位口径继续拍下一张（原行为，归入同一点位）；
            // 落点口径拒绝，否则本次照片会静默并入上一个点位
            if (!lat.isNaN()) {
                Toast.makeText(activity, "请先完成当前连拍", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // 会话首张照片固定坐标，后续照片归入同一标注点
            val located = if (lat.isNaN()) LocationManager.getInstance().getLastLocation() else null
            val useLat = if (lat.isNaN()) located?.latitude else lat
            val useLon = if (lon.isNaN()) located?.longitude else lon
            if (useLat == null || useLon == null) {
                Toast.makeText(activity, "尚未定位成功，无法标注照片位置", Toast.LENGTH_LONG).show()
                return
            }
            pendingCaptureLat = useLat
            pendingCaptureLon = useLon

            val now = System.currentTimeMillis()
            sessionPlacemarkId = ProjectManager.get(activity).addPhoto(
                formatPlacemarkName(now, if (lat.isNaN()) "拍照" else "兴趣点"),
                useLat, useLon, now
            )
            if (sessionPlacemarkId < 0) {
                Toast.makeText(activity, "创建拍照记录失败", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 附件文件保存在 /调查宝/Media/<placemark_id>/ 目录
        val placemarkDir = File(AppDirectories.getMediaDir(activity), sessionPlacemarkId.toString())
        if (!placemarkDir.exists() && !placemarkDir.mkdirs()) {
            Toast.makeText(activity, "创建媒体目录失败", Toast.LENGTH_SHORT).show()
            return
        }
        val time = String.format(Locale.US, "%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS", Date())
        val outputFile = File(placemarkDir, "IMG_$time.jpg")

        val outputUri: Uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", outputFile)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "创建拍照文件 Uri 失败", e)
            Toast.makeText(activity, "创建媒体文件失败", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(ProviderMediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(ProviderMediaStore.EXTRA_OUTPUT, outputUri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        pendingCapturePath = outputFile.absolutePath
        try {
            photoCaptureLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            clearPendingCapture()
            Toast.makeText(activity, "未找到可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 结束当前连拍会话。
     */
    fun resetSession() {
        sessionPlacemarkId = -1L
    }

    private fun handleCaptureResult(success: Boolean) {
        val path = pendingCapturePath
        if (!success || path == null || sessionPlacemarkId < 0) {
            clearPendingCapture()
            // 取消拍照：会话点位尚无附件时删除该空点位（避免地图残留无照片标注），
            // 已有附件则保留点位与会话，下次拍照继续归入同一点位（含相机被系统回收后重入的兜底）
            cleanupEmptySessionPlacemark()
            return
        }

        val file = File(path)
        if (!file.exists() || file.length() <= 0) {
            clearPendingCapture()
            Toast.makeText(activity, "照片保存失败", Toast.LENGTH_SHORT).show()
            return
        }

        val capturedAt = System.currentTimeMillis()
        val t0 = android.os.SystemClock.elapsedRealtime()

        // 按设置添加坐标水印
        val watermarkEnabled =
            DocumentManager.getInstance().getDocument()?.systemConfig?.photoAddWatermark ?: true
        if (watermarkEnabled
            && !MediaWatermark.addCoordinateWatermark(file, pendingCaptureLat, pendingCaptureLon, capturedAt)
        ) {
            Toast.makeText(activity, "照片已保存，但添加坐标水印失败", Toast.LENGTH_SHORT).show()
        }
        val t1 = android.os.SystemClock.elapsedRealtime()

        // 写入附件记录（照片本体增量入库 gpkg BLOB，库提交成功后源文件自动删除）
        ProjectManager.get(activity).addPhotoAttachment(
            sessionPlacemarkId,
            file.name,
            file.absolutePath,
            capturedAt
        )
        // 栅栏排空增量队列：调用返回时记录与照片 BLOB 已落库，进程被杀不丢
        ProjectManager.get(activity).saveNow()
        AppLog.i(
            "MediaCapture",
            "照片入库耗时 水印=${t1 - t0}ms 读文件+BLOB落库+栅栏=${android.os.SystemClock.elapsedRealtime() - t1}ms " +
                    "合计=${android.os.SystemClock.elapsedRealtime() - t0}ms bytes=${file.length()}"
        )
        onMediaChanged()
        Toast.makeText(activity, "照片已保存并标注", Toast.LENGTH_SHORT).show()
        clearPendingCapture()

        showContinueCaptureDialog()
    }

    /**
     * 拍照后不退出：询问继续拍照或完成（结束会话）。
     */
    private fun showContinueCaptureDialog() {
        AlertDialog.Builder(activity)
            .setTitle("连拍模式")
            .setItems(arrayOf("继续拍照", "完成")) { _, which ->
                if (which == 0) {
                    launchPhotoCapture()
                } else {
                    finishSession()
                }
            }
            .setOnCancelListener { resetSession() }
            .show()
    }

    /**
     * 完成会话：先结束连拍会话（无论后续是否保存），再弹出"修改名称 + 所属分组"对话框。
     * 保存后刷新地图标注（名称/分组生效）；取消则保留默认时间名，点位已在拍照时入库渲染。
     */
    private fun finishSession() {
        val placemark = ProjectManager.get(activity).findPhoto(sessionPlacemarkId)
        // 先复位会话：编辑对话框取消也应结束本次连拍，避免下次拍照误并入本点位
        resetSession()
        if (placemark == null) return
        MediaEditDialog.show(activity, placemark, title = "完成拍照") { _, _ ->
            onMediaChanged()
        }
    }

    private fun clearPendingCapture() {
        pendingCapturePath = null
    }

    /**
     * 无附件的会话点位删除并结束会话，刷新媒体标注图层。
     */
    private fun cleanupEmptySessionPlacemark() {
        if (sessionPlacemarkId < 0) return
        val projectManager = ProjectManager.get(activity)
        val placemark = projectManager.findPhoto(sessionPlacemarkId)
        if (placemark != null && placemark.attachments.isEmpty()) {
            projectManager.removePhoto(sessionPlacemarkId)
            File(AppDirectories.getMediaDir(activity), sessionPlacemarkId.toString()).deleteRecursively()
            resetSession()
            onMediaChanged()
        }
    }

    companion object {
        private const val TAG = "MediaCaptureHelper"

        /** 相册单次最多选几张（与鸿蒙 PhotoViewPicker maxSelectNumber 对齐） */
        private const val ALBUM_MAX_SELECT = 9

        /**
         * 会话点位默认时间名（自原底层库主界面 MediaOverlayModel 抽入，该类已随主界面移除）。
         * @param prefix 定位拍照「拍照」/ 长按落点「兴趣点」，与鸿蒙 Ui.stampName 同格式
         */
        fun formatPlacemarkName(time: Long, prefix: String = "拍照"): String =
            prefix + " " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }
}
