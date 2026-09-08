package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.PermissionUtil
import com.zys.mobilemap.util.UiUtil
import java.io.File
import java.io.FileOutputStream

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val launchMainRunnable = Runnable { this.launchMain() }
    private var waitingForAllFilesPermission = false
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与主界面一致的全屏沉浸窗口（隐藏导航栏 + 透明状态栏）：
        // 保证启动页与主界面加载遮罩的窗口内边距完全一致，切换时画面不位移、状态栏不跳变
        UiUtil.enableFullscreen(this)
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
            AppLog.i(TAG, "原生库 mobilemap 加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'mobilemap'", e)
            AppLog.e(TAG, "原生库 mobilemap 加载失败", e)
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
        // 全文件访问权限已就绪，将日志目录切到外部 调查宝/log，便于用户取出反馈
        AppLog.refreshLogDir(this)
        // 解压 proj 数据（PROJ 坐标转换依赖 proj.db 与格网改正数文件）
        extractProjData()
        // 加载或创建文档
        DocumentManager.getInstance().loadOrCreate(this)
        AppLog.i(TAG, "初始化完成，文档已加载")
        // 外部文件打开（ACTION_VIEW）走此入口时初始化完成后直接进主界面，跳过启动页停留
        if (intent?.action == Intent.ACTION_VIEW && intent?.data != null) {
            launchMain()
        } else {
            handler.postDelayed(launchMainRunnable, SPLASH_MIN_DURATION_MS)
        }
    }

    private fun extractProjData() {
        try {
            val projDir = File(filesDir, "proj")
            if (!projDir.exists()) {
                projDir.mkdirs()
            }
            copyAssetFolder("proj", projDir)
            initProjDataPath(projDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proj data", e)
            AppLog.e(TAG, "解压 proj 数据失败", e)
        }
    }

    /**
     * 递归复制 assets 目录到 [destDir]：子目录建同名目录后递归（保持层级），
     * 文件已存在时跳过，避免每次启动重复解压。
     */
    private fun copyAssetFolder(assetPath: String, destDir: File) {
        try {
            val children = assets.list(assetPath)
            // 空清单即叶子节点（assets 对文件返回空数组），按文件复制
            if (children.isNullOrEmpty()) {
                val leaf = File(destDir, assetPath.substringAfterLast('/'))
                if (!leaf.exists()) copyAssetFile(assetPath, leaf)
                return
            }
            if (!destDir.exists()) destDir.mkdirs()
            for (child in children) {
                val subPath = "$assetPath/$child"
                val subChildren = assets.list(subPath)
                if (subChildren.isNullOrEmpty()) {
                    val destFile = File(destDir, child)
                    if (!destFile.exists()) copyAssetFile(subPath, destFile)
                } else {
                    copyAssetFolder(subPath, File(destDir, child))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private external fun initProjDataPath(projDataPath: String?)

    private fun launchMain() {
        if (launched) return
        launched = true
        AppLog.i(TAG, "启动 MainActivity")

        val mainIntent = Intent(this, MainActivity::class.java)
        // 若 Splash 由外部 ACTION_VIEW（如微信打开矢量文件）拉起，透传 action/data 给 MainActivity，
        // 并传递 content URI 的临时读取权限，保证冷启动（未经 Launcher）也能加载外部文件
        val source = intent
        if (source != null && source.action == Intent.ACTION_VIEW && source.data != null) {
            mainIntent.action = Intent.ACTION_VIEW
            mainIntent.data = source.data
            mainIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(mainIntent)
        finish()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in, android.R.anim.fade_out
            )
        } else {
            // overridePendingTransition 自 API 34 起弃用，仅低版本分支使用（高版本走 overrideActivityTransition）
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    companion object {
        private const val TAG = "SplashActivity"

        /** 启动页最短停留时长（毫秒），避免一闪而过 */
        private const val SPLASH_MIN_DURATION_MS = 800L
    }
}