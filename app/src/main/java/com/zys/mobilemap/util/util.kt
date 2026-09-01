package com.zys.mobilemap.util

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class util {

    companion object {
        fun enableFullscreen(activity: AppCompatActivity) {
            // 设置没有标题栏
            activity.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            // 隐藏导航栏
            hideBottomUIMenu(activity)
            // 透明状态栏 @ 顶部
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS) //A
            // 透明导航栏 @ 底部
            //getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);//B
        }

        fun hideBottomUIMenu(activity: AppCompatActivity) {
            //隐藏虚拟按键，并且全屏
            //for new api versions.
            val decorView = activity.getWindow().getDecorView()
            val uiOptions = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) /*| View.SYSTEM_UI_FLAG_FULLSCREEN*/
            decorView.setSystemUiVisibility(uiOptions)
        }
    }
}