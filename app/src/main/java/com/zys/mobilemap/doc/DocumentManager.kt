package com.zys.mobilemap.doc

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.zys.mobilemap.util.AppDirectories
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Simple document manager: load / save MapDocument to JSON under config directory.
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
     * Load document from config dir or create default one.
     */
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    fun loadOrCreate(context: Context) {
        synchronized(this) {
            val cfgDir = AppDirectories.getConfigDir(context)
            val docFile = File(cfgDir, DOCUMENT_FILE)
            var loaded = false
            if (docFile.exists()) {
                try {
                    val content = docFile.readText(StandardCharsets.UTF_8)
                    val o = JSONObject(content)
                    document = MapDocument.fromJson(o)
                    migrateAnnotationSource(document!!)
                    unifyTiandituTokens(document!!)
                    notifyListeners()
                    loaded = true
                } catch (e: IOException) {
                    Log.w(TAG, "failed to read document.json, will create default", e)
                } catch (e: JSONException) {
                    Log.w(TAG, "failed to read document.json, will create default", e)
                }
            }

            if (!loaded) {
                // create default document
                document = createDefaultDocument()
                // notify immediately
                notifyListeners()
                // try to save synchronously first so file exists on disk for first-run checks
                try {
                    docFile.writeText(document!!.toJson().toString(2), StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    Log.w(TAG, "failed to write document.json synchronously, will retry async", e)
                    // fallback to async save
                    save(context)
                } catch (e: JSONException) {
                    Log.w(TAG, "failed to write document.json synchronously, will retry async", e)
                    // fallback to async save
                    save(context)
                }
            }
        }
    }

    private fun createDefaultDocument(): MapDocument {
        val doc = MapDocument()
        // 系统自带地图源：天地图-影像（默认可见）
        val img = MapSource(SYSTEM_SOURCE_TIANDITU_IMG, TIANDITU_IMG_URL, true)
        img.token1 = TIANDITU_TOKEN
        img.imageFormat = "image/jpeg"
        img.isSystem = true
        doc.mapSources.add(img)
        // 系统自带地图源：天地图-电子，由地图源管理界面管理
        val vec = MapSource(SYSTEM_SOURCE_TIANDITU_VEC, TIANDITU_VEC_URL, false)
        vec.token1 = TIANDITU_TOKEN
        vec.imageFormat = "image/png"
        vec.isSystem = true
        doc.mapSources.add(vec)
        // 系统自带地图源：天地图-地形，由地图源管理界面管理
        val ter = MapSource(SYSTEM_SOURCE_TIANDITU_TER, TIANDITU_TER_URL, false)
        ter.token1 = TIANDITU_TOKEN
        ter.imageFormat = "image/png"
        ter.isSystem = true
        doc.mapSources.add(ter)
        // 系统自带地图源：天地图-注记（默认可见，不在地图源列表中显示，由主界面按钮控制）
        doc.mapSources.add(createAnnotationSource(true))
        // raster/vector empty by default
        doc.systemConfig.showCenterCross = true
        doc.systemConfig.photoAddWatermark = true
        return doc
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

    private fun createAnnotationSource(visible: Boolean): MapSource {
        val cia = MapSource(SYSTEM_SOURCE_TIANDITU_CIA, TIANDITU_CIA_URL, visible)
        cia.token1 = TIANDITU_TOKEN
        cia.imageFormat = "image/png"
        cia.isSystem = true
        cia.isAnnotation = true
        return cia
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
            // perform save asynchronously
            executor.submit {
                val cfgDir = AppDirectories.getConfigDir(appCtx)
                val docFile = File(cfgDir, DOCUMENT_FILE)
                try {
                    docFile.writeText(doc.toJson().toString(2), StandardCharsets.UTF_8)
                    // after successful save, notify listeners on main thread
                    mainHandler.post { notifyListeners() }
                } catch (e: IOException) {
                    Log.e(TAG, "failed to save document.json", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "failed to save document.json", e)
                }
            }
        }
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l.onDocumentChanged(document!!)
            } catch (ignored: Throwable) {
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
            if (instance == null) instance = DocumentManager()
            instance!!
        }
    }
}
