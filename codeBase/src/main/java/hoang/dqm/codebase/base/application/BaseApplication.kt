package hoang.dqm.codebase.base.application

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import hoang.dqm.codebase.data.AppInfo
import hoang.dqm.codebase.utils.pref.LocalCache

abstract class BaseApplication : Application(), DefaultLifecycleObserver {
    companion object {
        lateinit var INSTANCE: BaseApplication
    }

    abstract val appInfo: AppInfo

    override fun onCreate() {
        super<Application>.onCreate()
        INSTANCE = this
        LocalCache.getInstance().initContext(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
}

fun getBaseApplication(): BaseApplication {
    return BaseApplication.INSTANCE
}

fun appInfo(): AppInfo {
    return getBaseApplication().appInfo
}