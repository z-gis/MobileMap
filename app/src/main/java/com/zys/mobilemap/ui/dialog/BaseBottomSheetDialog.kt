package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zys.mobilemap.R
import com.google.android.material.R as MaterialR

/**
 * 使用方法：BaseBottomSheetDialog.show()
 */
open class BaseBottomSheetDialog(var resource: Int) : BottomSheetDialogFragment() {

    var sheetView: View? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        sheetView = layoutInflater.inflate(resource, null)
        dialog.setContentView(sheetView!!)

        // Apply rounded background when dialog is shown (design_bottom_sheet may not be ready until show)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded)
        }

        return dialog
    }
}
