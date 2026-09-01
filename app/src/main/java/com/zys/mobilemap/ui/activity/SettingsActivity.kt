package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager

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

        // ==================== 设置类分组 ====================

        // 照片坐标水印
        val photoWatermarkSwitch: SwitchCompat = findViewById(R.id.set_switch_photo_watermark)
        photoWatermarkSwitch.isChecked = config.photoAddWatermark
        photoWatermarkSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.photoAddWatermark = isChecked
        }

        // 后台定位
        val bgLocationSwitch: SwitchCompat = findViewById(R.id.set_switch_background_location)
        bgLocationSwitch.isChecked = config.backgroundLocation
        bgLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.backgroundLocation = isChecked
        }

        // 关于按键
        findViewById<android.view.View>(R.id.set_row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
