package com.zys.mobilemap.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 应用数据目录统一入口：所有用户数据都落在外部存储的 `/调查宝/` 下，
 * 便于用户直接用文件管理器取走成果，也避免数据散落到应用私有目录。
 *
 * 各子目录职责：
 * - [LAYER_DIR_NAME]  导入的矢量/栅格图层源文件（压缩包解压到同名子目录）
 * - [MAP_DIR_NAME]    轨迹等地图业务数据库 map.db
 * - [MEDIA_DIR_NAME]  拍照点位数据库 media.db 及各点位附件子目录
 * - [SAMPLE_DIR_NAME] 示例数据（预留）
 * - [CONFIG_DIR_NAME] 地图文档 document.json
 * - [TILES_DIR_NAME]  底图与栅格瓦片离线缓存（按图源分子目录）
 * - [LOG_DIR_NAME]    运行日志
 *
 * 各 getXxxDir 会顺带建目录（见 [ensureDir]）。基目录（含 `/调查宝/` 根）在首次
 * 触达时做一次「探测写」校验（见 [effectiveBaseDir]）：外部存储根不可写（如个别
 * ROM 上“所有文件访问”授权未真正落地、FUSE 建目录静默失败）时，整体回退到应用
 * 私有目录，保证后续 map.db / document.json / 瓦片缓存一定能落盘、不再启动闪退。
 * 回退后所有子目录 getter 自动跟随，无需调用方感知。
 */
object AppDirectories {

    const val ROOT_DIR_NAME = "调查宝"

    const val LAYER_DIR_NAME = "layer"

    const val MAP_DIR_NAME = "map"

    const val MEDIA_DIR_NAME = "Media"

    const val SAMPLE_DIR_NAME = "sample"

    const val CONFIG_DIR_NAME = "config"

    const val TILES_DIR_NAME = "tiles"

    const val LOG_DIR_NAME = "log"

    private const val TAG = "AppDirectories"

    /** 基目录探测结果缓存（进程内一次判定）；null 表示尚未判定 */
    @Volatile
    private var baseDirCache: File? = null

    /** 是否已回退到应用私有目录（供设置/关于页提示实际数据路径） */
    @Volatile
    var usingPrivateFallback: Boolean = false
        private set

    fun getRootDir(context: Context): File = File(effectiveBaseDir(context), ROOT_DIR_NAME)

    fun getLayerDir(context: Context): File = ensureDir(File(getRootDir(context), LAYER_DIR_NAME))

    fun getMapDir(context: Context): File = ensureDir(File(getRootDir(context), MAP_DIR_NAME))

    fun getMediaDir(context: Context): File = ensureDir(File(getRootDir(context), MEDIA_DIR_NAME))
        .also { ensureNoMedia(it) }

    fun getSampleDir(context: Context): File = ensureDir(File(getRootDir(context), SAMPLE_DIR_NAME))

    fun getConfigDir(context: Context): File = ensureDir(File(getRootDir(context), CONFIG_DIR_NAME))

    fun getTileCacheDir(context: Context): File = ensureDir(File(getRootDir(context), TILES_DIR_NAME))

    fun getLogDir(context: Context): File = ensureDir(File(getRootDir(context), LOG_DIR_NAME))

    /** 启动时一次性建齐全部子目录，避免各处首次写入时才各自建目录 */
    fun ensureAll(context: Context) {
        ensureDir(getRootDir(context))
        // 以下 getter 内部已 ensureDir，此处调用只为触发建目录
        getLayerDir(context)
        getMapDir(context)
        getMediaDir(context)
        getSampleDir(context)
        getConfigDir(context)
        getTileCacheDir(context)
        getLogDir(context)
    }

    /**
     * 由文件路径推导基名（去扩展名与首尾空白），空名回退 "sample"，并过滤非法文件名字符。
     */
    fun deriveBaseName(path: String?): String {
        val fileName = path?.let { File(it).name }.orEmpty()
        // 只截掉最后一个点之后的扩展名；点开头的隐藏文件（dot == 0）保留整名
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        return sanitizeFileName(stem.trim().ifEmpty { "sample" })
    }

    /**
     * 生效基目录：进程内首次触达时判定一次并缓存。
     *
     * 优先外部存储根（`/storage/emulated/0`）并实测 `/调查宝/` 可建可写；不可用时
     * 回退应用私有外部目录（`Android/data/<pkg>/files`，免权限、必然可写），私有外部
     * 目录也不可用（如外部存储整体未挂载）再退内部 [Context.getFilesDir]。
     *
     * 判定结果写入 [baseDirCache] 后再打日志，避免 [AppLog] 解析日志目录回调
     * [getLogDir] 时再次进入本方法造成递归。
     */
    private fun effectiveBaseDir(context: Context): File {
        baseDirCache?.let { return it }
        synchronized(this) {
            baseDirCache?.let { return it }
            val app = context.applicationContext
            val primary = primaryBase()
            val usable = primary != null && probeDirUsable(File(primary, ROOT_DIR_NAME))
            val chosen = if (usable) primary!! else fallbackBase(app)
            usingPrivateFallback = !usable
            baseDirCache = chosen
            val rootPath = File(chosen, ROOT_DIR_NAME).absolutePath
            if (usable) {
                AppLog.i(TAG, "数据根目录（外部存储）=$rootPath")
            } else {
                AppLog.w(TAG, "外部存储根不可写，数据根目录回退到应用私有目录=$rootPath")
            }
            return chosen
        }
    }

    /** 外部存储根；系统未挂载外部存储时返回 null */
    private fun primaryBase(): File? = Environment.getExternalStorageDirectory()

    /** 回退基目录：优先应用私有外部目录，再退内部 filesDir */
    private fun fallbackBase(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * 探测目录能否创建且可写：建不出目录、或写探测文件失败均判为不可用。
     * 覆盖 ENOENT（父目录没建出来）与 EACCES（只读挂载/授权未落地）两类失效。
     */
    private fun probeDirUsable(dir: File): Boolean {
        if (!dir.isDirectory) {
            runCatching { dir.mkdirs() }
            if (!dir.isDirectory) return false
        }
        val probe = File(dir, ".wprobe")
        return try {
            probe.writeText("1")
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    /**
     * 目录不存在则逐级创建并回读校验；创建失败仍返回该路径（基目录已由
     * [effectiveBaseDir] 保证可写，此处失败仅可能出现在极端运行期变更）。
     */
    private fun ensureDir(dir: File): File {
        if (!dir.isDirectory) {
            dir.mkdirs()
            if (!dir.isDirectory) {
                AppLog.w(TAG, "目录创建失败: ${dir.absolutePath}")
            }
        }
        return dir
    }

    /**
     * 在目录下放置空的 `.nomedia` 标记文件，让 Android 媒体扫描器跳过该目录（含子目录）。
     * 采集照片（/调查宝/Media/<placemark_id> 目录下的 .jpg）不应被系统相册收录：
     * 它们通过应用内拍照标识管理访问，混入相册会打扰用户且泄露外业坐标。
     */
    private fun ensureNoMedia(dir: File) {
        val marker = File(dir, ".nomedia")
        if (marker.exists()) return
        runCatching { marker.createNewFile() }
    }

    /** 过滤 Windows/Android 不允许的文件名字符，空值回退 "sample" */
    private fun sanitizeFileName(value: String): String =
        value.ifEmpty { "sample" }.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
}
