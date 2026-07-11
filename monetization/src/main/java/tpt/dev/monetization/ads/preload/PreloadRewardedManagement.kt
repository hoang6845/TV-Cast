package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.ads.PrepareLoadingAdsDialog
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager
import kotlin.random.Random

/**
 * Preload Rewarded Management
 * Quản lý việc preload và hiển thị rewarded ads với cơ chế pool
 */
class PreloadRewardedManagement(
    val context: Context,
    val adsManager: AdsManager,
    val premiumManager: IPremiumManager,
    val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {

    // Pool chứa các ads đã load
    private val adsPool: MutableMap<String, RewardedAd> = HashMap()
    
    // Set chứa các key đang trong quá trình load
    private val loadingPools: MutableSet<String> = mutableSetOf()
    
    // Key backup ads
    private var backupAdKey: String = ""
    
    // Flag đang show ads
    var isShowingAds = false
        private set
    
    // Dialog loading
    private var prepareLoadingAdsDialog: PrepareLoadingAdsDialog? = null

    companion object {
        private const val TAG = "PreloadRewarded"
        private const val DEFAULT_DELAY_WATERFALL = 300L
    }

    /**
     * Load rewarded ad với ad key
     */
    fun load(activity: Activity, adKey: String, callback: (() -> Unit)? = null) {
        Log.d(TAG, "load: $adKey")

        if (!appAllowShowAd) {
            Log.d(TAG, "load: $adKey - app not allow show ad")
            callback?.invoke()
            return
        }

        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d(TAG, "load: $adKey - cannot request ads")
            callback?.invoke()
            return
        }

        // Kiểm tra đã load rồi
        if (adsPool.containsKey(adKey)) {
            Log.d(TAG, "load: $adKey - already loaded")
            callback?.invoke()
            return
        }

        // Kiểm tra đang load
        if (loadingPools.contains(adKey)) {
            Log.d(TAG, "load: $adKey - already loading")
            callback?.invoke()
            return
        }

        // Thêm vào loading pool
        loadingPools.add(adKey)

        // Load ad
        RewardedAd.load(
            context,
            adKey,
            defaultAdRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    Log.d(TAG, "onAdLoaded: $adKey")
                    adsPool[adKey] = rewardedAd
                    rewardedAd.setImmersiveMode(true)
                    loadingPools.remove(adKey)
                    callback?.invoke()
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.e(TAG, "onAdFailedToLoad: $adKey - ${loadAdError.message}")
                    loadingPools.remove(adKey)
                    callback?.invoke()
                }
            }
        )
    }

    /**
     * Load nhiều ads với waterfall delay
     */
    fun loadWithWaterfall(
        activity: Activity,
        adKeys: List<String>,
        delayMs: Long = DEFAULT_DELAY_WATERFALL,
        callback: (() -> Unit)? = null
    ) {
        if (adKeys.isEmpty()) {
            callback?.invoke()
            return
        }

        val appCompatActivity = activity as? AppCompatActivity ?: return
        var loadedCount = 0
        val totalCount = adKeys.size

        adKeys.forEachIndexed { index, adKey ->
            appCompatActivity.lifecycleScope.launch {
                // Delay waterfall với random
                val randomDelay = delayMs + Random.nextInt(0, 500)
                delay(index * randomDelay)

                load(activity, adKey) {
                    loadedCount++
                    if (loadedCount == totalCount) {
                        callback?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Enable backup ad
     */
    fun enableBackup(activity: Activity, backupKey: String) {
        this.backupAdKey = backupKey
        load(activity, backupKey)
    }

    /**
     * Kiểm tra ad đã load chưa
     */
    fun isLoaded(adKey: String): Boolean {
        return adsPool.containsKey(adKey)
    }

    /**
     * Kiểm tra ad đang load
     */
    fun isLoading(adKey: String): Boolean {
        return loadingPools.contains(adKey)
    }

    /**
     * Show ad với loading dialog
     */
    fun show(
        activity: Activity,
        adKey: String,
        reload: Boolean = true,
        onAdShowed: (() -> Unit)? = null,
        onAdClosed: (() -> Unit)? = null,
        onRewarded: ((RewardItem) -> Unit)? = null,
        onLoadFailed: (() -> Unit)? = null
    ) {
        Log.d(TAG, "show: $adKey")

        if (isShowingAds) {
            Log.d(TAG, "show: $adKey - already showing ads")
            onLoadFailed?.invoke()
            return
        }

        if (!appAllowShowAd) {
            Log.d(TAG, "show: $adKey - app not allow show ad")
            onLoadFailed?.invoke()
            return
        }

        val ad = adsPool[adKey]
        if (ad == null) {
            Log.d(TAG, "show: $adKey - ad not loaded, try backup")
            showBackupIfAvailable(activity, onAdShowed, onAdClosed, onRewarded, onLoadFailed)
            return
        }

        // Show loading dialog
        showLoadingDialog(activity)

        // Setup callback
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "onAdShowedFullScreenContent: $adKey")
                isShowingAds = true
                adsPool.remove(adKey)
                dismissLoadingDialog()
                onAdShowed?.invoke()
                adsManager.updateIsShowingFullScreenAd(true)
                
                // Reload if needed
                if (reload) {
                    load(activity, adKey)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "onAdDismissedFullScreenContent: $adKey")
                isShowingAds = false
                adsManager.updateIsShowingFullScreenAd(false)
                dismissLoadingDialog()
                onAdClosed?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "onAdFailedToShowFullScreenContent: $adKey - ${adError.message}")
                isShowingAds = false
                adsPool.remove(adKey)
                dismissLoadingDialog()
                adsManager.updateIsShowingFullScreenAd(false)
                
                // Try backup
                showBackupIfAvailable(activity, onAdShowed, onAdClosed, onRewarded, onLoadFailed)
                
                // Reload
                if (reload) {
                    load(activity, adKey)
                }
            }
        }

        // Show ad with delay
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isDestroyed && !activity.isFinishing) {
                ad.show(activity) { rewardItem ->
                    Log.d(TAG, "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
                    onRewarded?.invoke(rewardItem)
                }
            } else {
                dismissLoadingDialog()
                onLoadFailed?.invoke()
            }
        }, 500)
    }

    /**
     * Show backup ad nếu có
     */
    private fun showBackupIfAvailable(
        activity: Activity,
        onAdShowed: (() -> Unit)?,
        onAdClosed: (() -> Unit)?,
        onRewarded: ((RewardItem) -> Unit)?,
        onLoadFailed: (() -> Unit)?
    ) {
        if (backupAdKey.isEmpty() || !adsPool.containsKey(backupAdKey)) {
            Log.d(TAG, "showBackupIfAvailable: no backup available")
            onLoadFailed?.invoke()
            return
        }

        Log.d(TAG, "showBackupIfAvailable: showing backup $backupAdKey")
        show(activity, backupAdKey, true, onAdShowed, onAdClosed, onRewarded, onLoadFailed)
    }

    /**
     * Show loading dialog
     */
    private fun showLoadingDialog(context: Context) {
        try {
            dismissLoadingDialog()
            prepareLoadingAdsDialog = PrepareLoadingAdsDialog(context)
            prepareLoadingAdsDialog?.show()
        } catch (e: Exception) {
            Log.e(TAG, "showLoadingDialog error: ${e.message}")
            prepareLoadingAdsDialog = null
        }
    }

    /**
     * Dismiss loading dialog
     */
    private fun dismissLoadingDialog() {
        try {
            if (prepareLoadingAdsDialog?.isShowing == true) {
                prepareLoadingAdsDialog?.dismiss()
            }
        } catch (e: Exception) {
            Log.e(TAG, "dismissLoadingDialog error: ${e.message}")
        } finally {
            prepareLoadingAdsDialog = null
        }
    }

    /**
     * Clear tất cả ads trong pool
     */
    fun clearAll() {
        adsPool.clear()
        loadingPools.clear()
    }

    /**
     * Remove ad khỏi pool
     */
    fun remove(adKey: String) {
        adsPool.remove(adKey)
        loadingPools.remove(adKey)
    }
}
