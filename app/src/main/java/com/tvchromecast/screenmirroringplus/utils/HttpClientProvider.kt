package com.tvchromecast.screenmirroringplus.utils

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

object HttpClientProvider {

    fun provide(context: Context): OkHttpClient {
        val cacheSize = 20L * 1024 * 1024

        val cache = Cache(
            File(context.cacheDir, "http_cache"),
            cacheSize
        )

        return OkHttpClient.Builder()
            .cache(cache)
            .build()
    }
}