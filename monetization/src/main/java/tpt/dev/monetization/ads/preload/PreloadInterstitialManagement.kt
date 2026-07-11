package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.ads.PrepareLoadingAdsDialog
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager
import kotlin.random.Random

/**
 * Preload Interstitial Management
 * Quản lý việc preload và hiển thị interstitial ads với cơ chế pool
 */
class PreloadInterstitialManagement(
    val context: Context,
    val adsManager: AdsManager,
    val premiumManager: IPremiumManager,
    val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {

    // Pool chứa các ads đã load
    private val adsPool: MutableMap<String, InterstitialAd> = HashMap()
    
    // Set chứa các key đang trong quá trình load
    private val loadingPools: MutableSet<String> = mutableSetOf()
    
    // Thời gian lần cuối show ads
    private var lastShowAdsTime: Long = 0
    
    // Key backup ads
    private var backupAdKey: String = ""
    
    // Flag đang show ads
    var isShowingAds = false
        private set
    
    // Dialog loading
    private var prepareLoadingAdsDialog: PrepareLoadingAdsDialog? = null

    companion object {
        private const val TAG = "PreloadInterstitial"
        private const val DEFAULT_DELAY_WATERFALL = 300L
    }

    /**
     * Load interstitial ad với ad key
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
        InterstitialAd.load(
            context,
            adKey,
            defaultAdRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    Log.d(TAG, "onAdLoaded: $adKey")
                    adsPool[adKey] = interstitialAd
                    interstitialAd.setImmersiveMode(true)
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
        onAdClosed: ((Boolean) -> Unit)? = null
    ) {
        Log.d(TAG, "show: $adKey")

        if (isShowingAds) {
            Log.d(TAG, "show: $adKey - already showing ads")
            onAdClosed?.invoke(false)
            return
        }

        if (!appAllowShowAd) {
            Log.d(TAG, "show: $adKey - app not allow show ad")
            onAdClosed?.invoke(false)
            return
        }

        val currentTime = System.currentTimeMillis()
        val interval = adsManager.getTimeIntervalShowInterstitialAd().inWholeMilliseconds

        if (currentTime - lastShowAdsTime < interval) {
            Log.d(TAG, "show: $adKey - interval not reached")
            onAdClosed?.invoke(false)
            return
        }

        val ad = adsPool[adKey]

        if (ad == null) {
            Log.d(TAG, "show: $adKey - ad not loaded, try backup")
            showBackupIfAvailable(activity, onAdShowed, onAdClosed)
            return
        }

        val appCompatActivity = activity as? AppCompatActivity

        val isProcessResumed =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        val isActivityResumed =
            appCompatActivity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true

        if (!isProcessResumed || !isActivityResumed) {
            Log.d(TAG, "show: $adKey - lifecycle not resumed")
            onAdClosed?.invoke(false)
            return
        }

        showLoadingDialog(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "onAdShowedFullScreenContent: $adKey")

                isShowingAds = true
                adsPool.remove(adKey)

                dismissLoadingDialog()

                adsManager.updateIsShowingFullScreenAd(true)

                onAdShowed?.invoke()
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "onAdDismissedFullScreenContent: $adKey")

                isShowingAds = false
                adsManager.updateIsShowingFullScreenAd(false)

                lastShowAdsTime = System.currentTimeMillis()
                adsManager.updateLastTimeShowInterstitialAd(lastShowAdsTime)

                dismissLoadingDialog()

                if (reload) {
                    load(activity, adKey)
                }

                // Quan trọng:
                // Không gọi onAdClosed ngay tại đây.
                // Đợi Activity/Process RESUMED lại rồi mới callback.
                invokeOnAdClosedWhenResumed(
                    activity = activity,
                    isShowed = true,
                    onAdClosed = onAdClosed
                )
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "onAdFailedToShowFullScreenContent: $adKey - ${adError.message}")

                isShowingAds = false
                adsPool.remove(adKey)

                dismissLoadingDialog()

                adsManager.updateIsShowingFullScreenAd(false)

                if (reload) {
                    load(activity, adKey)
                }

                Handler(Looper.getMainLooper()).post {
                    onAdClosed?.invoke(false)
                }
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val latestIsProcessResumed =
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

            val latestIsActivityResumed =
                appCompatActivity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true

            if (
                !activity.isDestroyed &&
                !activity.isFinishing &&
                latestIsProcessResumed &&
                latestIsActivityResumed
            ) {
                adsManager.updateIsShowingFullScreenAd(true)
                ad.show(activity)
            } else {
                dismissLoadingDialog()
                adsManager.updateIsShowingFullScreenAd(false)
                onAdClosed?.invoke(false)
            }
        }, 500)
    }

    private fun invokeOnAdClosedWhenResumed(
        activity: Activity,
        isShowed: Boolean,
        onAdClosed: ((Boolean) -> Unit)?,
        retryCount: Int = 0
    ) {
        val appCompatActivity = activity as? AppCompatActivity

        val isProcessResumed =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        val isActivityResumed =
            appCompatActivity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true

        if (
            !activity.isDestroyed &&
            !activity.isFinishing &&
            isProcessResumed &&
            isActivityResumed
        ) {
            Handler(Looper.getMainLooper()).post {
                try {
                    Log.d(TAG, "invoke onAdClosed safely, isShowed=$isShowed")
                    onAdClosed?.invoke(isShowed)
                } catch (e: Exception) {
                    Log.e(TAG, "onAdClosed callback error: ${e.message}", e)
                }
            }
            return
        }

        if (retryCount >= 10) {
            Handler(Looper.getMainLooper()).post {
                try {
                    Log.d(TAG, "invoke onAdClosed fallback, isShowed=$isShowed")
                    onAdClosed?.invoke(isShowed)
                } catch (e: Exception) {
                    Log.e(TAG, "onAdClosed fallback error: ${e.message}", e)
                }
            }
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            invokeOnAdClosedWhenResumed(
                activity = activity,
                isShowed = isShowed,
                onAdClosed = onAdClosed,
                retryCount = retryCount + 1
            )
        }, 150)
    }

    /**
     * Show backup ad nếu có
     */
    private fun showBackupIfAvailable(
        activity: Activity,
        onAdShowed: (() -> Unit)?,
        onAdClosed: ((Boolean) -> Unit)?
    ) {
        if (backupAdKey.isEmpty() || !adsPool.containsKey(backupAdKey)) {
            Log.d(TAG, "showBackupIfAvailable: no backup available")
            onAdClosed?.invoke(false)
            return
        }

        Log.d(TAG, "showBackupIfAvailable: showing backup $backupAdKey")
        show(activity, backupAdKey, true, onAdShowed, onAdClosed)
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
     * Reset thời gian show ads
     */
    fun resetAdShowedTime() {
        lastShowAdsTime = 0
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
