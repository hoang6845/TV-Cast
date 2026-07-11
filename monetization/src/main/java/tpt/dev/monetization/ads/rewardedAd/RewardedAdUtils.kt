package tpt.dev.monetization.ads.rewardedAd

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.ads.PrepareLoadingAdsDialog
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

class RewardedAdUtils internal constructor(
    private val rewardedAdId: String,
    private val adsManager: AdsManager,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {
    private var prepareLoadingAdsDialog: PrepareLoadingAdsDialog? = null

    fun showRewardedAd(
        activity: AppCompatActivity,
        onAdShowed: () -> Unit,
        onAdDismissed: () -> Unit,
        onLoadFailed: () -> Unit,
        onRewarded: (RewardItem) -> Unit
    ) {
        if (ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        ) {
            adsManager.updateIsShowingFullScreenAd(true)

            try {
                if (prepareLoadingAdsDialog != null && prepareLoadingAdsDialog!!.isShowing) prepareLoadingAdsDialog!!.dismiss()
                prepareLoadingAdsDialog = PrepareLoadingAdsDialog(activity)
                prepareLoadingAdsDialog?.show()
            } catch (e: Exception) {
                prepareLoadingAdsDialog = null
                e.printStackTrace()
            }

            loadRewardedAd(
                context = activity,
                onAdLoaded = { rewardedAd ->
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            Handler(Looper.getMainLooper()).postDelayed(
                                {
                                    if (prepareLoadingAdsDialog != null && prepareLoadingAdsDialog!!.isShowing && !activity.isDestroyed) prepareLoadingAdsDialog!!.dismiss()
                                },
                                1000
                            )

                            rewardedAd.fullScreenContentCallback =
                                object : FullScreenContentCallback() {
                                    override fun onAdDismissedFullScreenContent() {
                                        adsManager.updateIsShowingFullScreenAd(false)
                                        onAdDismissed()
                                    }

                                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                        adsManager.updateIsShowingFullScreenAd(false)
                                        onLoadFailed()
                                    }

                                    override fun onAdShowedFullScreenContent() {
                                        onAdShowed()
                                    }

                                    override fun onAdClicked() {
                                        super.onAdClicked()
                                    }

                                    override fun onAdImpression() {
                                        super.onAdImpression()
                                    }
                                }
                            rewardedAd.show(activity) {
                                onRewarded(it)
                            }
                        } else {
                            if (prepareLoadingAdsDialog != null && prepareLoadingAdsDialog!!.isShowing && !activity.isDestroyed) prepareLoadingAdsDialog!!.dismiss()
                            adsManager.updateIsShowingFullScreenAd(false)
                            onLoadFailed()
                        }
                    }, 500)
                },
                onLoadFailed = {
                    if (prepareLoadingAdsDialog != null && prepareLoadingAdsDialog!!.isShowing && !activity.isDestroyed) prepareLoadingAdsDialog!!.dismiss()
                    adsManager.updateIsShowingFullScreenAd(false)
                    onLoadFailed()
                }
            )
        } else {
            onLoadFailed()
        }
    }

    private fun loadRewardedAd(
        context: Context,
        onAdLoaded: (RewardedAd) -> Unit,
        onLoadFailed: (() -> Unit)?
    ) {
        if (!googleMobileAdsConsentManager.canRequestAds) {
            onLoadFailed?.invoke()
            return
        }

        RewardedAd.load(
            context,
            rewardedAdId,
            defaultAdRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    onLoadFailed?.invoke()
                }

                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    onAdLoaded(rewardedAd)
                }
            }
        )
    }
}
