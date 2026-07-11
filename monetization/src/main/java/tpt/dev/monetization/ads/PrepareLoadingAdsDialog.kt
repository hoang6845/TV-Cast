package tpt.dev.monetization.ads

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import tpt.dev.monetization.R

class PrepareLoadingAdsDialog(
    context: Context
) : Dialog(context, R.style.PrepareLoadingAdsDialogTheme) {
    init {
        setContentView(R.layout.dialog_prepare_loading_ads)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setCanceledOnTouchOutside(false)
        setCancelable(false)
    }
}
