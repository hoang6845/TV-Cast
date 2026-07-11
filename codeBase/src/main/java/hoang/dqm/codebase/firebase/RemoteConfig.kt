package hoang.dqm.codebase.firebase

interface RemoteConfig {
    fun getString(config: String): String?

    fun getLong(config: String): Long?

    fun getBoolean(config: String): Boolean?

    fun getDouble(config: String): Double?

    fun fetchConfig(runnable: Runnable? = null)
}