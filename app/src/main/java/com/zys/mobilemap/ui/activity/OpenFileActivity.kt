package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.zys.mobilemap.R
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.PermissionUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件选择器Activity
 * 用于选择地图文件（如KML、GPX等），支持目录浏览和文件过滤
 */
class OpenFileActivity : BaseStyledActivity() {

    /** 文件列表数据 */
    private val items = mutableListOf<FileItem>()

    /** 文件列表适配器 */
    private val adapter = FileListAdapter()

    /** 面包屑容器 */
    private lateinit var breadcrumbContainer: LinearLayout

    /** 空状态提示TextView */
    private lateinit var emptyTextView: TextView

    /** 允许的文件类型数组 */
    private var fileTypeArray: Array<String>? = null

    /** 根目录（导航起点） */
    private var rootDirectory: File? = null

    /** 当前目录 */
    private var currentDirectory: File? = null

    /** Intent 传入的默认目录（授权返回后复用，避免退回外部存储根） */
    private var defaultDirArg: String? = null

    /** 是否已提示过全文件访问权限 */
    private var promptedAllFilesPermission = false

    /**
     * Activity创建时调用
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_openfile)

        applyDefaultPageStyle()

        bindViews()
        readArguments()
        updateList(currentDirectory)
    }

    /**
     * 绑定视图组件
     */
    private fun bindViews() {
        val backView: ImageView = findViewById(R.id.set_iv_back)
        backView.setOnClickListener { finish() }
        breadcrumbContainer = findViewById(R.id.open_breadcrumb_container)
        emptyTextView = findViewById(R.id.open_tv_empty)
        val listView: ListView = findViewById(R.id.open_lv_list)
        listView.setAdapter(adapter)
        listView.setOnItemClickListener { _, _, position, _ ->
            onItemSelected(items[position])
        }

        // 处理返回键事件（返回上级目录）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigateUp()) {
                    finish()
                }
            }
        })
    }

    /**
     * 读取Intent参数
     */
    private fun readArguments() {
        fileTypeArray = intent.getStringArrayExtra(EXTRA_FILE_TYPES)
        val defaultDir = intent.getStringExtra(EXTRA_DEFAULT_DIR)
        defaultDirArg = defaultDir
        // 根目录固定为可读存储根：作为面包屑起点与向上导航边界，保留完整多级面包屑
        rootDirectory = resolveReadableRoot(null)
        // 初始目录：优先默认目录（需存在、是目录、且位于根目录之下），否则回退根目录
        currentDirectory = resolveInitialDir(defaultDir, rootDirectory) ?: rootDirectory

        // Android 11+ 需要申请全文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !PermissionUtil.isExternalStorageManager()
        ) {
            Toast.makeText(this, R.string.open_file_permission_required, Toast.LENGTH_SHORT).show()
            PermissionUtil.goManagerFileAccess(this)
            promptedAllFilesPermission = true
        }
    }

    /**
     * 解析初始浏览目录：传入的默认目录需存在、是目录且位于根目录之下，
     * 以保证面包屑能从根目录逐级构建并支持向上导航；不满足时返回 null 由调用方回退根目录。
     */
    private fun resolveInitialDir(defaultDir: String?, root: File?): File? {
        if (defaultDir.isNullOrEmpty() || root == null) return null
        val dir = File(defaultDir)
        if (!dir.exists() || !dir.isDirectory) return null
        if (!dir.absolutePath.startsWith(root.absolutePath)) return null
        return dir
    }

    /**
     * 解析可读的根目录。
     * 优先使用默认目录，其次使用外部存储根目录，最后兜底到应用目录。
     */
    private fun resolveReadableRoot(defaultDir: String?): File {
        val candidates = mutableListOf<File>()
        if (!defaultDir.isNullOrEmpty()) {
            candidates.add(File(defaultDir))
        }

        val externalRoot = Environment.getExternalStorageDirectory()
        if (externalRoot != null) {
            candidates.add(externalRoot)
        }

        candidates.add(AppDirectories.getRootDir(this))

        val externalFilesDir = getExternalFilesDir(null)
        if (externalFilesDir != null) {
            candidates.add(externalFilesDir)
        }

        candidates.add(filesDir)

        // 逐个候选目录取首个已存在的目录；均不可用时兜底应用内部目录
        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: filesDir
    }

    /**
     * Activity恢复时调用
     * 检查权限是否已授予
     */
    override fun onResume() {
        super.onResume()
        // 如果之前提示过权限，现在检查是否已授予
        if (promptedAllFilesPermission
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && PermissionUtil.isExternalStorageManager()
        ) {
            promptedAllFilesPermission = false
            rootDirectory = resolveReadableRoot(null)
            currentDirectory = resolveInitialDir(defaultDirArg, rootDirectory) ?: rootDirectory
            updateList(currentDirectory)
        }
    }

    /**
     * 列表项点击处理
     *
     * @param item 被点击的文件项
     */
    private fun onItemSelected(item: FileItem) {
        if (item.file.isDirectory) {
            // 目录：进入浏览
            updateList(item.file)
            return
        }

        // 文件：返回选中结果
        val result = Intent()
        result.putExtra(RESULT_PATH, item.file.absolutePath)
        setResult(RESULT_OK, result)
        finish()
    }

    /**
     * 更新文件列表
     *
     * @param directory 要显示的目录
     */
    private fun updateList(directory: File?) {
        currentDirectory = directory
        updateBreadcrumb()

        items.clear()
        val files = currentDirectory?.listFiles()

        if (files != null) {
            val directories = mutableListOf<File>()
            val regularFiles = mutableListOf<File>()

            // 分类：目录和文件
            for (file in files) {
                if (file.isDirectory) {
                    directories.add(file)
                } else if (matchesExtension(file)) {
                    regularFiles.add(file)
                }
            }

            // 按名称排序（不区分大小写）
            val comparator = Comparator<File> { left, right ->
                left.name.compareTo(right.name, ignoreCase = true)
            }
            directories.sortWith(comparator)
            regularFiles.sortWith(comparator)

            // 添加到列表（目录优先）
            for (file in directories) {
                items.add(FileItem(file, true))
            }
            for (file in regularFiles) {
                items.add(FileItem(file, false))
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !PermissionUtil.isExternalStorageManager()
        ) {
            // Android 11+ 无权限时显示提示
            emptyTextView.setText(R.string.open_file_permission_required)
        } else {
            // 目录为空
            emptyTextView.setText(R.string.open_file_empty)
        }

        // 显示/隐藏空状态
        emptyTextView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /**
     * 检查文件是否匹配允许的扩展名
     *
     * @param file 文件
     * @return 是否匹配
     */
    private fun matchesExtension(file: File): Boolean {
        // 如果没有指定文件类型，则允许所有文件
        val types = fileTypeArray
        if (types.isNullOrEmpty()) {
            return true
        }

        val fileName = file.name
        if (fileName.isEmpty()) {
            return false
        }

        val lowerFileName = fileName.trim().lowercase(Locale.ROOT)
        for (item in types) {
            // 规范化扩展名：去空白转小写，并依次移除通配前缀 * 与点号
            val normalized = item.trim().lowercase(Locale.ROOT).removePrefix("*").removePrefix(".")
            if (normalized.isEmpty()) {
                continue
            }

            // 通配符匹配全部文件
            if (normalized == "*" || normalized == "*.*") {
                return true
            }

            // 扩展名匹配
            if (lowerFileName.endsWith(".$normalized")) {
                return true
            }
        }
        return false
    }

    /**
     * 返回上级目录
     *
     * @return 是否成功返回
     */
    private fun navigateUp(): Boolean {
        val current = currentDirectory ?: return false
        // 已经在根目录
        if (current == rootDirectory) {
            Toast.makeText(this, R.string.open_file_root_tip, Toast.LENGTH_SHORT).show()
            return false
        }

        val parent = current.parentFile
        if (parent == null) {
            Toast.makeText(this, R.string.open_file_root_tip, Toast.LENGTH_SHORT).show()
            return false
        }

        updateList(parent)
        return true
    }

    /**
     * 构建面包屑导航。
     * 从 rootDirectory 到 currentDirectory，每一级目录显示为一个可点击的 TextView，
     * 用 " › " 分隔。最后一级为当前目录，加粗且不可点击。
     */
    private fun updateBreadcrumb() {
        breadcrumbContainer.removeAllViews()

        val pathSegments = buildPathSegments()
        val separator = getString(R.string.open_file_breadcrumb_separator)

        for (i in pathSegments.indices) {
            val segment = pathSegments[i]
            val isLast = (i == pathSegments.size - 1)

            if (i > 0) {
                val sepView = TextView(this)
                sepView.text = separator
                sepView.setTextColor(0xFF9CA3AF.toInt())
                sepView.textSize = 14f
                sepView.setPadding(12, 0, 12, 0)
                breadcrumbContainer.addView(sepView)
            }

            val crumbView = TextView(this)
            crumbView.text = getBreadcrumbLabel(segment, i == 0)
            crumbView.textSize = 14f
            crumbView.setPadding(0, 0, 0, 0)

            if (isLast) {
                crumbView.setTextColor(0xFF1F2937.toInt())
                crumbView.setTypeface(null, Typeface.BOLD)
            } else {
                crumbView.setTextColor(0xFF3B82F6.toInt())
                crumbView.setOnClickListener { updateList(segment) }
            }

            breadcrumbContainer.addView(crumbView)
        }
    }

    /**
     * 构建从 rootDirectory 到 currentDirectory 的路径段列表。
     */
    private fun buildPathSegments(): List<File> {
        val root = rootDirectory
        val segments = mutableListOf<File>()
        if (root == null) {
            return segments
        }
        segments.add(root)

        val current = currentDirectory
        if (current == null || current == root) {
            return segments
        }

        val rootPath = root.absolutePath
        val currentPath = current.absolutePath

        if (!currentPath.startsWith(rootPath)) {
            return segments
        }

        var relative = currentPath.substring(rootPath.length)
        if (relative.startsWith("/")) {
            relative = relative.substring(1)
        }
        if (relative.isEmpty()) {
            return segments
        }

        val parts = relative.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var accumulated: File = root
        for (part in parts) {
            accumulated = File(accumulated, part)
            segments.add(accumulated)
        }

        return segments
    }

    /**
     * 获取面包屑段落的显示标签。
     * 根目录显示"内部存储"，其他显示目录名。
     */
    private fun getBreadcrumbLabel(segment: File, isRoot: Boolean): String {
        if (isRoot) {
            return getString(R.string.open_file_internal_storage)
        }
        return segment.name
    }

    /**
     * 格式化文件大小
     *
     * @param bytes 文件字节数
     * @return 格式化后的大小字符串
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }
        var size = bytes / 1024.0
        var unit = "KB"
        if (size >= 1024.0) {
            size /= 1024.0
            unit = "MB"
        }
        if (size >= 1024.0) {
            size /= 1024.0
            unit = "GB"
        }
        return String.format(Locale.getDefault(), "%.2f %s", size, unit)
    }

    /**
     * 文件项数据类
     */
    private class FileItem(
        /** 文件对象 */
        val file: File,
        /** 是否为目录 */
        val directory: Boolean
    )

    /**
     * 文件列表适配器
     */
    private inner class FileListAdapter : BaseAdapter() {

        /** 时间格式化器 */
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        override fun getCount(): Int {
            return items.size
        }

        override fun getItem(position: Int): FileItem {
            return items[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var view = convertView
            val holder: ViewHolder
            if (view == null) {
                view = layoutInflater.inflate(R.layout.item_open_file, parent, false)
                holder = ViewHolder(view)
                view.tag = holder
            } else {
                holder = view.tag as ViewHolder
            }

            val item = getItem(position)
            holder.nameView.text = item.file.name
            // 目录显示修改时间，文件显示修改时间和大小
            holder.metaView.text = if (item.directory) {
                getString(R.string.open_file_item_folder_meta, timeFormat.format(Date(item.file.lastModified())))
            } else {
                getString(
                    R.string.open_file_item_file_meta,
                    timeFormat.format(Date(item.file.lastModified())),
                    formatFileSize(item.file.length())
                )
            }
            // 设置图标表情
            holder.iconView.text = if (item.directory) "📁" else "📄"
            return view
        }
    }

    /**
     * 视图持有者
     */
    private class ViewHolder(root: View) {
        /** 图标视图 */
        val iconView: TextView = root.findViewById(R.id.open_item_icon)

        /** 名称视图 */
        val nameView: TextView = root.findViewById(R.id.open_item_name)

        /** 元数据视图（时间/大小） */
        val metaView: TextView = root.findViewById(R.id.open_item_meta)
    }

    companion object {
        /** Intent参数 - 默认目录路径 */
        const val EXTRA_DEFAULT_DIR = "def_dir"

        /** Intent参数 - 文件类型过滤数组 */
        const val EXTRA_FILE_TYPES = "file_type"

        /** 返回结果 - 选中的文件路径 */
        const val RESULT_PATH = "map"
    }
}
