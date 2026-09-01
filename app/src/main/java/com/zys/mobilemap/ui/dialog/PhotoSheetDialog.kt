package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zys.mobilemap.R
import com.zys.mobilemap.media.MediaAttachment
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.ui.activity.PhotoViewerActivity
import com.google.android.material.R as MaterialR

/**
 * 照片列表 BottomSheet：显示某拍照点位（Placemark）的全部照片，
 * 点击照片进入全屏查看（左右滑动切换，仅本点位附件）。
 */
class PhotoSheetDialog : BaseBottomSheetDialog(R.layout.dialog_photo_sheet) {

    private var placemarkId = -1L
    private var placemarkName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placemarkId = arguments?.getLong(ARG_PLACEMARK_ID, -1L) ?: -1L
        placemarkName = arguments?.getString(ARG_PLACEMARK_NAME) ?: "照片"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 标题与内容装配统一在 onCreateDialog 完成（基类 setContentView 后 onViewCreated 不会被回调，
        // 同 FeatureDetailDialog 处理方式）
        sheet.findViewById<TextView>(R.id.photo_sheet_title).text = placemarkName

        val attachments = if (placemarkId < 0) emptyList() else
            MediaStore.getInstance(requireContext()).loadPlacemarks()
                .firstOrNull { it.id == placemarkId }
                ?.attachments
                ?.filter { it.type == MediaAttachment.TYPE_PHOTO }
                ?: emptyList()

        // 无照片时显示空提示，避免仅剩标题无内容
        sheet.findViewById<TextView>(R.id.photo_sheet_empty).visibility =
            if (attachments.isEmpty()) View.VISIBLE else View.GONE

        val grid = sheet.findViewById<RecyclerView>(R.id.photo_sheet_grid)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        // 显式指定高度（每行 96dp，上限 360dp），规避折叠态下列表测量高度为 0 的问题
        if (attachments.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val rows = (attachments.size + 2) / 3
            val height = minOf((rows * 96 * density).toInt(), (360 * density).toInt())
            (grid.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.height = height
                grid.layoutParams = lp
            }
        }
        grid.adapter = PhotoThumbAdapter(attachments) { index ->
            val intent = Intent(requireContext(), PhotoViewerActivity::class.java)
            intent.putExtra(PhotoViewerActivity.EXTRA_PLACEMARK_ID, placemarkId)
            intent.putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
            startActivity(intent)
        }

        // 覆盖基类 onShowListener：圆角背景 + 布局完成后按内容实测高度设 peekHeight 并展开（同 FeatureDetailDialog）
        dialog.setOnShowListener { d ->
            val bottomSheet = (d as BottomSheetDialog)
                .findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            bottomSheet?.let { bs ->
                bs.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded)
                sheet.post {
                    BottomSheetBehavior.from(bs).apply {
                        peekHeight = sheet.height
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }
        return dialog
    }

    /** 缩略图适配器：采样解码，避免大图 OOM */
    private class PhotoThumbAdapter(
        private val attachments: List<MediaAttachment>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PhotoThumbAdapter.Holder>() {

        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_thumb, parent, false) as ImageView
            return Holder(view)
        }

        override fun getItemCount(): Int = attachments.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val attachment = attachments[position]
            val thumbnail = decodeThumbnail(attachment.path, 240, 240)
            if (thumbnail != null) {
                holder.image.setImageBitmap(thumbnail)
            } else {
                holder.image.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            holder.image.setOnClickListener { onClick(position) }
        }

        private fun decodeThumbnail(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = maxOf(1, inSampleSize)
            return BitmapFactory.decodeFile(filePath, options)
        }
    }

    companion object {
        private const val ARG_PLACEMARK_ID = "arg_placemark_id"
        private const val ARG_PLACEMARK_NAME = "arg_placemark_name"

        fun show(manager: FragmentManager, placemarkId: Long, placemarkName: String) {
            val dialog = PhotoSheetDialog()
            dialog.arguments = Bundle().apply {
                putLong(ARG_PLACEMARK_ID, placemarkId)
                putString(ARG_PLACEMARK_NAME, placemarkName)
            }
            dialog.show(manager, "PhotoSheetDialog")
        }
    }
}
