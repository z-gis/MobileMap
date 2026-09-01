package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.ui.activity.MainActivity
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.PermissionUtil
import java.io.File
import java.io.FileOutputStream

class SplashActivity : AppCompatActivity() {

    val TAG: String = "SplashActivity"
    val SPLASH_MIN_DURATION_MS: Long = 800L

    private val handler = Handler(Looper.getMainLooper())
    private val launchMainRunnable = Runnable { this.launchMain() }
    private var waitingForAllFilesPermission = false
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        loadNativeLibrary()

        if (!ensurePermission()) {
            return
        }

        initializeAndLaunch()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFilesPermission && hasAllFilesPermission()) {
            waitingForAllFilesPermission = false
            initializeAndLaunch()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchMainRunnable)
        super.onDestroy()
    }

    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("mobilemap")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'mobilemap'", e)
        }
    }

    private fun ensurePermission(): Boolean {
        if (hasAllFilesPermission()) {
            return true
        }
        waitingForAllFilesPermission = true
        Toast.makeText(this, R.string.open_file_permission_required, Toast.LENGTH_SHORT).show()
        PermissionUtil.goManagerFileAccess(this)
        return false
    }

    private fun hasAllFilesPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || PermissionUtil.isExternalStorageManager()
    }

    private fun initializeAndLaunch() {
        AppDirectories.ensureAll(this)
        // 初始化缓存
        //TileCacheManager.getInstance().init(this)
        // 解压proj数据
        extractProjData()
        // 加载或创建文档
        DocumentManager.getInstance().loadOrCreate(this)
        handler.postDelayed(launchMainRunnable, SPLASH_MIN_DURATION_MS)
    }

    private fun extractProjData() {
        try {
            val projDir = File(getFilesDir(), "proj")
            if (!projDir.exists()) {
                projDir.mkdirs()
            }
            copyAssetFolder("proj", projDir)
            initProjDataPath(projDir.getAbsolutePath())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proj data", e)
        }
    }

    private fun copyAssetFolder(assetPath: String, destDir: File?) {
        try {
            val files = getAssets().list(assetPath)
            if (files == null || files.size == 0) {
                copyAssetFile(assetPath, File(destDir, assetPath))
                return
            }
            for (file in files) {
                val subPath = assetPath + "/" + file
                val subFiles = getAssets().list(subPath)
                if (subFiles != null && subFiles.size > 0) {
                    val subDir = File(destDir, file)
                    if (!subDir.exists()) subDir.mkdirs()
                    copyAssetFolder(subPath, destDir)
                } else {
                    val destFile = File(destDir, file)
                    if (!destFile.exists()) {
                        copyAssetFile(subPath, destFile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: " + assetPath, e)
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        try {
            getAssets().open(assetPath).use { `in` ->
                FileOutputStream(destFile).use { out ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while ((`in`.read(buffer).also { len = it }) != -1) {
                        out.write(buffer, 0, len)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy: " + assetPath, e)
        }
    }

    private external fun initProjDataPath(projDataPath: String?)

    private fun launchMain() {
        if (launched) return
        launched = true

        startActivity(Intent(this, MainActivity::class.java))
        finish()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in, android.R.anim.fade_out
            )
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}