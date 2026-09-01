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
