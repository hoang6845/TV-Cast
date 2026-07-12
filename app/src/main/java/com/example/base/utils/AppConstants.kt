package com.example.base.utils

object AppConstants {
    const val term = "https://sites.google.com/view/iptv-player-m3u-xtream/terms-conditions"
    const val policy = "https://sites.google.com/view/iptv-player-m3u-xtream/privacy-policy"
    const val LANGUAGE_URL = "https://iptv-org.github.io/iptv/index.language.m3u"
    const val CATEGORY_URL = "https://iptv-org.github.io/iptv/index.category.m3u"
    const val COUNTRY_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
    val CHANNEL_SOURCES = listOf(
        ChannelGroupSource.CATEGORY to CATEGORY_URL,
        ChannelGroupSource.LANGUAGE to LANGUAGE_URL,
        ChannelGroupSource.COUNTRY to COUNTRY_URL
    )
    const val TYPE_MIRROR = "mirror"
    const val TYPE_CAST_MEDIA = "cast_media"
    const val TYPE_CAMERA_CAST = "camera_cast"
    const val TYPE_TRY_TV_REMOTE = "try_tv_remote"
    const val TYPE_CAST_YOUTUBE = "cast_youtube"
    const val TYPE_CAST_WEB = "cast_web"
    const val TYPE_MOVIE_ADVISOR = "movie_advisor"
    const val TYPE_IPTV = "iptv"
}
