package hoang.dqm.codebase.firebase

import android.util.Log
import com.bumptech.glide.load.model.LazyHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.net.URL

enum class DataResourceType(val domainUrl: String) {
    GITHUB(SeverRemoteConfig.DATA_BASE_URL_GITHUB),
    DATA_OPTIMIZE_URL_BACKUP(SeverRemoteConfig.DATA_OPTIMIZE_URL_BACKUP);
}

object SeverRemoteConfig {

    const val DATA_BASE_URL_GITHUB =  "https://raw.githubusercontent.com/hoang6845/iptv_data/main/"
    const val DATA_OPTIMIZE_URL_BACKUP = ""

    const val URL_README_TEST = DATA_BASE_URL_GITHUB + "README.md"
    private var isGithubAvailable = true

    fun getUrlBase(): String {
        return if (isGithubAvailable) DATA_BASE_URL_GITHUB else DATA_OPTIMIZE_URL_BACKUP
    }

    fun getHeaderToken(): LazyHeaders {
        return LazyHeaders.Builder()
            .addHeader("Accept", "application/vnd.github.v3.raw")
            .addHeader("Authorization", "token ${AppRemoteConfig.getAccessToken()}")
            .build()
    }

    fun isGithubUrl(): Boolean {
        return getUrlBase().contains(DATA_BASE_URL_GITHUB)
    }

    fun setGithubAvailable(isAvailable: Boolean) {
        isGithubAvailable = isAvailable
    }

    suspend fun testReadmeToken(token: String?): String? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        fun fetchUrl(): String? {

            Log.d("GithubTest", "URL: $URL_README_TEST")
            Log.d("GithubTest", "Token: ${token?: AppRemoteConfig.getAccessToken()}")

            val request = Request.Builder()
                .url(URL_README_TEST)
                .addHeader("Accept", "application/vnd.github.v3.raw")
                .addHeader("Authorization", "token ${token?: AppRemoteConfig.getAccessToken()}")
                .build()

            return try {
                val response = client.newCall(request).execute()

                Log.d("GithubTest", "Response code: ${response.code}")
                Log.d("GithubTest", "Response message: ${response.message}")

                setGithubAvailable(response.isSuccessful)

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d("GithubTest", "Response body: $body")
                    body
                } else {
                    Log.e("GithubTest", "GitHub request failed: ${response.code}")
                    null
                }

            } catch (e: IOException) {
                Log.e("GithubTest", "Network error: ${e.message}")
                null
            }
        }

        val result = fetchUrl()

        Log.d("GithubTest", "Result: $result")

        return@withContext result
    }

    fun loadGameDataFromRemote(url: String, onComplete: (String?) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val json = withContext(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(DATA_BASE_URL_GITHUB + url)
                    .addHeader("Accept", "application/vnd.github.v3.raw")
                    .addHeader("Authorization", "token ${AppRemoteConfig.getAccessToken()}")
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.string()
                        } else {
                            Log.e("DEBUG", "Failed to load JSON, code: ${response.code}")
                            null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            onComplete(json)
        }
    }
}
