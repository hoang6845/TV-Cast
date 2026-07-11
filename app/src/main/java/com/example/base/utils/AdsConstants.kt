package com.example.base.utils

import android.content.res.Resources
import com.example.base.MainApplication
import hoang.dqm.codebase.R
import tpt.dev.monetization.ads.constants.AbstractAdsConstants
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object AdsConstants : AbstractAdsConstants() {
    private val resources: Resources
        get() = MainApplication.instance.resources

    val DEFAULT_TIME_INTERVAL_LOAD_NATIVE_AD: Duration
        get() = 10.seconds


    override val ADMOB_INTERSTITIAL_ID = resources.getString(R.string.ads_interstitial_id)
    //splash
    override val ADMOB_SPLASH_ID: String =
        resources.getString(R.string.ads_inter_splash)
    override val ADMOB_APP_OPEN_ID: String = resources.getString(R.string.ads_open_id)
    override val REWARD_ID: String
        get() = resources.getString(R.string.reward)
}
