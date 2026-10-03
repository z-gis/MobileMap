package com.zys.mobilemap.survey

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView

/**
 * 不可内部滚动的 ListView（嵌套在 ScrollView 中使用，高度由内容撑开）。
 */
class NonScrollListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 以 AT_MOST 模式展开全部子项高度
        val expandSpec = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, expandSpec)
    }
}
