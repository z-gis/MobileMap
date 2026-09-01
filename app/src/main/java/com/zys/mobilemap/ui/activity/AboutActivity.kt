package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import com.zys.mobilemap.R
import com.zys.mobilemap.databinding.ActivityAboutBinding
import com.zys.mobilemap.update.APKVersionUtil
import com.zys.mobilemap.update.AppUpdateManager

class AboutActivity : AppSourcesActivity() {
    private val TAG = "-------" + AboutActivity::class.java.simpleName + "-------"
    private var updateManager: AppUpdateManager? = null

    override fun appSourcesInstallCallBack() {
        updateManager?.checkForUpdates(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_about)

        val binding: ActivityAboutBinding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyDefaultPageStyle()

        // 后退按键
        binding.aboutIvBack.setOnClickListener { finish() }

        updateManager = AppUpdateManager(this)

        binding.gdalText.text = "gdal Version: " + getGDALVersion() + "  \u25B8"

        // gdal 版本行点击折叠/展开支持的矢量驱动列表（首次展开时惰性加载）
        binding.gdalText.setOnClickListener {
            val drivers = binding.gdalDriversText
            if (drivers.visibility == View.VISIBLE) {
                drivers.visibility = View.GONE
                binding.gdalText.text = "gdal Version: " + getGDALVersion() + "  \u25B8"
            } else {
                if (drivers.text.isEmpty()) drivers.text = getVectorDrivers() ?: ""
                drivers.visibility = View.VISIBLE
                binding.gdalText.text = "gdal Version: " + getGDALVersion() + "  \u25BE"
            }
        }

        binding.projText.text = "proj Version: " + getProjVersionString()

        binding.VersionCode.text = "VersionCode: " + APKVersionUtil.getVersionCode(this)

        binding.VersionName.text = "当前版本 V" + APKVersionUtil.getVersionName(this)

        binding.aboutCheckUpdate.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                updateManager?.checkForUpdates(true)
            } else {
                Log.e(TAG, "Call Permission ACTION_MANAGE_UNKNOWN_APP_SOURCES")
                val packageUri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, Settings_ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        }

        binding.aboutGitLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gitee.com/z-gis/MobileMap"))
            startActivity(intent)
        }
    }

    private fun getProjVersionString(): String {
        return getProjVersion().toString()
    }

    // 保持与 JNI 函数名 Java_com_zys_mobilemap_ui_activity_AboutActivity_getProjVersion 一致，
    // 因此声明为类成员 external 函数（不能放入 companion object，否则符号名会变化）
    external fun getProjVersion(): Int

    external fun getGDALVersion(): String?

    /** 枚举 GDAL 支持的矢量驱动（含读/写能力标注），每行一个 */
    external fun getVectorDrivers(): String?

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


}
