package tpt.dev.monetization.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle

class AdActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    var currentActivity: Activity? = null

    override fun onActivityCreated(
        activity: Activity,
        bundle: Bundle?
    ) {
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        bundle: Bundle
    ) {
    }

    override fun onActivityDestroyed(activity: Activity) {
        currentActivity = null
    }
}
