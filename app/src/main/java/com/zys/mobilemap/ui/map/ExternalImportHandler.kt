package com.zys.mobilemap.ui.map

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.media.MediaPackage
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.vector.VectorFileImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 外部文件承接处理器（自 MainActivity 拆出）：处理系统 ACTION_VIEW 打开——拍照数据包（.mphoto）导入新建点位，
 * 其余按矢量数据（content:// 复制到本地经 [VectorFileImporter] 导入，zip/kmz 解压）写入文档并缩放到图层范围。
 *
 * 复刻原底层库主界面 MainActivity.handleViewIntent。协程于注入的 [scope]（宿主 lifecycleScope）执行；
 * 导入新建点位后经 [rebuildOverlays] 回调刷新叠加层，缩放复用 [CameraNavigator]。
 */
internal class ExternalImportHandler(
    private val activity: AppCompatActivity,
    private val navigator: CameraNavigator,
    private val scope: CoroutineScope,
    private val rebuildOverlays: () -> Unit
) {

    /**
     * 处理系统外部打开（ACTION_VIEW）：先按显示名分流——拍照数据包（.mphoto）导入新建点位，
     * 其余按矢量数据 content:// 复制到本地经矢量导入器导入（zip/kmz 解压），写入文档同步地图后缩放到图层范围。
     */
    fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        scope.launch {
            // 拍照数据包与矢量数据共用同一外部打开入口，先按文件名后缀分流，避免误当矢量导入
            val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
            if (MediaPackage.isPackageFile(displayName)) {
                importMediaPackage(uri)
                return@launch
            }
            val localPath = withContext(Dispatchers.IO) { resolveToLocalPath(uri) }
            if (localPath == null) {
                Toast.makeText(activity, R.string.vector_open_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val imported = VectorFileImporter.importFile(activity, localPath)
            if (imported == null) {
                Toast.makeText(activity, R.string.vector_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val doc = DocumentManager.getInstance().getDocument() ?: return@launch
            if (doc.vectorLayers.none { it.path == imported.path }) {
                doc.vectorLayers.add(LayerInfo(imported.name, imported.path, LayerInfo.TYPE_VECTOR, true))
                DocumentManager.getInstance().save(activity)
            }
            navigator.zoomToLayerExtent(imported.path)
        }
    }

    /**
     * 导入拍照数据包：复制到应用私有临时目录 → 解包新建点位 → 刷新叠加层并缩放到首个新点位。
     * 与矢量导入一致，IO 均在工作线程；临时副本导入后清理。（本界面以 rebuildOverlays 替代原底层库的 mediaOverlayModel.reload）
     */
    private suspend fun importMediaPackage(uri: Uri) {
        val staged = withContext(Dispatchers.IO) { stagePackageFile(uri) }
        if (staged == null) {
            Toast.makeText(activity, "拍照数据包读取失败", Toast.LENGTH_SHORT).show()
            return
        }
        val file = staged.first
        val imported = try {
            withContext(Dispatchers.IO) { MediaPackage.importPackage(activity, file) }
        } finally {
            // 仅清理为导入复制出的临时副本（file:// 原件不动）
            if (staged.second) runCatching { file.delete() }
        }
        if (imported.isEmpty()) {
            Toast.makeText(activity, "拍照数据包导入失败", Toast.LENGTH_SHORT).show()
            return
        }
        rebuildOverlays()
        Toast.makeText(activity, "已导入 ${imported.size} 个拍照点位", Toast.LENGTH_SHORT).show()
        navigator.zoomToMediaPlacemark(imported.first())
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
        activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
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
                    val dir = File(activity.getExternalFilesDir(null) ?: activity.cacheDir, "media_import")
                    dir.mkdirs()
                    val target = File(dir, name)
                    activity.contentResolver.openInputStream(uri)?.use { input ->
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
     * content:// 经 ContentResolver 按**原文件名称**（净化非法字符、保留扩展名）复制到 /调查宝/layer/。
     * 不再用无意义的 import_<时间戳> 子目录：单文件直接落 layer 根，kmz/zip 由其自身解压到 /layer/<文件名>/，
     * 同名冲突时在扩展名前追加短时间戳兜底，避免覆盖既有图层。
     */
    private fun resolveToLocalPath(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    var name: String? = null
                    activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                    }
                    if (name.isNullOrEmpty()) {
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "import_file"
                    }
                    val target = uniqueLayerTarget(AppDirectories.getLayerDir(activity), name!!)
                    activity.contentResolver.openInputStream(uri)?.use { input ->
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
     * 依据原文件显示名生成 layer 目录下的唯一目标文件：净化非法字符、保留扩展名；
     * 若同名已存在则在扩展名前追加短时间戳（_<毫秒后6位>）兜底，避免覆盖既有图层。
     */
    private fun uniqueLayerTarget(layerDir: File, displayName: String): File {
        val safe = displayName.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim().ifEmpty { "import_file" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var target = File(layerDir, stem + ext)
        if (target.exists()) {
            target = File(layerDir, "${stem}_${System.currentTimeMillis().toString().takeLast(6)}$ext")
        }
        return target
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"
    }
}
