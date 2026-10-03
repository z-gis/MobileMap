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
 * 各 getXxxDir 会顺带建目录（见 [ensureDir]）；建目录失败时仍返回该路径，
 * 由调用方在真正读写时暴露错误，而不是在这里静默把数据改写到别处。
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

    fun getRootDir(context: Context): File = File(getBaseDir(context), ROOT_DIR_NAME)

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
     * 数据根目录：优先外部存储根（/storage/emulated/0），仅当系统未挂载外部存储时
     * 回退应用内部目录（真机上不会发生）。
     *
     * 不再回退 `File(".")`：它在 Android 上解析为根目录 `/`，不可写，
     * 会让后续所有写入静默失败而看不出原因。
     */
    private fun getBaseDir(context: Context): File =
        Environment.getExternalStorageDirectory() ?: context.filesDir

    /** 目录不存在则创建；创建失败也返回原路径，由调用方在读写时暴露错误 */
    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
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
