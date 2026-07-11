package tpt.dev.monetization.ads.nativeAd

import android.app.Activity
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager


class SingleNativeAdUtils internal constructor(
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {
    fun loadAd(
        activity: Activity,
        adId: String,
        numberOfAdsToLoad: Int = 1,
        onLoadFailed: (stringFail: String?) -> Unit,
        onAdLoaded: (NativeAd) -> Unit
    ) {
        if (!appAllowShowAd) {
            onLoadFailed("app not allow show ad")
            return
        }
        if (!googleMobileAdsConsentManager.canRequestAds) {
            onLoadFailed("can not request ads")
            return
        }

        val videoOptions = VideoOptions
            .Builder()
            .setStartMuted(true)
            .build()

        val nativeAdOptions = NativeAdOptions
            .Builder()
            .setVideoOptions(videoOptions)
            .build()

        val adListener = object : AdListener() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                super.onAdFailedToLoad(adError)
                onLoadFailed(adError.toString())
            }

            override fun onAdClicked() {
                super.onAdClicked()

            }

            override fun onAdImpression() {
                super.onAdImpression()
            }
        }

        val adLoader = AdLoader
            .Builder(activity, adId)
            .forNativeAd { ad ->

                // If this callback occurs after the activity is destroyed, you must call
                // destroy and return or you may get a memory leak.
                if (activity.isDestroyed || activity.isFinishing || activity.isChangingConfigurations) {
                    ad.destroy()
                    return@forNativeAd
                }

                onAdLoaded(ad)
            }.withAdListener(adListener)
            .withNativeAdOptions(nativeAdOptions)
            .build()

        if (numberOfAdsToLoad > 1) {
            adLoader.loadAds(defaultAdRequest, numberOfAdsToLoad)
        } else {
            adLoader.loadAd(defaultAdRequest)
        }
    }
}
