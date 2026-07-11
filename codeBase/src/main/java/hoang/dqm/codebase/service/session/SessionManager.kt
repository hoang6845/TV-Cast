package hoang.dqm.codebase.service.session

import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.data.DrawResult
import hoang.dqm.codebase.utils.gsonObjToStr
import hoang.dqm.codebase.utils.gsonStrToList
import hoang.dqm.codebase.utils.pref.LocalCache

private const val IS_MUSIC = "IS_MUSIC"
private const val IS_SOUND_FX = "IS_SOUND_FX"
private const val IS_VIBRATE = "IS_VIBRATE"
private const val IS_FIRST_SPLASH = "IS_FIRST_SPLASH"
private const val IS_FIRST = "IS_FIRST"
private const val IS_RANDOM_COLOR = "IS_RANDOM_COLOR"

fun setFirstScene(clazz: BaseFragment<*, *>, isFirst: Boolean) {
    LocalCache.getInstance().put(clazz::class.java.name, isFirst)
}

fun isFirstScene(clazz: BaseFragment<*, *>): Boolean {
    return LocalCache.getInstance().getData(clazz::class.java.name, Boolean::class.java) ?: true
}


fun saveFirst(isFirst: Boolean) {
    LocalCache.getInstance().put(IS_FIRST, isFirst)
}

fun isFirst(): Boolean {
    return LocalCache.getInstance().getData(IS_FIRST, Boolean::class.java) ?: true
}


fun setFirstSplash(isFirst: Boolean) {
    LocalCache.getInstance().put(IS_FIRST_SPLASH, isFirst)
}

fun isFirstSplash(): Boolean {
    return LocalCache.getInstance().getData(IS_FIRST_SPLASH, Boolean::class.java) ?: true
}

fun saveMusic(data: Boolean) {
    LocalCache.getInstance().put(IS_MUSIC, data)
}

fun isMusic(): Boolean {
    return LocalCache.getInstance().getData(IS_MUSIC, Boolean::class.java) ?: true
}

fun saveSound(data: Boolean) {
    LocalCache.getInstance().put(IS_SOUND_FX, data)
}

fun isSound(): Boolean {
    return LocalCache.getInstance().getData(IS_SOUND_FX, Boolean::class.java) ?: true
}

fun saveVibrate(data: Boolean) {
    LocalCache.getInstance().put(IS_VIBRATE, data)
}

fun isVibrate(): Boolean {
    return LocalCache.getInstance().getData(IS_VIBRATE, Boolean::class.java) ?: true
}

fun saveRandomColor(data: Boolean) {
    LocalCache.getInstance().put(IS_RANDOM_COLOR, data)
}

fun isRandomColor(): Boolean {
    return LocalCache.getInstance().getData(IS_RANDOM_COLOR, Boolean::class.java) ?: true
}

inline fun <reified T> getLocalData(): List<T> {
    val json = LocalCache.getInstance().getData(T::class.java.name, String::class.java)
    if (json.isNullOrEmpty()) return emptyList()
    val list = gsonStrToList(json, T::class.java)
    return list
}

fun saveDrawCollection(drawResult: DrawResult) {
    if (drawResult.getPathSafe().isEmpty().not()) {
        val list = getLocalData<DrawResult>().toMutableList()
        val existingIndex = list.indexOfFirst { it.getPathSafe() == drawResult.getPathSafe() }
        if (existingIndex != -1) {
            list[existingIndex] = drawResult
        } else {
            list.add(drawResult)
        }
        val json = gsonObjToStr(list)
        LocalCache.getInstance().put(DrawResult::class.java.name, json)
    }
}

fun deleteDrawCollection(drawResult: DrawResult): Boolean {
    var deleteFlag = false
    if (drawResult.getPathSafe().isEmpty().not()) {
        val list = getLocalData<DrawResult>().toMutableList()
        val existingIndex = list.indexOfFirst { it.getPathSafe() == drawResult.getPathSafe() || it.id == drawResult.id }
        if (existingIndex != -1) {
            list.removeAt(existingIndex)
            val json = gsonObjToStr(list)
            LocalCache.getInstance().put(DrawResult::class.java.name, json)
            deleteFlag = true
        }
    }
    return deleteFlag
}





