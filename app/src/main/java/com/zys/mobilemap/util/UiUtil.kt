package com.zys.mobilemap.util

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * 窗口显示工具：启动页、地图主页、照片全屏页共用的沉浸式全屏设置，
 * 保证各页面窗口内边距一致，切换时画面不位移、状态栏不跳变。
 */
object UiUtil {

    /**
     * 应用全屏沉浸样式：无标题栏 + 隐藏虚拟导航栏 + 透明状态栏。
     * 需在 [AppCompatActivity.setContentView] 之前调用。
     */
    @Suppress("DEPRECATION")
    fun enableFullscreen(activity: AppCompatActivity) {
        // 设置没有标题栏
        activity.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        // 隐藏导航栏
        hideBottomUIMenu(activity)
        // 透明状态栏 @ 顶部（透明导航栏方案已弃用，改由沉浸式标志隐藏）
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    }

    /**
     * 隐藏虚拟按键并全屏（沉浸式粘滞，滑动可临时唤出）。
     * systemUiVisibility 与 SYSTEM_UI_FLAG_* 自 API 30 起弃用，但地图/拍照页靠它保证
     * 窗口内边距在各页面一致（启动加载遮罩与地图界面切换不位移）；改用 WindowInsetsController
     * 会改变状态栏/导航栏实际表现，需真机验证，故保留原行为仅抑制告警。
     */
    @Suppress("DEPRECATION")
    fun hideBottomUIMenu(activity: AppCompatActivity) {
        val decorView = activity.window.decorView
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * 进入“启动遮罩全屏”：在常态沉浸样式（[hideBottomUIMenu]）基础上再隐藏状态栏（叠加
     * SYSTEM_UI_FLAG_FULLSCREEN），使白底加载遮罩真正铺满整屏、顶部不留状态栏接缝。
     * 仅供启动加载遮罩显示阶段使用，遮罩淡出后应调用 [restoreStandardImmersive] 恢复常态样式。
     */
    @Suppress("DEPRECATION")
    fun enterLoadingFullscreen(activity: AppCompatActivity) {
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * 恢复常态沉浸样式（隐藏导航栏 + 显示透明状态栏），即 [enableFullscreen]/[hideBottomUIMenu]
     * 应用的窗口标志。启动加载遮罩淡出后调用，把界面从“全屏遮罩”切回地图常态显示。
     */
    fun restoreStandardImmersive(activity: AppCompatActivity) {
        hideBottomUIMenu(activity)
    }
}
