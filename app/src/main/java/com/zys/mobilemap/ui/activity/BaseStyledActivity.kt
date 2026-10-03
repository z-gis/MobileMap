package com.zys.mobilemap.ui.activity

import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zys.mobilemap.R

/**
 * 统一处理普通页面的状态栏和 ActionBar 样式。
 */
abstract class BaseStyledActivity : AppCompatActivity() {

    /**
     * 应用于多数页面的统一样式。
     * 建议在 setContentView(...) 后调用。
     */
    protected fun applyDefaultPageStyle() {
        // statusBarColor 自 API 35 起弃用；低版本仍需它统一页面底色，
        // 新 API（WindowInsets 控制）会改变状态栏表现，故保留原行为仅抑制告警
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.activity_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            val decorView = window.decorView
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        supportActionBar?.hide()
    }
}
