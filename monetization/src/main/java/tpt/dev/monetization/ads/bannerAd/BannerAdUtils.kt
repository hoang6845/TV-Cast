package tpt.dev.monetization.ads.bannerAd

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import java.util.UUID
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

class BannerAdUtils internal constructor(
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {
    fun loadAdaptiveBanner(
        adViewContainer: FrameLayout,
        activity: Activity,
        adId: String,
        onAdLoaded: (() -> Unit)? = null,
        onAdFailedToLoad: (() -> Unit)? = null
    ) {
        val adView = AdView(activity)
        adView.adUnitId = adId
        adViewContainer.removeAllViews()
        adViewContainer.addView(adView)
        val adSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getAdSizeAndroid11(adViewContainer, activity)
        } else {
            getAdSize(adViewContainer, activity)
        }
        adView.setAdSize(adSize)

        val adRequest = AdRequest.Builder().build()
        loadBannerAdView(adView, adRequest, onAdLoaded, onAdFailedToLoad)
    }

    fun loadCollapsibleBanner(
        adViewContainer: FrameLayout,
        adPlacement: BannerPlacement,
        requestId: String?,
        activity: Activity,
        adId: String,
        onAdLoaded: (() -> Unit)? = null,
        onAdFailedToLoad: (() -> Unit)? = null
    ): String {
        val adView = AdView(activity)
        adView.adUnitId = adId
        adViewContainer.removeAllViews()
        adViewContainer.addView(adView)
        val adSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getAdSizeAndroid11(adViewContainer, activity)
        } else {
            getAdSize(adViewContainer, activity)
        }
        adView.setAdSize(adSize)

        val nonNullRequestId = requestId ?: UUID.randomUUID().toString()

        val extras = Bundle()
        extras.putString("collapsible", adPlacement.value)
        extras.putString("collapsible_request_id", nonNullRequestId)

        val adRequest = AdRequest
            .Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()

        loadBannerAdView(adView, adRequest, onAdLoaded, onAdFailedToLoad)

        return nonNullRequestId
    }

    fun loadBannerWithSize(
        adViewContainer: FrameLayout,
        adSize: AdSize,
        context: Context,
        adId: String,
        onAdLoaded: (() -> Unit)? = null,
        onAdFailedToLoad: (() -> Unit)? = null
    ) {
        val adView = AdView(context)
        adView.adUnitId = adId
        adViewContainer.removeAllViews()
        adViewContainer.addView(adView)
        adView.setAdSize(adSize)

        loadBannerAdView(adView, defaultAdRequest, onAdLoaded, onAdFailedToLoad)
    }

    private fun loadBannerAdView(
        adView: AdView,
        adRequest: AdRequest,
        onAdLoaded: (() -> Unit)? = null,
        onAdFailedToLoad: (() -> Unit)? = null
    ) {
        if (!googleMobileAdsConsentManager.canRequestAds) {
            onAdFailedToLoad?.invoke()
            return
        }

        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                super.onAdLoaded()
                onAdLoaded?.invoke()
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                super.onAdFailedToLoad(adError)
                onAdFailedToLoad?.invoke()
            }

            override fun onAdClicked() {
                super.onAdClicked()
            }

            override fun onAdImpression() {
                super.onAdImpression()
            }
        }
        adView.loadAd(adRequest)
    }

    /**
     * Get adaptive banner AdSize for Android 11 and above
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun getAdSizeAndroid11(adViewContainer: FrameLayout, activity: Activity): AdSize {
        val windowMetrics = activity.windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds

        var adWidthPixels = adViewContainer.width.toFloat()

        if (adWidthPixels == 0f) {
            adWidthPixels = bounds.width().toFloat()
        }

        val density = activity.resources.displayMetrics.density
        val adWidth = (adWidthPixels / density).toInt()

        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    /**
     * Get adaptive banner AdSize for Android 10 and below
     */
    @Suppress("DEPRECATION")
    private fun getAdSize(adViewContainer: FrameLayout, activity: Activity): AdSize {
        val display = activity.windowManager.defaultDisplay
        val outMetrics = DisplayMetrics()
        display.getMetrics(outMetrics)
        val density = outMetrics.density
        var adWidthPixels = adViewContainer.width.toFloat()

        if (adWidthPixels == 0f) {
            adWidthPixels = outMetrics.widthPixels.toFloat()
        }

        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }
}
