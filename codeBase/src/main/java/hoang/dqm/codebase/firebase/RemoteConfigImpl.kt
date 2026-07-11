package hoang.dqm.codebase.firebase

class RemoteConfigImpl : RemoteConfig {
    private val firebaseConfig by lazy { BaseFirebaseRemoteConfig() }

    private fun config() = firebaseConfig

    override fun getString(config: String): String? {
        return config().getString(config)
    }

    override fun getLong(config: String): Long? {
        return config().getLong(config)
    }

    override fun getBoolean(config: String): Boolean? {
        return config().getBoolean(config)
    }

    override fun getDouble(config: String): Double? {
        return config().getDouble(config)
    }

    override fun fetchConfig(runnable: Runnable?) {
        config().fetchConfig(runnable)
    }

}