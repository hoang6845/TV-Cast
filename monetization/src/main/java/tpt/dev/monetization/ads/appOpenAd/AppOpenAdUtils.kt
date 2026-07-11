package tpt.dev.monetization.ads.appOpenAd

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

class AppOpenAdUtils internal constructor(
    private val isAllowShowOpenAd: Boolean,
    private val appOpenAdId: String,
    private val application: Application,
    private val adsManager: AdsManager,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager,
) : BaseAdUtils(premiumManager),
    DefaultLifecycleObserver {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var countLoadAppOpenAds = 0
    private var isPendingShowAd = false
    private var pendingShowActivity: AppCompatActivity? = null
    private var pendingShowAdCompleteListener: OnShowAdCompleteListener? = null
    internal var isShowingAd = false

    /**
     * Load ad only when we are going to show it (no preloading).
     */
    internal fun loadAd(forceLoad: Boolean = false) {
        if (!isAllowShowOpenAd || !appAllowShowAd || !googleMobileAdsConsentManager.canRequestAds) {
            return
        }

        if (forceLoad) {
            appOpenAd = null
        }

        if (isLoadingAd || isAdAvailable()) {
            return
        }

        ++countLoadAppOpenAds
        isLoadingAd = true

        AppOpenAd.load(
            application,
            appOpenAdId,
            defaultAdRequest,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    super.onAdLoaded(ad)
                    appOpenAd = ad
                    isLoadingAd = false
                    countLoadAppOpenAds = 0
                    showPendingAdIfAvailable()

                    // Drop loaded ad if request is no longer pending to avoid preloading.
                    if (!isPendingShowAd && !isShowingAd) {
                        appOpenAd = null
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    isLoadingAd = false
                    if (countLoadAppOpenAds < MAX_RETRY_LOAD_AD) {
                        loadAd(forceLoad = false)
                    } else {
                        countLoadAppOpenAds = 0
                        completePendingShowAd()
                    }
                }
            }
        )
    }

    /** Check if ad exists and can be shown. */
    fun isAdAvailable(): Boolean = appOpenAd != null

    /**
     * For each show request, load then show that loaded ad immediately.
     */
    fun showAdIfAvailable(
        activity: AppCompatActivity,
        onShowAdCompleteListener: OnShowAdCompleteListener
    ) {
        Log.d("AppLifecycle", "showAdIfAvailable: start")
        Log.d("AppLifecycle", "isShowingAd = $isShowingAd")
        Log.d("AppLifecycle", "isPendingShowAd = $isPendingShowAd")
        Log.d("AppLifecycle", "canShowOpenAdNow = ${canShowOpenAdNow()}")

        if (isShowingAd) {
            Log.d("AppLifecycle", "showAdIfAvailable: skipped → already showing ad")
            onShowAdCompleteListener.onShowAdComplete()
            return
        }

        if (isPendingShowAd) {
            Log.d("AppLifecycle", "showAdIfAvailable: skipped → already pending show")
            return
        }

        if (!canShowOpenAdNow()) {
            Log.d("AppLifecycle", "showAdIfAvailable: skipped → canShowOpenAdNow = false")
            onShowAdCompleteListener.onShowAdComplete()
            return
        }

        Log.d("AppLifecycle", "showAdIfAvailable: proceed → set pending state")

        isPendingShowAd = true
        pendingShowActivity = activity
        pendingShowAdCompleteListener = onShowAdCompleteListener

        Log.d("AppLifecycle", "showAdIfAvailable: calling loadAd(forceLoad=true)")
        loadAd(forceLoad = true)
    }

    private fun canShowOpenAdNow(): Boolean {
        if (!isAllowShowOpenAd) {
            return false
        }
        if (!appAllowShowAd) {
            return false
        }
        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d("tpt", "can req ads")
            return false
        }
        if (adsManager.checkIsShowingFullScreenAd()) {
            return false
        }
        if ((System.currentTimeMillis() - adsManager.getLastTimeShowInterstitialAd() < adsManager.getTimeIntervalShowInterVsOpen().inWholeMilliseconds) ||
            (System.currentTimeMillis() - adsManager.getLastTimeShowOpenAd() < adsManager.getTimeIntervalShowInterVsOpen().inWholeMilliseconds)
        ) {
            return false
        }
        return true
    }

    private fun showAdInternal(
        activity: AppCompatActivity,
        onShowAdCompleteListener: OnShowAdCompleteListener
    ) {
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                adsManager.updateIsShowingFullScreenAd(false)
                adsManager.updateLastTimeShowOpenAd(System.currentTimeMillis())
                onShowAdCompleteListener.onShowAdComplete()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                isShowingAd = false
                adsManager.updateIsShowingFullScreenAd(false)
                onShowAdCompleteListener.onShowAdComplete()
            }

            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
                clearPendingShowAd()
                adsManager.updateIsShowingFullScreenAd(true)
            }
        }

        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            appOpenAd?.show(activity) ?: onShowAdCompleteListener.onShowAdComplete()
        } else {
            onShowAdCompleteListener.onShowAdComplete()
        }
    }

    private fun showPendingAdIfAvailable() {
        if (!isPendingShowAd) {
            return
        }

        val activity = pendingShowActivity
        val listener = pendingShowAdCompleteListener

        if (activity == null || listener == null) {
            clearPendingShowAd()
            return
        }

        if (activity.isDestroyed || activity.isFinishing) {
            completePendingShowAd()
            return
        }

        if (!canShowOpenAdNow()) {
            completePendingShowAd()
            return
        }

        if (!isAdAvailable()) {
            return
        }

        showAdInternal(activity, listener)
    }

    private fun completePendingShowAd() {
        val listener = pendingShowAdCompleteListener
        clearPendingShowAd()
        listener?.onShowAdComplete()
    }

    fun setPendingShowAd(isPending: Boolean) {
        isPendingShowAd = isPending
        if (!isPending) {
            pendingShowActivity = null
            pendingShowAdCompleteListener = null
            if (!isShowingAd) {
                appOpenAd = null
            }
        }
    }

    private fun clearPendingShowAd() {
        setPendingShowAd(false)
    }

    companion object {
        private const val MAX_RETRY_LOAD_AD = 3
    }
}
