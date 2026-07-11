package hoang.dqm.codebase.utils

import android.content.res.AssetManager

fun AssetManager.readAssetsFile(fileName: String): String {
    return try {
        open(fileName).bufferedReader().use { it.readText() }
    } catch (ex: Exception) {
        ex.printStackTrace()
        ""
    }
}