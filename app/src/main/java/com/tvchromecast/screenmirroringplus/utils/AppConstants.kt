package com.tvchromecast.screenmirroringplus.utils

object AppConstants {
    const val term = "https://sites.google.com/view/screen-mirroring--cast-tv/privacy-policy?fbclid=IwY2xjawTnpDRwZG9mBWV4dG4DYWVtAjEwAGJyaWQRMWZmSzY4S1QwS1dodzJSWXVzcnRjBmFwcF9pZBAyMjIwMzkxNzg4MjAwODkyAAEeBN1u7Hw0cJAgi5K_7hZ3oEt2ri7_jT65WswBPjSyzLFOUOY0ieSBqIi5CQE_aem_maRNIP2hTSH0p7_6PA1Y5w"
    const val policy = "https://sites.google.com/view/screen-mirroring--cast-tv/privacy-policy?fbclid=IwY2xjawTnpDRwZG9mBWV4dG4DYWVtAjEwAGJyaWQRMWZmSzY4S1QwS1dodzJSWXVzcnRjBmFwcF9pZBAyMjIwMzkxNzg4MjAwODkyAAEeBN1u7Hw0cJAgi5K_7hZ3oEt2ri7_jT65WswBPjSyzLFOUOY0ieSBqIi5CQE_aem_maRNIP2hTSH0p7_6PA1Y5w"
    const val LANGUAGE_URL = "https://iptv-org.github.io/iptv/index.language.m3u"
    const val CATEGORY_URL = "https://iptv-org.github.io/iptv/index.category.m3u"
    const val COUNTRY_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
    val CHANNEL_SOURCES = listOf(
        ChannelGroupSource.CATEGORY to CATEGORY_URL,
        ChannelGroupSource.LANGUAGE to LANGUAGE_URL,
        ChannelGroupSource.COUNTRY to COUNTRY_URL
    )
    const val TYPE_MIRROR = "mirror"
    /**
     * Select the screen mirroring implementation.
     *
     * Change [SCREEN_MIRRORING_OPTION] to [SCREEN_MIRRORING_OPTION_SYSTEM] to open
     * Android's own screen-mirroring controls instead of streaming through the custom
     * Cast/WebRTC receiver.
     */
    const val SCREEN_MIRRORING_OPTION_CAST = "cast"
    const val SCREEN_MIRRORING_OPTION_SYSTEM = "system"
    const val SCREEN_MIRRORING_OPTION = SCREEN_MIRRORING_OPTION_SYSTEM
    const val TYPE_CAST_MEDIA = "cast_media"
    const val TYPE_CAMERA_CAST = "camera_cast"
    const val TYPE_TRY_TV_REMOTE = "try_tv_remote"
    const val TYPE_CAST_YOUTUBE = "cast_youtube"
    const val TYPE_CAST_WEB = "cast_web"
    const val TYPE_MOVIE_ADVISOR = "movie_advisor"
    const val TYPE_IPTV = "iptv"
}
