package com.zys.mobilemap.media

import android.content.Context
import com.zys.mobilemap.R
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 媒体标注图层：地图默认读取媒体数据库（/调查宝/Media/media.db），
 * 每个拍照点位渲染一个 Placemark，点击可查看该点位全部照片。
 * 图层可见性由设置（[com.zys.mobilemap.doc.SystemConfig.showMediaLayer]）驱动。
 */
class MediaOverlayModel(private val context: Context, private val worldWindow: WorldWindow) {

    /** 媒体标注图层 */
    val layer: RenderableLayer = RenderableLayer().apply {
        displayName = "拍照标注"
        putUserProperty(MEDIA_LAYER_KEY, true)
    }

    private val store: MediaStore = MediaStore.getInstance(context)

    /** 已渲染的点位记录，供点击后查询附件 */
    private var placemarks: List<MediaPlacemark> = emptyList()

    /**
     * 将图层加入地图（幂等）。
     */
    fun attach() {
        if (!worldWindow.engine.layers.contains(layer)) {
            worldWindow.engine.layers.addLayer(layer)
        }
    }

    /**
     * 按设置可见性显示/隐藏。
     */
    fun setEnabled(visible: Boolean) {
        layer.isEnabled = visible
        worldWindow.requestRedraw()
    }

    /**
     * 从数据库重新加载全部拍照点位（仅渲染可见点位，隐藏的保留记录供管理对话框恢复）。
     */
    fun reload() {
        placemarks = store.loadPlacemarks()
        layer.clearRenderables()
        for (placemark in placemarks) {
            if (!placemark.visible) continue
            layer.addRenderable(createPlacemark(placemark))
        }
        worldWindow.requestRedraw()
    }

    /**
     * 按渲染对象查询对应点位记录（点击拾取后使用）。
     */
    fun findPlacemark(renderable: earth.worldwind.render.Renderable?): MediaPlacemark? {`
        val id = renderable?.getUserProperty(MEDIA_PLACEMARK_ID_KEY) as? Long ?: return null
        return placemarks.firstOrNull { it.id == id }
            ?: store.loadPlacemarks().firstOrNull { it.id == id }
    }

    private fun createPlacemark(record: MediaPlacemark): Placemark {
        val attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(R.drawable.ic_media))
        attributes.imageScale = 0.8
        // 标签使用数据库名称（拍照标识管理可重命名），空名称时回退拍照+附件数
        val count = record.attachments.size
        val name = if (record.name.isNotBlank()) record.name
        else if (count > 1) "拍照(${count})" else "拍照"
        val placemark = Placemark(
            Position(Angle.fromDegrees(record.latitude), Angle.fromDegrees(record.longitude), 0.0),
            attributes,
            name
        )
        placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        placemark.putUserProperty(MEDIA_PLACEMARK_ID_KEY, record.id)
        return placemark
    }

    companion object {
        /** 媒体标注图层标记，供拾取时识别 */
        const val MEDIA_LAYER_KEY = "mobilemap.media.layer"

        /** Placemark 用户属性：点位数据库主键 */
        const val MEDIA_PLACEMARK_ID_KEY = "mobilemap.media.placemark.id"

        /** 点位名称时间格式 */
        fun formatPlacemarkName(time: Long): String =
            "拍照 " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }
}
