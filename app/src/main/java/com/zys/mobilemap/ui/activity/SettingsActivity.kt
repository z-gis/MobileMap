package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.location.BackgroundLocationController

class SettingsActivity : BaseStyledActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        // 应用默认的页面样式
        applyDefaultPageStyle()

        // 设置后退按键
        val backView: ImageView = findViewById(R.id.set_iv_back)
        backView.setOnClickListener { finish() }

        // 获取配置文档
        val config = DocumentManager.getInstance().getDocument()?.systemConfig ?: return

        // ==================== 显示类分组 ====================

        // 中心十字
        val centerCrossSwitch: SwitchCompat = findViewById(R.id.set_switch_cross)
        centerCrossSwitch.isChecked = config.showCenterCross
        centerCrossSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showCenterCross = isChecked
        }

        // 拍照标注图层可见性
        val mediaLayerSwitch: SwitchCompat = findViewById(R.id.set_switch_media_layer)
        mediaLayerSwitch.isChecked = config.showMediaLayer
        mediaLayerSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showMediaLayer = isChecked
        }

        // 照片名称标注显示
        val photoNameSwitch: SwitchCompat = findViewById(R.id.set_switch_photo_name)
        photoNameSwitch.isChecked = config.showPhotoName
        photoNameSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showPhotoName = isChecked
        }

        // 轨迹记录图层可见性
        val trackLayerSwitch: SwitchCompat = findViewById(R.id.set_switch_track_layer)
        trackLayerSwitch.isChecked = config.showTrackLayer
        trackLayerSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showTrackLayer = isChecked
        }

        // 测量结果图层可见性
        val measureLayerSwitch: SwitchCompat = findViewById(R.id.set_switch_measure_layer)
        measureLayerSwitch.isChecked = config.showMeasureLayer
        measureLayerSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showMeasureLayer = isChecked
        }

        // 坐标显示格式：开启为米（CGCS2000 3 度带），关闭为十进制度
        val coordMeterSwitch: SwitchCompat = findViewById(R.id.set_switch_coord_meter)
        coordMeterSwitch.isChecked = config.coordDisplayMeter
        coordMeterSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.coordDisplayMeter = isChecked
        }

        // KML/KMZ 名称标注显示（默认关）：退出设置页落盘后经文档变更通知重载 KML/KMZ 图层（开关已纳入图层缓存键）
        val kmlLabelSwitch: SwitchCompat = findViewById(R.id.set_switch_kml_label)
        kmlLabelSwitch.isChecked = config.showKmlLabel
        kmlLabelSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showKmlLabel = isChecked
        }

        // 3D 视图（球模式）：关闭为 2D（默认），开启为 3D。退出设置页落盘，主界面 onResume 读回即时生效
        val viewModeSwitch: SwitchCompat = findViewById(R.id.set_switch_view_mode)
        viewModeSwitch.isChecked = config.viewMode3d
        viewModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.viewMode3d = isChecked
        }

        // ==================== 设置类分组 ====================

        // 照片坐标水印
        val photoWatermarkSwitch: SwitchCompat = findViewById(R.id.set_switch_photo_watermark)
        photoWatermarkSwitch.isChecked = config.photoAddWatermark
        photoWatermarkSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.photoAddWatermark = isChecked
        }

        // 后台定位：与定位详情页同源；开关为「轨迹记录期后台定位」许可，切换后据当前记录态同步前台服务启停
        val bgLocationSwitch: SwitchCompat = findViewById(R.id.set_switch_background_location)
        bgLocationSwitch.isChecked = config.backgroundLocation
        bgLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.backgroundLocation = isChecked
            BackgroundLocationController.onSettingChanged(this)
        }

        // 帮助按键：进入使用说明页（三段可折叠说明）
        findViewById<android.view.View>(R.id.set_row_help).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        // 关于按键
        findViewById<android.view.View>(R.id.set_row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onDestroy() {
        // 设置项直接改的是内存中的文档对象，退出设置页时落盘，
        // 避免主界面异常退出（进程被回收）时丢失本次修改
        DocumentManager.getInstance().save(this)
        super.onDestroy()
    }
}
