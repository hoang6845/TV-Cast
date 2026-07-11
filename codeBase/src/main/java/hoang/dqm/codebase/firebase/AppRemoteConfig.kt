package hoang.dqm.codebase.firebase

import android.app.Activity
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.base.application.getBaseApplication
import hoang.dqm.codebase.data.CategoryItem
import hoang.dqm.codebase.data.CategoryItemChannel
import hoang.dqm.codebase.data.CategoryItemUrl
import hoang.dqm.codebase.utils.gsonStrToList
import hoang.dqm.codebase.utils.readAssetsFile
import hoang.dqm.codebase.utils.strToObj

object AppRemoteConfig {
    private val baseConfig by lazy { RemoteConfigImpl() }
    const val REMOTE_DATA_CATEGORY_APP = "data_category_app"
    private const val ACCESS_KEY_REMOTE_DATA = "access_key_remote_data"
    const val DATA_CATEGORY_URL = "data_category_url"
    const val DATA_AVATAR = "data_avatar"
    const val DATA_PRICE = "data_price"
    const val TIME_DELAY_INTER_SPLASH_OPEN = "time_delay_inter_splash_vs_open"
    const val TIME_DELAY_SHOW_INTER = "time_delay_show_inter"
    const val IS_SHOW_AD_OPEN = "is_show_ad_open"
    const val IS_SHOW_ADS_APP = "is_show_ads_app"
    const val IS_SHOW_INTER_SPLASH = "is_show_inter_splash"

    fun fetchConfig( callback: Runnable){
        Log.d("AppRemoteConfig", "fetchConfig called, passing callback to baseConfig")
        baseConfig.fetchConfig(callback)
    }
    fun getAccessToken(): String {
        return baseConfig.getString(ACCESS_KEY_REMOTE_DATA) ?: ""
    }

    fun getStringValue(key: String, defaultValue: String = ""): String {
        return baseConfig.getString(key) ?: defaultValue
    }

    fun getBooleanValue(key: String, defaultValue: Boolean = false): Boolean {
        return baseConfig.getBoolean(key) ?: defaultValue
    }

    fun getLongValue(key: String, defaultValue: Long = 0L): Long {
        return baseConfig.getLong(key) ?: defaultValue
    }

    fun <T> getData(key: String, clazz: Class<T>): T? {
        return try {
            val json = baseConfig.getString(key)?.ifEmpty {
                getBaseApplication().assets.readAssetsFile("$key.json")
            }

            strToObj(json, clazz)

        } catch (e: Exception) {
            e.printStackTrace()

            if (appInfo().isDebug) {
                val json = getBaseApplication().assets.readAssetsFile("$key.json")
                strToObj(json, clazz)
            } else {
                null
            }
        }
    }

    fun <T> getListData(key: String, claszz: Class<T>): List<T> {
        return try {
            val json = baseConfig.getString(key)?.ifEmpty {
                getBaseApplication().assets.readAssetsFile("$key.json")
            }
            gsonStrToList(json, claszz)

        } catch (e: Exception) {
            e.printStackTrace()
//            emptyList()
           if (appInfo().isDebug) {
               val json = getBaseApplication().assets.readAssetsFile("$key.json")
               gsonStrToList(json, claszz)
           } else {
                emptyList()
           }
        }
    }

    fun getListCategoryMovie(languageKey: String): List<CategoryItem> {
        return try {
            val json = baseConfig.getString(REMOTE_DATA_CATEGORY_APP)?.ifEmpty {
                getBaseApplication().assets.readAssetsFile("$REMOTE_DATA_CATEGORY_APP.json")
            }
            Log.d("check data", "getListCategoryMovie: $json")
            json?.let { convertJsonToCategoryList(it, languageKey) } ?: emptyList()

        } catch (e: Exception) {
            e.printStackTrace()
//            emptyList()
            if (appInfo().isDebug) {
                val json = getBaseApplication().assets.readAssetsFile("$REMOTE_DATA_CATEGORY_APP.json")
                convertJsonToCategoryList(json, languageKey)
            } else {
                emptyList()
            }
        }
    }

    fun getListCategoryChannels(languageKey: String): List<CategoryItemChannel> {
        return try {
            val json = baseConfig.getString(REMOTE_DATA_CATEGORY_APP)?.ifEmpty {
                getBaseApplication().assets.readAssetsFile("$REMOTE_DATA_CATEGORY_APP.json")
            }
            Log.d("check data", "getListCategoryMovie: $json")
            json?.let { convertJsonToCategoryChannel(it, languageKey) } ?: emptyList()

        } catch (e: Exception) {
            e.printStackTrace()
//            emptyList()
            if (appInfo().isDebug) {
                val json = getBaseApplication().assets.readAssetsFile("$REMOTE_DATA_CATEGORY_APP.json")
                convertJsonToCategoryChannel(json, languageKey)
            } else {
                emptyList()
            }
        }
    }

    fun getListCategoryUrl(languageKey: String): List<CategoryItemUrl> {
        return try {
            val json = baseConfig.getString(DATA_CATEGORY_URL)?.ifEmpty {
                getBaseApplication().assets.readAssetsFile("$DATA_CATEGORY_URL.json")
            }
            Log.d("check data", "getListCategoryMovie data_category_url: $json")
            json?.let { convertJsonToCategoryUrlList(it, languageKey) } ?: emptyList()

        } catch (e: Exception) {
            e.printStackTrace()
//            emptyList()
            if (appInfo().isDebug) {
                val json = getBaseApplication().assets.readAssetsFile("$DATA_CATEGORY_URL.json")
                convertJsonToCategoryUrlList(json, languageKey)
            } else {
                emptyList()
            }
        }
    }

    fun convertJsonToCategoryUrlList(jsonString: String, langKey: String): List<CategoryItemUrl> {
        val jsonArray: JsonArray = JsonParser.parseString(jsonString).asJsonArray

        return jsonArray.map { jsonElement ->
            val obj = jsonElement.asJsonObject
            val type = obj.get("type")?.asString ?: ""
            var value = obj.get(langKey)?.asString ?: ""
            if (value.isEmpty()) value = obj.get("en")?.asString?: ""
            val url = obj.get("url")?.asString ?: ""
            CategoryItemUrl(type = type, value = value, url = url)
        }
    }

    fun convertJsonToCategoryList(jsonString: String, langKey: String): List<CategoryItem> {
        val jsonArray: JsonArray = JsonParser.parseString(jsonString).asJsonArray

        return jsonArray.map { jsonElement ->
            val obj = jsonElement.asJsonObject
            val type = obj.get("type")?.asString ?: ""
            val value = obj.get(langKey)?.asString ?: ""
            CategoryItem(type = type, value = value)
        }
    }

    fun convertJsonToCategoryChannel(jsonString: String, langKey: String): List<CategoryItemChannel> {
        val jsonArray: JsonArray = JsonParser.parseString(jsonString).asJsonArray

        return jsonArray.map { jsonElement ->
            val obj = jsonElement.asJsonObject
            val type = obj.get("type")?.asString ?: ""
            val value = obj.get(langKey)?.asString ?: ""
            CategoryItemChannel(type = type, value = value)
        }
    }




}
