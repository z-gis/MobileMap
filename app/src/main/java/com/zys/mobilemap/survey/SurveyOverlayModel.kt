package com.zys.mobilemap.survey

import android.content.Context
import com.zys.mobilemap.R
import com.zys.mobilemap.util.AppLog
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes

/**
 * 样地调查地图覆盖模型：将已保存的样地记录渲染为 Placemark 叠加在地图上。
 *
 * 使用 [R.drawable.ic_sample] 图标，点击可识别样地（通过 userProperty 中的 plotId）。
 * 调查模式开启时显示，退出时隐藏。
 */
class SurveyOverlayModel(private val context: Context, private val worldWindow: WorldWindow) {

    companion object {
        private const val TAG = "SurveyOverlayModel"
        /** 样地图层标记，供拾取时识别 */
        const val SURVEY_LAYER_KEY = "mobilemap.survey.layer"
        /** Placemark 用户属性：样地数据库主键 */
        const val SURVEY_PLOT_ID_KEY = "mobilemap.survey.plot.id"
        /** Placemark 用户属性：源文件路径 */
        const val SURVEY_SOURCE_PATH_KEY = "mobilemap.survey.source.path"
        /** Placemark 用户属性：源要素 FID */
        const val SURVEY_SOURCE_FID_KEY = "mobilemap.survey.source.fid"
    }

    val layer: RenderableLayer = RenderableLayer().apply {
        displayName = "样地调查"
        putUserProperty(SURVEY_LAYER_KEY, true)
        isEnabled = false // 默认隐藏，调查模式开启时显示
    }

    private val iconDensityScale: Double =
        context.resources.displayMetrics.density.toDouble()

    fun attach() {
        worldWindow.engine.layers.addLayer(layer)
        AppLog.i(TAG, "样地图层已挂载")
    }

    fun detach() {
        worldWindow.engine.layers.removeLayer(layer)
    }

    /** 重新加载全部样地 Placemark */
    fun reload() {
        layer.clearRenderables()
        val plots = SampleSurveyStore.loadAllPlots(context)
        for (plot in plots) {
            if (plot.centerLatitude.isNaN() || plot.centerLongitude.isNaN()) continue
            layer.addRenderable(createPlacemark(plot))
        }
        worldWindow.requestRedraw()
        AppLog.i(TAG, "样地图层刷新：${plots.size} 条记录")
    }

    fun setEnabled(enabled: Boolean) {
        layer.isEnabled = enabled
        worldWindow.requestRedraw()
    }

    /** 从 Placemark 提取样地 ID（供点击识别） */
    fun findPlotId(renderable: Any?): Long? {
        if (renderable !is Placemark) return null
        val raw: Any? = renderable.getUserProperty(SURVEY_PLOT_ID_KEY)
        return raw as? Long
    }

    private fun createPlacemark(plot: SurveyPlot): Placemark {
        val attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(R.drawable.ic_sample))
        attributes.imageScale = 0.6 * iconDensityScale
        val pos = Position(
            Angle.fromDegrees(plot.centerLatitude),
            Angle.fromDegrees(plot.centerLongitude),
            0.0
        )
        val label = "样地#${plot.plotNo}"
        val placemark = Placemark(pos, attributes, label)
        placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        placemark.putUserProperty(SURVEY_PLOT_ID_KEY, plot.id)
        placemark.putUserProperty(SURVEY_SOURCE_PATH_KEY, plot.sourcePath)
        placemark.putUserProperty(SURVEY_SOURCE_FID_KEY, plot.sourceFid)
        placemark.isPickEnabled = true
        return placemark
    }
}
