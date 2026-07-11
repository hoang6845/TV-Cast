package hoang.dqm.codebase.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

private val mGson by lazy {
    Gson()
}

/**
 * format json string.
 *
 * @param obj
 * @return
 */
fun formatJson(obj: Any?): String {
    val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    return gson.toJson(obj)
}

/**
 * convert json object to json string.
 *
 * @param obj
 * @return
 */
fun gsonObjToStr(obj: Any?): String {
    var gsonString = ""
    gsonString = mGson?.toJson(obj) ?: ""
    return gsonString
}

/**
 * json string to object
 *
 * @param str
 * @param cls
 * @return
 */
fun <T> strToObj(str: String?, cls: Class<T>?): T? {
    var t: T? = null
    t = mGson.fromJson(str, cls)
    return t
}

/**
 * json string to list.
 *
 * @param str
 * @param cls
 * @return
 */
fun <T> gsonStrToList(str: String?, cls: Class<T>?): List<T> {
    val list: MutableList<T> = ArrayList()
    try {
        val gson = Gson()
        val array = JsonParser.parseString(str).asJsonArray
        for (jsonElement in array) {
            list.add(gson.fromJson(jsonElement, cls))
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return emptyList()
    }
    return list
}

fun <T> T.toJson(): String {
    return Gson().toJson(this)
}