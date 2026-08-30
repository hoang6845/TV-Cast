package com.tvchromecast.screenmirroringplus.ui.common

import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvchromecast.screenmirroringplus.R
import org.json.JSONObject
import java.util.WeakHashMap

private const val RECEIVER_ERROR_THROTTLE_MS = 4_000L
private const val RECEIVER_ERROR_DETAIL_MAX_LENGTH = 700

private val receiverErrorUiStates = WeakHashMap<Fragment, ReceiverErrorUiState>()

fun Fragment.showReceiverMediaErrorIfAny(
    rawMessage: String,
    logTag: String
): Boolean {
    Log.d(logTag, "Receiver message: $rawMessage")

    val error = rawMessage.toReceiverMediaError() ?: return false
    if (!isAdded || view == null) return true

    val now = SystemClock.elapsedRealtime()
    val currentState = receiverErrorUiStates[this]
    if (currentState?.hasShownForCurrentAttempt == true) {
        Log.d(logTag, "Receiver media error already shown; logging only: ${error.logSummary}")
        return true
    }
    receiverErrorUiStates[this] = ReceiverErrorUiState(
        hasShownForCurrentAttempt = true,
        shownAtMs = now
    )
    dismissActiveCastFailureDialog()

    Log.w(logTag, "Showing receiver media error once: ${error.logSummary}")

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.text_receiver_media_error_title)
        .setMessage(error.userMessage)
        .setPositiveButton(R.string.text_ok, null)
        .show()

    return true
}

fun Fragment.hasRecentReceiverMediaError(windowMs: Long = RECEIVER_ERROR_THROTTLE_MS): Boolean {
    val state = receiverErrorUiStates[this] ?: return false
    return state.hasShownForCurrentAttempt &&
        SystemClock.elapsedRealtime() - state.shownAtMs < windowMs
}

fun Fragment.resetReceiverMediaErrorUiState() {
    receiverErrorUiStates.remove(this)
}

private fun String.toReceiverMediaError(): ReceiverMediaError? {
    val json = runCatching { JSONObject(this) }.getOrNull() ?: return null
    val type = json.optString("type")
    if (type !in RECEIVER_MEDIA_ERROR_TYPES) return null

    val media = json.optJSONObject("media")
    val userMessage = json.optCleanString("userMessage")
        ?: json.optCleanString("message")
        ?: json.optCleanString("reason")
        ?: "The TV could not play this media."

    val technicalDetail = json.optCleanString("technicalDetail")
        ?: json.optCleanString("detail")
        ?: media?.let { "Media: ${it.toString().limitReceiverErrorDetail()}" }
    val displayDetail = technicalDetail?.toDisplayReceiverDetail(media)

    return ReceiverMediaError(
        userMessage = userMessage.limitReceiverErrorDetail(),
        technicalDetail = displayDetail?.limitReceiverErrorDetail(),
        dedupeKey = listOf(
            type,
            userMessage,
            technicalDetail,
            media?.optString("contentId")
        ).joinToString("|")
    )
}

private fun String.toDisplayReceiverDetail(media: JSONObject?): String {
    val normalized = lowercase()
    if (normalized.contains("\"detailederrorcode\":315") ||
        normalized.contains("\"errorcode\":315") ||
        normalized.contains("hls_network_invalid_segment")
    ) {
        return "Cast error 315: the TV received an invalid HLS segment. This is usually caused by a stream fragment URL being loaded as a full video, a blocked segment request, or an unsupported HLS segment format."
    }

    if (normalized.contains("\"detailederrorcode\":905") ||
        normalized.contains("\"errorcode\":905") ||
        normalized.contains("load_failed")
    ) {
        return "Cast error 905: the TV rejected the media load request. Check that the selected item is the full video or playlist and that the TV can reach the media URL."
    }

    if (normalized.contains("\"detailederrorcode\":104") ||
        normalized.contains("\"errorcode\":104") ||
        normalized.contains("media_src_not_supported")
    ) {
        return "Cast error 104: the TV does not support this media source, format, or codec."
    }

    if (!normalized.contains("\"playerstate\":\"idle\"") ||
        !normalized.contains("\"idlereason\":\"error\"")
    ) {
        return this
    }

    val mediaSummary = media?.let {
        listOfNotNull(
            it.optCleanString("contentType")?.let { value -> "type=$value" },
            it.optCleanString("streamType")?.let { value -> "stream=$value" }
        ).joinToString(", ")
            .takeIf { value -> value.isNotBlank() }
    }

    return if (mediaSummary.isNullOrBlank()) {
        "Receiver returned IDLE/ERROR without a specific cause."
    } else {
        "Receiver returned IDLE/ERROR without a specific cause ($mediaSummary)."
    }
}

private fun JSONObject.optCleanString(name: String): String? {
    return optString(name)
        .trim()
        .takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
}

private fun String.limitReceiverErrorDetail(): String {
    return if (length <= RECEIVER_ERROR_DETAIL_MAX_LENGTH) {
        this
    } else {
        take(RECEIVER_ERROR_DETAIL_MAX_LENGTH).trimEnd() + "..."
    }
}

private data class ReceiverMediaError(
    val userMessage: String,
    val technicalDetail: String?,
    val dedupeKey: String
) {
    val logSummary: String
        get() = buildString {
            append("key=").append(dedupeKey)
            if (!technicalDetail.isNullOrBlank()) {
                append(" detail=").append(technicalDetail)
            }
        }
}

private data class ReceiverErrorUiState(
    val hasShownForCurrentAttempt: Boolean,
    val shownAtMs: Long
)

private val RECEIVER_MEDIA_ERROR_TYPES = setOf(
    "MEDIA_PLAYBACK_ERROR",
    "MEDIA_ERROR",
    "MEDIA_STATUS_ERROR"
)
