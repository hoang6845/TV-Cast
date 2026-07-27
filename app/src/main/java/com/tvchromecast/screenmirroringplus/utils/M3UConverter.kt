package com.tvchromecast.screenmirroringplus.utils

import com.tvchromecast.screenmirroringplus.model.entity.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class ChannelGroupSource {
    CATEGORY,
    LANGUAGE,
    COUNTRY
}

suspend fun fetchM3U(
    client: OkHttpClient,
    url: String
): String = withContext(Dispatchers.IO) {

    val request = Request.Builder()
        .url(url)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }
        response.body?.string().orEmpty()
    }
}

fun parseM3U(m3u: String, source: ChannelGroupSource): List<Channel> {
    val lines = m3u.lines()
    val channels = mutableListOf<Channel>()

    var currentExtinf: String? = null

    for (line in lines) {
        val l = line.trim()
        if (l.isEmpty()) continue

        if (l.startsWith("#EXTINF")) {
            currentExtinf = l
        } else if (!l.startsWith("#") && currentExtinf != null) {
            val channel = parseExtinf(currentExtinf, l, source)
            channels.add(channel)
            currentExtinf = null
        }
    }

    return channels
}


private fun parseExtinf(extinf: String, streamUrl: String, source: ChannelGroupSource): Channel {
    fun find(attr: String): String? {
        val regex = Regex("""$attr="([^"]+)"""")
        return regex.find(extinf)?.groupValues?.get(1)
    }

    val name = extinf.substringAfter(",").trim()
    val url = streamUrl.trim()
    val groupTitle = find("group-title")

    return Channel(
        id = find("tvg-id")?.takeIf { it.isNotBlank() } ?: url,
        name = name,
        logo = find("tvg-logo"),
        categories = groupTitle.takeIf { source == ChannelGroupSource.CATEGORY },
        languages = groupTitle.takeIf { source == ChannelGroupSource.LANGUAGE },
        countries = groupTitle.takeIf { source == ChannelGroupSource.COUNTRY },
        url = url
    )
}


