package tpt.dev.monetization.ads.interstitlaAd

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.ads.PrepareLoadingAdsDialog
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

class InterstitialAdUtils internal constructor(
    private val interstitialAdId: String,
    private val context: Context,
    private val adsManager: AdsManager,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager,
    private val type: InterstitialType,
) : BaseAdUtils(premiumManager) {
    private var mInterstitialAd: InterstitialAd? = null
    private var mAdIsLoading: Boolean = false
    private var isStartShowingAd = false
    private var isLoadFailed: Boolean = false
    private var prepareLoadingAdsDialog: PrepareLoadingAdsDialog? = null

    fun loadAd() {
        if (!appAllowShowAd) return
        if (!googleMobileAdsConsentManager.canRequestAds) return

        isLoadFailed = false

        InterstitialAd.load(
            context,
            interstitialAdId,
            defaultAdRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    mInterstitialAd = null
                    mAdIsLoading = false
                    isLoadFailed = true
                }

                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                    mInterstitialAd?.setImmersiveMode(true)
                    mAdIsLoading = true
                }
            }
        )
    }

    fun isAdAvailable(): Boolean = mInterstitialAd != null
    fun isLoadFail(): Boolean = isLoadFailed

    // ─── Hide status bar + handle punch-hole / notch cutout ───────────────────

    private fun Activity.hideAdStatusBar() {
        // Cho phép content render vào vùng display cutout (punch-hole, notch, v.v.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Cho layout phủ lên system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Ẩn status bar + navigation bar, swipe để hiện tạm thời
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun Activity.restoreAdStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(
            WindowInsetsCompat.Type.systemBars()
        )
    }

    fun showAdOpenAds(
        activity: Activity,
        onAdsShowed: () -> Unit,
        onAdsClosed: () -> Unit
    ) {
        if (isStartShowingAd) return

        if (!appAllowShowAd) {
            onAdsClosed()
            return
        }

        if (mInterstitialAd != null) {
            if (System.currentTimeMillis() - adsManager.getLastTimeShowInterstitialAd()
                < adsManager.getTimeIntervalShowInterstitialAd().inWholeMilliseconds
            ) {
                onAdsClosed.invoke()
                return
            }

            if (adsManager.checkIsShowingFullScreenAd()) {
                onAdsClosed.invoke()
                return
            }

            mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mInterstitialAd = null
                    isStartShowingAd = false
                    adsManager.updateIsShowingFullScreenAd(false)
                    adsManager.updateLastTimeShowInterstitialAd(System.currentTimeMillis())
                    onAdsClosed()
                    loadAd()
                    activity.restoreAdStatusBar()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    mInterstitialAd = null
                    isStartShowingAd = false
                    adsManager.updateIsShowingFullScreenAd(false)
                    onAdsClosed()
                    loadAd()
                    activity.restoreAdStatusBar()
                }

                override fun onAdShowedFullScreenContent() {
                    isStartShowingAd = false
                    onAdsShowed.invoke()
                }

                override fun onAdClicked() = Unit
                override fun onAdImpression() = Unit
            }

            adsManager.updateIsShowingFullScreenAd(true)

            try {
                if (prepareLoadingAdsDialog?.isShowing == true) prepareLoadingAdsDialog!!.dismiss()
                prepareLoadingAdsDialog = PrepareLoadingAdsDialog(activity)
                prepareLoadingAdsDialog?.show()
            } catch (e: Exception) {
                prepareLoadingAdsDialog = null
                e.printStackTrace()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            if (prepareLoadingAdsDialog?.isShowing == true && !activity.isDestroyed)
                                prepareLoadingAdsDialog!!.dismiss()
                        },
                        1000
                    )
                    isStartShowingAd = true
                    if (mInterstitialAd != null) {
                        // ✅ Ẩn status bar TRƯỚC khi show ad — tránh bị hở phía trên
                        activity.hideAdStatusBar()
                        mInterstitialAd!!.show(activity)
                    } else {
                        adsManager.updateIsShowingFullScreenAd(false)
                        onAdsClosed()
                    }
                } else {
                    if (prepareLoadingAdsDialog?.isShowing == true && !activity.isDestroyed)
                        prepareLoadingAdsDialog!!.dismiss()
                    adsManager.updateIsShowingFullScreenAd(false)
                    onAdsClosed()
                }
            }, 1000)
        } else {
            loadAd()
            onAdsClosed()
        }
    }

    // ─── showAd ───────────────────────────────────────────────────────────────

    fun showAd(
        activity: AppCompatActivity,
        onAdsShowed: (() -> Unit)? = null,
        onAdsClosed: (Boolean) -> Unit
    ) {
        if (isStartShowingAd) return

        if (!appAllowShowAd) {
            onAdsClosed(false)
            return
        }

        if (mInterstitialAd != null) {
            if (System.currentTimeMillis() - adsManager.getLastTimeShowInterstitialAd()
                < adsManager.getTimeIntervalShowInterstitialAd().inWholeMilliseconds
            ) {
                onAdsClosed.invoke(false)
                return
            }

            mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mInterstitialAd = null
                    isStartShowingAd = false
                    adsManager.updateIsShowingFullScreenAd(false)
                    adsManager.updateLastTimeShowInterstitialAd(System.currentTimeMillis())
                    if (type != InterstitialType.OPEN) loadAd()
                    onAdsClosed(true)
                    activity.restoreAdStatusBar()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    mInterstitialAd = null
                    isStartShowingAd = false
                    adsManager.updateIsShowingFullScreenAd(false)
                    if (type != InterstitialType.OPEN) loadAd()
                    onAdsClosed(false)
                    activity.restoreAdStatusBar()
                }

                override fun onAdShowedFullScreenContent() {
                    isStartShowingAd = false
                    onAdsShowed?.invoke()
                    // ✅ KHÔNG gọi hideStatusBar ở đây nữa — đã gọi trước show()
                }

                override fun onAdClicked() = Unit
                override fun onAdImpression() = Unit
            }

            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                adsManager.updateIsShowingFullScreenAd(true)

                try {
                    if (prepareLoadingAdsDialog?.isShowing == true) prepareLoadingAdsDialog!!.dismiss()
                    prepareLoadingAdsDialog = PrepareLoadingAdsDialog(activity)
                    prepareLoadingAdsDialog?.show()
                } catch (e: Exception) {
                    prepareLoadingAdsDialog = null
                    e.printStackTrace()
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Handler(Looper.getMainLooper()).postDelayed(
                            {
                                if (prepareLoadingAdsDialog?.isShowing == true && !activity.isDestroyed)
                                    prepareLoadingAdsDialog!!.dismiss()
                            },
                            1000
                        )
                        isStartShowingAd = true
                        if (mInterstitialAd != null) {
                            // ✅ Ẩn status bar TRƯỚC khi show ad — tránh bị hở phía trên
                            activity.hideAdStatusBar()
                            mInterstitialAd!!.show(activity)
                        } else {
                            adsManager.updateIsShowingFullScreenAd(false)
                            onAdsClosed(false)
                        }
                    } else {
                        if (prepareLoadingAdsDialog?.isShowing == true && !activity.isDestroyed)
                            prepareLoadingAdsDialog!!.dismiss()
                        adsManager.updateIsShowingFullScreenAd(false)
                        onAdsClosed(false)
                    }
                }, 500)
            } else {
                onAdsClosed(false)
            }
        } else {
            loadAd()
            onAdsClosed(false)
        }
    }
}