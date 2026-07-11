package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager
import java.util.UUID
import kotlin.random.Random

/**
 * Preload Banner Management
 * Quản lý việc preload và hiển thị banner ads với cơ chế pool
 */
class PreloadBannerManagement(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {

    // Pool chứa các banner ads đã load
    private val adsPool: MutableMap<String, AdView> = HashMap()
    
    // Set chứa các key đang trong quá trình load
    private val loadingPools: MutableSet<String> = mutableSetOf()
    
    // Key backup ads
    private var backupAdKey: String = ""

    companion object {
        private const val TAG = "PreloadBanner"
        private const val DEFAULT_DELAY_WATERFALL = 300L
    }

    /**
     * Load adaptive banner ad
     */
    fun loadAdaptiveBanner(
        activity: Activity,
        adKey: String,
        callback: ((AdView?) -> Unit)? = null
    ) {
        Log.d(TAG, "loadAdaptiveBanner: $adKey")

        if (!appAllowShowAd) {
            Log.d(TAG, "loadAdaptiveBanner: $adKey - app not allow show ad")
            callback?.invoke(null)
            return
        }

        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d(TAG, "loadAdaptiveBanner: $adKey - cannot request ads")
            callback?.invoke(null)
            return
        }

        // Kiểm tra đã load rồi
        if (adsPool.containsKey(adKey)) {
            Log.d(TAG, "loadAdaptiveBanner: $adKey - already loaded")
            callback?.invoke(adsPool[adKey])
            return
        }

        // Kiểm tra đang load
        if (loadingPools.contains(adKey)) {
            Log.d(TAG, "loadAdaptiveBanner: $adKey - already loading")
            callback?.invoke(null)
            return
        }

        // Thêm vào loading pool
        loadingPools.add(adKey)

        // Create AdView
        val adView = AdView(activity)
        adView.adUnitId = adKey

        // Get adaptive size
        val adSize = getAdaptiveAdSize(activity)
        adView.setAdSize(adSize)

        // Set ad listener
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.d(TAG, "onAdLoaded: $adKey")
                adsPool[adKey] = adView
                loadingPools.remove(adKey)
                callback?.invoke(adView)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e(TAG, "onAdFailedToLoad: $adKey - ${adError.message}")
                loadingPools.remove(adKey)
                callback?.invoke(null)
            }

            override fun onAdClicked() {
                Log.d(TAG, "onAdClicked: $adKey")
            }

            override fun onAdImpression() {
                Log.d(TAG, "onAdImpression: $adKey")
            }
        }

        // Load ad
        adView.loadAd(defaultAdRequest)
    }

    /**
     * Load collapsible banner ad
     */
    fun loadCollapsibleBanner(
        activity: Activity,
        adKey: String,
        placement: BannerPlacement = BannerPlacement.BOTTOM,
        requestId: String? = null,
        callback: ((AdView?, String) -> Unit)? = null
    ) {
        Log.d(TAG, "loadCollapsibleBanner: $adKey")

        if (!appAllowShowAd) {
            Log.d(TAG, "loadCollapsibleBanner: $adKey - app not allow show ad")
            callback?.invoke(null, "")
            return
        }

        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d(TAG, "loadCollapsibleBanner: $adKey - cannot request ads")
            callback?.invoke(null, "")
            return
        }

        // Kiểm tra đã load rồi
        if (adsPool.containsKey(adKey)) {
            Log.d(TAG, "loadCollapsibleBanner: $adKey - already loaded")
            callback?.invoke(adsPool[adKey], requestId ?: "")
            return
        }

        // Kiểm tra đang load
        if (loadingPools.contains(adKey)) {
            Log.d(TAG, "loadCollapsibleBanner: $adKey - already loading")
            callback?.invoke(null, "")
            return
        }

        // Thêm vào loading pool
        loadingPools.add(adKey)

        // Create AdView
        val adView = AdView(activity)
        adView.adUnitId = adKey

        // Get adaptive size
        val adSize = getAdaptiveAdSize(activity)
        adView.setAdSize(adSize)

        // Generate request ID
        val nonNullRequestId = requestId ?: UUID.randomUUID().toString()

        // Build collapsible extras
        val extras = Bundle()
        extras.putString("collapsible", placement.value)
        extras.putString("collapsible_request_id", nonNullRequestId)

        val adRequest = AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()

        // Set ad listener
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.d(TAG, "onAdLoaded: $adKey (collapsible)")
                adsPool[adKey] = adView
                loadingPools.remove(adKey)
                callback?.invoke(adView, nonNullRequestId)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e(TAG, "onAdFailedToLoad: $adKey - ${adError.message}")
                loadingPools.remove(adKey)
                callback?.invoke(null, nonNullRequestId)
            }

            override fun onAdClicked() {
                Log.d(TAG, "onAdClicked: $adKey")
            }

            override fun onAdImpression() {
                Log.d(TAG, "onAdImpression: $adKey")
            }
        }

        // Load ad
        adView.loadAd(adRequest)
    }

    /**
     * Load nhiều banner ads với waterfall delay
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

                loadAdaptiveBanner(activity, adKey) {
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
        loadAdaptiveBanner(activity, backupKey)
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
     * Show banner ad vào container
     */
    fun showBanner(
        adKey: String,
        container: FrameLayout,
        removeAfterShow: Boolean = false
    ): Boolean {
        val adView = adsPool[adKey]
        if (adView == null) {
            Log.d(TAG, "showBanner: $adKey - ad not loaded")
            return false
        }

        // Remove from parent if exists
        (adView.parent as? FrameLayout)?.removeView(adView)

        // Add to container
        container.removeAllViews()
        container.addView(adView)

        if (removeAfterShow) {
            adsPool.remove(adKey)
        }

        Log.d(TAG, "showBanner: $adKey - shown successfully")
        return true
    }

    /**
     * Show banner hoặc backup
     */
    fun showBannerOrBackup(
        adKey: String,
        container: FrameLayout,
        removeAfterShow: Boolean = false
    ): Boolean {
        var success = showBanner(adKey, container, removeAfterShow)
        if (!success && backupAdKey.isNotEmpty()) {
            Log.d(TAG, "showBannerOrBackup: using backup $backupAdKey")
            success = showBanner(backupAdKey, container, removeAfterShow)
        }
        return success
    }

    /**
     * Get adaptive ad size
     */
    private fun getAdaptiveAdSize(activity: Activity): AdSize {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getAdSizeAndroid11(activity)
        } else {
            getAdSizeBeforeAndroid11(activity)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getAdSizeAndroid11(activity: Activity): AdSize {
        val windowMetrics = activity.windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val adWidthPixels = bounds.width().toFloat()
        val density = activity.resources.displayMetrics.density
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    @Suppress("DEPRECATION")
    private fun getAdSizeBeforeAndroid11(activity: Activity): AdSize {
        val display = activity.windowManager.defaultDisplay
        val outMetrics = DisplayMetrics()
        display.getMetrics(outMetrics)
        val adWidthPixels = outMetrics.widthPixels.toFloat()
        val density = outMetrics.density
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    /**
     * Destroy banner ad
     */
    fun destroy(adKey: String) {
        adsPool[adKey]?.destroy()
        adsPool.remove(adKey)
        loadingPools.remove(adKey)
    }

    /**
     * Clear tất cả ads trong pool
     */
    fun clearAll() {
        adsPool.values.forEach { it.destroy() }
        adsPool.clear()
        loadingPools.clear()
    }

    /**
     * Remove ad khỏi pool
     */
    fun remove(adKey: String) {
        adsPool[adKey]?.destroy()
        adsPool.remove(adKey)
        loadingPools.remove(adKey)
    }

    /**
     * Get số lượng ads đã load
     */
    fun getLoadedCount(): Int {
        return adsPool.size
    }

    /**
     * Get số lượng ads đang load
     */
    fun getLoadingCount(): Int {
        return loadingPools.size
    }
}

/**
 * Banner placement enum
 */
enum class BannerPlacement(val value: String) {
    TOP("top"),
    BOTTOM("bottom")
}
