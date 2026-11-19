package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.zys.mobilemap.R
import com.zys.mobilemap.databinding.ActivityAboutBinding
import com.zys.mobilemap.update.APKVersionUtil
import com.zys.mobilemap.update.AppUpdateManager
import com.zys.globecore.NativeLayerInfo
import com.zys.globecore.NativeSrs
import com.zys.globecore.NativeVersion
import java.util.Locale

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
        binding.gdalText.text = getString(R.string.about_gdal_version, gdalVersion)

        // gdal 版本行点击折叠/展开支持的矢量驱动列表（首次展开时惰性加载）
        binding.gdalText.setOnClickListener {
            val drivers = binding.gdalDriversText
            if (drivers.isVisible) {
                drivers.visibility = View.GONE
                binding.gdalText.text = getString(R.string.about_gdal_version, gdalVersion)
            } else {
                if (drivers.text.isEmpty()) drivers.text = NativeLayerInfo.getVectorDrivers() ?: ""
                drivers.visibility = View.VISIBLE
                binding.gdalText.text = getString(R.string.about_gdal_version_expanded, gdalVersion)
            }
        }

        binding.projText.text = String.format(
            Locale.getDefault(),
            getString(R.string.about_proj_version),
            NativeSrs.getProjVersion()
        )

        // 渲染引擎版本 + 构建 ID（globecore 库自带，随 AAR 编入）：排查「装机产物是否本次引擎改动」看这行
        binding.engineText.text = getString(R.string.about_engine_version, NativeVersion.engineBuildId())

        binding.VersionCode.text = String.format(
            Locale.getDefault(),
            getString(R.string.about_version_code),
            APKVersionUtil.getVersionCode(this)
        )

        binding.VersionName.text = getString(
            R.string.about_current_version,
            APKVersionUtil.getVersionName(this)
        )

        binding.aboutCheckUpdate.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                updateManager?.checkForUpdates(true)
            } else {
                // 未授予“安装未知应用”权限：跳系统授权页，返回后经 appSourcesInstallCallBack 续跑检查
                Log.w(TAG, "未授予安装未知应用权限，跳转系统授权页")
                val packageUri = "package:$packageName".toUri()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_INSTALL_UNKNOWN_SOURCES)
            }
        }

        binding.aboutGitLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://gitee.com/z-gis/MobileMap".toUri())
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
