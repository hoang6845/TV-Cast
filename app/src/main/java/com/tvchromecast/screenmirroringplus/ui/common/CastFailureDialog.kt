package com.tvchromecast.screenmirroringplus.ui.common

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.tvchromecast.screenmirroringplus.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference

private var activeCastFailureDialog = WeakReference<AlertDialog>(null)

private val mediaRouteDialogTags = setOf(
    "android.support.v7.mediarouter:MediaRouteChooserDialogFragment",
    "android.support.v7.mediarouter:MediaRouteControllerDialogFragment",
    "androidx.mediarouter:MediaRouteChooserDialogFragment",
    "androidx.mediarouter:MediaRouteControllerDialogFragment",
    "IPTVCastChooser"
)

fun Fragment.showCastFailureDialog(
    @StringRes titleRes: Int = R.string.text_something_went_wrong,
    @StringRes messageRes: Int = R.string.text_cast_failure_message,
    onDismiss: (() -> Unit)? = null
) {
    dismissMediaRouteDialogs()
    if (activeCastFailureDialog.get()?.isShowing == true) return

    val dialogView = LayoutInflater.from(requireContext())
        .inflate(R.layout.dialog_cast_failure, null, false)

    dialogView.findViewById<TextView>(R.id.text_title).setText(titleRes)
    dialogView.findViewById<TextView>(R.id.text_message).setText(messageRes)

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setView(dialogView)
        .create()

    activeCastFailureDialog = WeakReference(dialog)
    dialogView.findViewById<TextView>(R.id.button_ok).setOnClickListener {
        dialog.dismiss()
    }
    dialog.setOnDismissListener {
        if (activeCastFailureDialog.get() === dialog) {
            activeCastFailureDialog.clear()
        }
        onDismiss?.invoke()
    }
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    dialog.show()
}

private fun Fragment.dismissMediaRouteDialogs() {
    listOf(parentFragmentManager, childFragmentManager, activity?.supportFragmentManager)
        .filterNotNull()
        .distinct()
        .forEach { it.dismissMediaRouteDialogs() }
}

private fun FragmentManager.dismissMediaRouteDialogs() {
    mediaRouteDialogTags.forEach { tag ->
        (findFragmentByTag(tag) as? DialogFragment)?.dismissAllowingStateLoss()
    }
    fragments
        .filterIsInstance<DialogFragment>()
        .filter { fragment ->
            fragment.tag in mediaRouteDialogTags ||
                fragment::class.java.name.contains("mediaroute", ignoreCase = true)
        }
        .forEach { it.dismissAllowingStateLoss() }
}
