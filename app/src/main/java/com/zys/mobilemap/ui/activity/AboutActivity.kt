package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import com.zys.mobilemap.databinding.ActivityAboutBinding
import com.zys.mobilemap.update.APKVersionUtil
import com.zys.mobilemap.update.AppUpdateManager
import com.zys.globecore.NativeLayerInfo
import com.zys.globecore.NativeSrs

/**
 * 关于页：展示 GDAL / PROJ 版本与本机版本号，提供检查更新与项目主页入口。
 * GDAL 版本行可点击折叠/展开其支持的矢量驱动列表。
 */
class AboutActivity : AppSourcesActivity() {

    private var updateManager: AppUpdateManager? = null

    override fun appSourcesInstallCallBack() {
        updateManager?.checkForUpdates(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 仅用 ViewBinding 的根视图作内容：再调一次 setContentView(R.layout.*) 会白白 inflate 一棵随即丢弃的布局
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyDefaultPageStyle()

        // 后退按键
        binding.aboutIvBack.setOnClickListener { finish() }

        updateManager = AppUpdateManager(this)

        // GDAL 版本经 JNI 获取，只取一次；折叠箭头随展开态切换
        val gdalVersion = NativeLayerInfo.getGdalVersion()
        binding.gdalText.text = "gdal Version: $gdalVersion  \u25B8"

        // gdal 版本行点击折叠/展开支持的矢量驱动列表（首次展开时惰性加载）
        binding.gdalText.setOnClickListener {
            val drivers = binding.gdalDriversText
            if (drivers.visibility == View.VISIBLE) {
                drivers.visibility = View.GONE
                binding.gdalText.text = "gdal Version: $gdalVersion  \u25B8"
            } else {
                if (drivers.text.isEmpty()) drivers.text = NativeLayerInfo.getVectorDrivers() ?: ""
                drivers.visibility = View.VISIBLE
                binding.gdalText.text = "gdal Version: $gdalVersion  \u25BE"
            }
        }

        binding.projText.text = "proj Version: ${NativeSrs.getProjVersion()}"

        binding.VersionCode.text = "VersionCode: ${APKVersionUtil.getVersionCode(this)}"

        binding.VersionName.text = "当前版本 V${APKVersionUtil.getVersionName(this)}"

        binding.aboutCheckUpdate.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                updateManager?.checkForUpdates(true)
            } else {
                // 未授予“安装未知应用”权限：跳系统授权页，返回后经 appSourcesInstallCallBack 续跑检查
                Log.w(TAG, "未授予安装未知应用权限，跳转系统授权页")
                val packageUri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_INSTALL_UNKNOWN_SOURCES)
            }
        }

        binding.aboutGitLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gitee.com/z-gis/MobileMap"))
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateManager?.onHostResume()
    }

    override fun onPause() {
        updateManager?.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        updateManager?.release()
        updateManager = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AboutActivity"
    }
}
