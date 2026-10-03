package com.zys.mobilemap.ui.activity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.zys.mobilemap.R
import com.zys.mobilemap.media.MediaAttachment
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.util.UiUtil

/**
 * 全屏照片浏览器：只显示指定拍照点位（Placemark）的照片附件，
 * 左右滑动切换上一张/下一张。
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var indexView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        UiUtil.enableFullscreen(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val placemarkId = intent.getLongExtra(EXTRA_PLACEMARK_ID, -1L)
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)

        val attachments = if (placemarkId < 0) emptyList() else
            MediaStore.getInstance(this).loadPlacemarks()
                .firstOrNull { it.id == placemarkId }
                ?.attachments
                ?.filter { it.type == MediaAttachment.TYPE_PHOTO }
                ?: emptyList()

        if (attachments.isEmpty()) {
            finish()
            return
        }

        indexView = findViewById(R.id.photo_viewer_index)
        pager = findViewById(R.id.photo_viewer_pager)
        pager.adapter = PhotoPageAdapter(attachments)
        pager.setCurrentItem(initialIndex.coerceIn(0, attachments.size - 1), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                indexView.text = "${position + 1} / ${attachments.size}"
            }
        })
        indexView.text = "${pager.currentItem + 1} / ${attachments.size}"
    }

    /** 单页大图适配器：按屏幕尺寸采样解码，避免大图 OOM */
    private class PhotoPageAdapter(
        private val attachments: List<MediaAttachment>
    ) : RecyclerView.Adapter<PhotoPageAdapter.Holder>() {

        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_page, parent, false)
            return Holder(view.findViewById(R.id.photo_page_image))
        }

        override fun getItemCount(): Int = attachments.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bitmap = decodeSampled(attachments[position].path, MAX_EDGE)
            if (bitmap != null) {
                holder.image.setImageBitmap(bitmap)
            } else {
                holder.image.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }

        private fun decodeSampled(filePath: String, maxEdge: Int): Bitmap? {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            var inSampleSize = 1
            val longEdge = maxOf(options.outWidth, options.outHeight)
            while (longEdge / inSampleSize > maxEdge) {
                inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = maxOf(1, inSampleSize)
            return BitmapFactory.decodeFile(filePath, options)
        }
    }

    companion object {
        const val EXTRA_PLACEMARK_ID = "extra_placemark_id"
        const val EXTRA_INITIAL_INDEX = "extra_initial_index"

        /** 大图解码最长边上限 */
        private const val MAX_EDGE = 2048
    }
}
