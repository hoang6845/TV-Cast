package hoang.dqm.codebase.utils.pref

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.utils.toJson

class SpUtils internal constructor(private val SP_FILE_KEY: String){

    companion object {
        private val DEFAULT_SP_FILE_KEY: String = "${appInfo().appId}.SharedPreferenceUtils.DEFAULT_SP_FILE_NAME}"

        @JvmStatic
        fun getInstance(spFileName: String) = SpUtils(spFileName)

        @JvmStatic
        fun getDefaultInstance() = SpUtils(DEFAULT_SP_FILE_KEY)
    }

    private fun getSharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(SP_FILE_KEY, Context.MODE_PRIVATE)

    private fun getSpEditor(context: Context): SharedPreferences.Editor =
        getSharedPreferences(context).edit()

    fun<T> saveData(context:Context, data: T, key: String){
        val editor = getSpEditor(context)
        when (data){
            is Long -> editor.putLong(key, data)
            is String -> editor.putString(key, data)
            is Int -> editor.putInt(key, data)
            is Boolean -> editor.putBoolean(key, data)
            is Float -> editor.putFloat(key, data)
            else -> {
                editor.putString(key, data.toJson())
            }
        }
        editor.apply()
    }

    fun <T> getData(context: Context, key: String, type: Class<T>): T? {
        var retVal: T? = null
        getSharedPreferences(context).let {
            if (it.contains(key)) {
                try {
                    retVal = when {
                        type.isAssignableFrom(Long::class.java) -> it.getLong(key, Long.MIN_VALUE)
                        type.isAssignableFrom(Int::class.java) -> it.getInt(key, Int.MIN_VALUE)
                        type.isAssignableFrom(Float::class.java) -> it.getFloat(
                            key, Float.MIN_VALUE
                        )

                        type.isAssignableFrom(false.javaClass) -> it.getBoolean(key, false)
                        type.isAssignableFrom(String::class.java) -> {
                            it.getString(key, "")
                        }

                        else -> {
                            val json = it.getString(key, "")
                            if (!json.isNullOrEmpty()) {
                                val item = Gson().fromJson(json, type)
                                item
                            } else {
                                null
                            }
                        }
                    } as T?
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } else {
                retVal = null
            }
        }
        return retVal
    }

    fun removeKey(context: Context, key: String) = getSpEditor(context).remove(key).apply()

    fun checkIfExists(context: Context, key: String): Boolean =
        getSharedPreferences(context).contains(key)

    fun clearAll(context: Context): Boolean = getSpEditor(context).clear().commit()
}