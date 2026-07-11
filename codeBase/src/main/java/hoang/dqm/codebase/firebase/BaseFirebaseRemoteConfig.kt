package hoang.dqm.codebase.firebase

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

internal class BaseFirebaseRemoteConfig : RemoteConfig {
    private var onConfigActivated: Runnable? = null
    private val remoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    override fun getString(config: String): String? {
        val value = remoteConfig.getString(config)
        Log.d("check data", "getString key=$config value=$value")

        return value
    }

    override fun getLong(config: String): Long? {
        return remoteConfig.getLong(config)
    }

    override fun getBoolean(config: String): Boolean? {
        return remoteConfig.getBoolean(config)
    }

    override fun getDouble(config: String): Double? {
        return remoteConfig.getDouble(config)
    }

    override fun fetchConfig(runnable: Runnable?) {
        onConfigActivated = runnable
        val firebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600)
            .build()
        firebaseRemoteConfig.setConfigSettingsAsync(configSettings)
        firebaseRemoteConfig.fetchAndActivate()
            .addOnSuccessListener {
                onConfigActivated?.run()
                onConfigActivated = null
                Log.d(
                    "RC",
                    "fetchConfig COMPLETE success"
                )
            }
            .addOnFailureListener {
                onConfigActivated?.run()
                onConfigActivated = null
                Log.d(
                    "RC",
                    "fetchConfig COMPLETE fail"
                )
            }
            .addOnCompleteListener {
                onConfigActivated?.run()
                onConfigActivated = null
                Log.d(
                    "RC",
                    "fetchConfig COMPLETE "
                )
            }
    }

}