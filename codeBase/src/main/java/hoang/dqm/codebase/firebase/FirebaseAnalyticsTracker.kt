package hoang.dqm.codebase.firebase

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import hoang.dqm.codebase.base.application.appInfo

object FirebaseAnalyticsTracker {
    private const val TAG = "FirebaseScreenTracker"
    private const val EVENT_SCREEN_TIME = "screen_time"
    private const val PARAM_DURATION_MS = "duration_ms"
    private const val PARAM_DURATION_SEC = "duration_sec"
    private const val PARAM_SCREEN_TYPE = "screen_type"
    private const val PARAM_APP_ID = "app_id"

    fun logScreenView(
        context: Context,
        screenName: String,
        screenClass: String,
        screenType: String
    ) {
        runCatching {
            FirebaseAnalytics.getInstance(context.applicationContext).logEvent(
                FirebaseAnalytics.Event.SCREEN_VIEW,
                Bundle().apply {
                    putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                    putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
                    putString(PARAM_SCREEN_TYPE, screenType)
                    putString(PARAM_APP_ID, appInfo().appId)
                }
            )
            Log.d(TAG, "screen_view name=$screenName class=$screenClass type=$screenType")
        }.onFailure {
            Log.e(TAG, "logScreenView failed: ${it.message}", it)
        }
    }

    fun logScreenTime(
        context: Context,
        screenName: String,
        screenClass: String,
        screenType: String,
        durationMs: Long
    ) {
        if (durationMs <= 0L) return

        runCatching {
            FirebaseAnalytics.getInstance(context.applicationContext).logEvent(
                EVENT_SCREEN_TIME,
                Bundle().apply {
                    putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                    putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
                    putString(PARAM_SCREEN_TYPE, screenType)
                    putString(PARAM_APP_ID, appInfo().appId)
                    putLong(PARAM_DURATION_MS, durationMs)
                    putLong(PARAM_DURATION_SEC, durationMs / 1000L)
                }
            )
            Log.d(
                TAG,
                "screen_time name=$screenName class=$screenClass type=$screenType durationMs=$durationMs"
            )
        }.onFailure {
            Log.e(TAG, "logScreenTime failed: ${it.message}", it)
        }
    }
}
