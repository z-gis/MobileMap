package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zys.mobilemap.R
import com.zys.mobilemap.control.MaxHeightRecyclerView
import com.zys.mobilemap.media.MediaAttachment
import com.zys.mobilemap.media.MediaPackage
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.ui.activity.PhotoViewerActivity

/**
 * 照片列表 BottomSheet：显示某拍照点位（Placemark）的全部照片，
 * 点击照片进入全屏查看（左右滑动切换，仅本点位附件）。
 */
class PhotoSheetDialog : BaseBottomSheetDialog(R.layout.dialog_photo_sheet) {

    /** 目标点位主键；-1 表示参数缺失（如进程重建后 arguments 丢失），此时按空列表处理 */
    private val placemarkId: Long
        get() = arguments?.getLong(ARG_PLACEMARK_ID, -1L) ?: -1L

    private val placemarkName: String
        get() = arguments?.getString(ARG_PLACEMARK_NAME) ?: DEFAULT_TITLE

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 标题与内容装配统一在 onCreateDialog 完成（基类 setContentView 后 onViewCreated 不会被回调，
        // 同 FeatureDetailDialog 处理方式）
        sheet.findViewById<TextView>(R.id.photo_sheet_title).text = placemarkName

        // 按主键查库只取本点位，不拉全部点位再过滤；非照片附件不列入网格
        val placemark = if (placemarkId < 0) {
            null
        } else {
            MediaStore.getInstance(requireContext()).findPlacemark(placemarkId)
        }
        val attachments = placemark?.attachments
            ?.filter { it.type == MediaAttachment.TYPE_PHOTO }
            ?: emptyList()

        // 分享按钮：把本点位（坐标/名称/照片）打包经系统分享面板发出；无照片时隐藏
        val shareButton = sheet.findViewById<ImageView>(R.id.photo_sheet_share)
        if (placemark == null || attachments.isEmpty()) {
            shareButton.visibility = View.GONE
        } else {
            shareButton.setOnClickListener { MediaPackage.sharePlacemark(requireContext(), placemark) }
        }

        // 无照片时显示空提示，避免仅剩标题无内容
        sheet.findViewById<TextView>(R.id.photo_sheet_empty).visibility =
            if (attachments.isEmpty()) View.VISIBLE else View.GONE

        val grid = sheet.findViewById<MaxHeightRecyclerView>(R.id.photo_sheet_grid)
        grid.layoutManager = GridLayoutManager(requireContext(), GRID_SPAN_COUNT)
        // 网格高度须由内容驱动（wrap_content）才能形成手势档位，但必须给上限：
        // 无上限时照片过多会测得超过屏高，弹层被父布局截断后网格底部滚不到
        grid.maxHeightPx = listMaxHeightPx()
        grid.adapter = PhotoThumbAdapter(attachments) { index ->
            val intent = Intent(requireContext(), PhotoViewerActivity::class.java)
            intent.putExtra(PhotoViewerActivity.EXTRA_PLACEMARK_ID, placemarkId)
            intent.putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
            startActivity(intent)
        }

        // 圆角背景与可手势调整的高度档位由基类统一配置（见 BaseBottomSheetDialog）
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
            val thumbnail = decodeThumbnail(attachment.path, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)
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
        private const val DEFAULT_TITLE = "照片"

        /** 缩略图网格列数 */
        private const val GRID_SPAN_COUNT = 3

        /** 缩略图解码边长（像素）：采样解码到此尺寸，避免原图 OOM */
        private const val THUMBNAIL_SIZE_PX = 240

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
