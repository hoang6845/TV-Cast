package hoang.dqm.codebase.base.activity

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.Window

object ActivityManager {
    private val activities by lazy {
        mutableListOf<BaseActivity<*, *>>()
    }

    fun addActivity(activity: BaseActivity<*, *>) {
        activities.add(activity)
    }

    fun removeActivity(activity: BaseActivity<*, *>) {
        activities.remove(activity)
    }

    fun getTopActivityOrNull(): BaseActivity<*, *>? {
        return try {
            activities.lastOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun finishAll() {
        for (activity in activities) {
            if (!activity.isFinishing) {
                activity.finish()
            }
        }
    }

    fun emptyActivity(): Boolean {
        return activities.isEmpty()
    }

    fun clearAll() {
        finishAll()
        activities.clear()
    }
}

fun isActivityFinished(context: Context?): Boolean {
    if (context == null) return true
    return if (context is Activity) {
        context.isDestroyed || context.isFinishing
    } else false
}

fun hasWindowFocus(w: Activity?): Boolean {
    return !isActivityFinished(w) && hasWindowFocus(w?.window)
}

fun hasWindowFocus(w: Window?): Boolean {
    if (w == null) return false
    return w.findViewById<View>(Window.ID_ANDROID_CONTENT).hasWindowFocus()
}