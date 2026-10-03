package com.zys.mobilemap.doc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zys.mobilemap.util.AppDirectories
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 地图文档单例管理：在 /调查宝/config/document.json 上读写 [MapDocument]，
 * 并在加载/保存成功后向已注册的监听者广播变更（地图图层同步均由此驱动）。
 *
 * 读写均宽容失败：读不出就建默认文档，写不下就记日志，不抛异常中断宿主流程。
 */
class DocumentManager private constructor() {

    interface DocumentChangeListener {
        fun onDocumentChanged(document: MapDocument)
    }

    private var document: MapDocument? = null
    private val listeners = CopyOnWriteArrayList<DocumentChangeListener>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun registerDocumentChangeListener(l: DocumentChangeListener?) {
        if (l != null) listeners.add(l)
    }

    fun unregisterDocumentChangeListener(l: DocumentChangeListener?) {
        if (l != null) listeners.remove(l)
    }

    fun getDocument(): MapDocument? = synchronized(this) { document }

    /**
     * 从 config 目录加载文档；不存在或解析失败时创建默认文档并立即落盘。
     */
    fun loadOrCreate(context: Context) {
        synchronized(this) {
            val cfgDir = AppDirectories.getConfigDir(context)
            val docFile = File(cfgDir, DOCUMENT_FILE)
            var loaded = false
            if (docFile.exists()) {
                try {
                    val content = docFile.readText(StandardCharsets.UTF_8)
                    val o = JSONObject(content)
                    val doc = MapDocument.fromJson(o)
                    document = doc
                    migrateAnnotationSource(doc)
                    unifyTiandituTokens(doc)
                    notifyListeners()
                    loaded = true
                } catch (e: IOException) {
                    Log.w(TAG, "读取 document.json 失败，将创建默认文档", e)
                } catch (e: JSONException) {
                    Log.w(TAG, "读取 document.json 失败，将创建默认文档", e)
                }
            }

            if (!loaded) {
                // 创建默认文档
                val doc = createDefaultDocument()
                document = doc
                // 立即通知，不等落盘
                notifyListeners()
                // 先同步写一次，保证首次启动的后续检查能读到磁盘上的文件
                try {
                    docFile.writeText(doc.toJson().toString(2), StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    Log.w(TAG, "同步写 document.json 失败，转为异步重试", e)
                    // 回退到异步保存
                    save(context)
                } catch (e: JSONException) {
                    Log.w(TAG, "同步写 document.json 失败，转为异步重试", e)
                    // 回退到异步保存
                    save(context)
                }
            }
        }
    }

    private fun createDefaultDocument(): MapDocument {
        val doc = MapDocument()
        // 系统自带地图源：天地图-影像默认可见，电子/地形由地图源管理界面按需开启
        doc.mapSources.add(createTiandituSource(SYSTEM_SOURCE_TIANDITU_IMG, TIANDITU_IMG_URL, true, "image/jpeg"))
        doc.mapSources.add(createTiandituSource(SYSTEM_SOURCE_TIANDITU_VEC, TIANDITU_VEC_URL, false, "image/png"))
        doc.mapSources.add(createTiandituSource(SYSTEM_SOURCE_TIANDITU_TER, TIANDITU_TER_URL, false, "image/png"))
        // 系统自带地图源：天地图-注记（默认可见，不在地图源列表中显示，由主界面按钮控制）
        doc.mapSources.add(createAnnotationSource(true))
        // 栅格/矢量图层默认为空
        doc.systemConfig.showCenterCross = true
        doc.systemConfig.photoAddWatermark = true
        return doc
    }

    /** 构造一个系统自带天地图地图源（全部图层共用同一 Token） */
    private fun createTiandituSource(
        name: String,
        url: String,
        visible: Boolean,
        imageFormat: String
    ): MapSource = MapSource(name, url, visible).apply {
        token1 = TIANDITU_TOKEN
        this.imageFormat = imageFormat
        isSystem = true
    }

    /**
     * 旧文档迁移：将原有的“天地图-注记”标记为注记图层；
     * 缺失时补充默认注记图层，保证主界面注记开关可用。
     */
    private fun migrateAnnotationSource(doc: MapDocument) {
        var annotation: MapSource? = null
        for (s in doc.mapSources) {
            if (s.isAnnotation) {
                annotation = s
                break
            }
            if (SYSTEM_SOURCE_TIANDITU_CIA == s.name) annotation = s
        }
        if (annotation == null) {
            doc.mapSources.add(createAnnotationSource(true))
        } else {
            annotation.isAnnotation = true
        }
    }

    private fun createAnnotationSource(visible: Boolean): MapSource =
        createTiandituSource(SYSTEM_SOURCE_TIANDITU_CIA, TIANDITU_CIA_URL, visible, "image/png").apply {
            isAnnotation = true
        }

    /**
     * 天地图 Token 共用：修改任一天地图地图源的 Token 后，
     * 同步到所有天地图图层（含注记图层）。
     */
    fun syncTiandituTokens(doc: MapDocument, source: MapSource) {
        if (!source.isTiandituSource) return
        for (s in doc.mapSources) {
            if (s.isTiandituSource) {
                s.token1 = source.token1
                s.token2 = source.token2
            }
        }
    }

    /**
     * 旧文档迁移：将各天地图图层的 Token 统一为第一个含 Token 的天地图图层，保证共用。
     */
    private fun unifyTiandituTokens(doc: MapDocument) {
        val ref = doc.mapSources.firstOrNull { it.isTiandituSource && !it.token1.isNullOrEmpty() }
            ?: return
        for (s in doc.mapSources) {
            if (s.isTiandituSource) {
                s.token1 = ref.token1
                s.token2 = ref.token2
            }
        }
    }

    fun save(context: Context) {
        synchronized(this) {
            val doc = document ?: return
            val appCtx = context.applicationContext
            // 写盘耗时，放到单线程执行器上异步做（同一执行器也保证了写入顺序）
            executor.submit {
                val cfgDir = AppDirectories.getConfigDir(appCtx)
                val docFile = File(cfgDir, DOCUMENT_FILE)
                try {
                    docFile.writeText(doc.toJson().toString(2), StandardCharsets.UTF_8)
                    // 保存成功后再回主线程通知监听者，避免监听者读到未落盘的旧文件
                    mainHandler.post { notifyListeners() }
                } catch (e: IOException) {
                    Log.e(TAG, "保存 document.json 失败", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "保存 document.json 失败", e)
                }
            }
        }
    }

    private fun notifyListeners() {
        val doc = document ?: return
        for (l in listeners) {
            try {
                l.onDocumentChanged(doc)
            } catch (ignored: Throwable) {
                // 单个监听器抛异常（如宿主界面已销毁）不得影响其余监听器
            }
        }
    }

    companion object {
        private const val DOCUMENT_FILE = "document.json"
        private const val TAG = "DocumentManager"

        private const val TIANDITU_TOKEN = "2c9be3116bec8e3f7be8d0c88298ff06"
        private const val SYSTEM_SOURCE_TIANDITU_IMG = "天地图-影像"
        private const val SYSTEM_SOURCE_TIANDITU_VEC = "天地图-电子"
        private const val SYSTEM_SOURCE_TIANDITU_TER = "天地图-地形"
        private const val SYSTEM_SOURCE_TIANDITU_CIA = "天地图-注记"
        private const val TIANDITU_IMG_URL =
            "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILECOL={x}&TILEROW={y}&TILEMATRIX={z}&tk=\$tiandituToken"
        private const val TIANDITU_VEC_URL =
            "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILECOL={x}&TILEROW={y}&TILEMATRIX={z}&tk=\$tiandituToken"
        private const val TIANDITU_TER_URL =
            "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/ter_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ter&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILECOL={x}&TILEROW={y}&TILEMATRIX={z}&tk=\$tiandituToken"
        private const val TIANDITU_CIA_URL =
            "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/cia_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cia&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILECOL={x}&TILEROW={y}&TILEMATRIX={z}&tk=\$tiandituToken"

        private var instance: DocumentManager? = null

        @JvmStatic
        fun getInstance(): DocumentManager = synchronized(this) {
            instance ?: DocumentManager().also { instance = it }
        }
    }
}
