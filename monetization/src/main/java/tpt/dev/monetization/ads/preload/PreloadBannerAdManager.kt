package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

/**
 * PreloadBannerAdManager - Quản lý preload Banner Ads
 * 
 * Chức năng:
 * - Preload Banner Ads theo placement key
 * - Cache AdView đã load thành công
 * - Hỗ trợ adaptive banner và collapsible banner
 * - Auto refresh với interval tùy chỉnh
 * - Thread-safe với coroutines
 */
class PreloadBannerAdManager(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) {
    private val adsCache = mutableMapOf<String, AdView>()
    private val loadingJobs = mutableMapOf<String, Job>()
    private val refreshJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Main)
    
    companion object {
        private const val TAG = "PreloadBannerAdManager"
        private const val DEFAULT_TIMEOUT = 10000L // 10 seconds
        private const val MAX_RETRY = 2
        private const val DEFAULT_REFRESH_INTERVAL = 60000L // 60 seconds
    }

    /**
     * Preload một Banner Ad theo placement key
     */
    fun preloadBannerAd(
        placementKey: String,
        adUnitId: String,
        activity: Activity,
        autoRefresh: Boolean = false,
        refreshInterval: Long = DEFAULT_REFRESH_INTERVAL,
        onLoadComplete: ((Boolean) -> Unit)? = null
    ) {
        // Kiểm tra premium
        if (premiumManager.isSubscribed()) {
            Log.d(TAG, "User is premium, skip preload: $placementKey")
            onLoadComplete?.invoke(false)
            return
        }

        // Kiểm tra consent
        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d(TAG, "Cannot request ads (consent), skip preload: $placementKey")
            onLoadComplete?.invoke(false)
            return
        }

        // Nếu đang load thì bỏ qua
        if (loadingJobs.containsKey(placementKey)) {
            Log.d(TAG, "Already loading: $placementKey")
            return
        }

        // Nếu đã có cache thì bỏ qua
        if (adsCache.containsKey(placementKey)) {
            Log.d(TAG, "Already cached: $placementKey")
            onLoadComplete?.invoke(true)
            return
        }

        Log.d(TAG, "Start preload: $placementKey")
        
        val job = scope.launch {
            var retryCount = 0
            var loadSuccess = false

            while (retryCount < MAX_RETRY && !loadSuccess) {
                try {
                    loadSuccess = loadBannerAdInternal(placementKey, adUnitId, activity)
                    if (!loadSuccess) {
                        retryCount++
                        if (retryCount < MAX_RETRY) {
                            delay(1000L * retryCount)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading banner ad: $placementKey", e)
                    retryCount++
                    if (retryCount < MAX_RETRY) {
                        delay(1000L * retryCount)
                    }
                }
            }

            loadingJobs.remove(placementKey)
            onLoadComplete?.invoke(loadSuccess)
            
            // Setup auto refresh nếu cần
            if (loadSuccess && autoRefresh) {
                setupAutoRefresh(placementKey, adUnitId, activity, refreshInterval)
            }
            
            Log.d(TAG, "Preload completed: $placementKey, success=$loadSuccess")
        }

        loadingJobs[placementKey] = job
    }

    /**
     * Load Banner Ad internal với timeout
     */
    private suspend fun loadBannerAdInternal(
        placementKey: String,
        adUnitId: String,
        activity: Activity
    ): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            var isCompleted = false
            
            // Timeout handler
            val timeoutJob = scope.launch {
                delay(DEFAULT_TIMEOUT)
                if (!isCompleted) {
                    isCompleted = true
                    Log.w(TAG, "Load timeout: $placementKey")
                    continuation.resume(false) {}
                }
            }

            // Create AdView
            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            
            // Set adaptive ad size
            val adSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getAdSizeAndroid11(activity)
            } else {
                getAdSize(activity)
            }
            adView.setAdSize(adSize)

            // Set listener
            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (!isCompleted) {
                        isCompleted = true
                        timeoutJob.cancel()
                        adsCache[placementKey] = adView
                        Log.d(TAG, "Banner ad loaded successfully: $placementKey")
                        continuation.resume(true) {}
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!isCompleted) {
                        isCompleted = true
                        timeoutJob.cancel()
                        Log.e(TAG, "Banner ad failed to load: $placementKey, error: ${error.message}")
                        continuation.resume(false) {}
                    }
                }
            }

            // Load ad
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
        }
    }

    /**
     * Show Banner Ad vào container
     */
    fun showBannerAd(
        placementKey: String,
        container: FrameLayout,
        onAdShowed: (() -> Unit)? = null,
        onAdFailed: (() -> Unit)? = null
    ) {
        val adView = adsCache[placementKey]
        
        if (adView == null) {
            Log.w(TAG, "No cached banner ad for: $placementKey")
            onAdFailed?.invoke()
            return
        }

        // Remove from cache
        adsCache.remove(placementKey)
        
        // Remove from parent if exists
        (adView.parent as? FrameLayout)?.removeView(adView)
        
        // Add to container
        container.removeAllViews()
        container.addView(adView)
        
        Log.d(TAG, "Banner ad showed: $placementKey")
        onAdShowed?.invoke()
    }

    /**
     * Setup auto refresh cho banner
     */
    private fun setupAutoRefresh(
        placementKey: String,
        adUnitId: String,
        activity: Activity,
        refreshInterval: Long
    ) {
        // Cancel existing refresh job
        refreshJobs[placementKey]?.cancel()
        
        val refreshJob = scope.launch {
            while (true) {
                delay(refreshInterval)
                
                // Chỉ refresh nếu chưa có cache
                if (!adsCache.containsKey(placementKey)) {
                    Log.d(TAG, "Auto refresh: $placementKey")
                    loadBannerAdInternal(placementKey, adUnitId, activity)
                }
            }
        }
        
        refreshJobs[placementKey] = refreshJob
    }

    /**
     * Stop auto refresh
     */
    fun stopAutoRefresh(placementKey: String) {
        refreshJobs[placementKey]?.cancel()
        refreshJobs.remove(placementKey)
        Log.d(TAG, "Auto refresh stopped: $placementKey")
    }

    /**
     * Kiểm tra xem Banner Ad đã được preload chưa
     */
    fun isBannerAdLoaded(placementKey: String): Boolean {
        return adsCache.containsKey(placementKey)
    }

    /**
     * Kiểm tra xem Banner Ad đang được load không
     */
    fun isLoading(placementKey: String): Boolean {
        return loadingJobs.containsKey(placementKey)
    }

    /**
     * Hủy load một Banner Ad
     */
    fun cancelLoad(placementKey: String) {
        loadingJobs[placementKey]?.cancel()
        loadingJobs.remove(placementKey)
        Log.d(TAG, "Cancelled load: $placementKey")
    }

    /**
     * Clear cache của một placement
     */
    fun clearCache(placementKey: String) {
        adsCache[placementKey]?.destroy()
        adsCache.remove(placementKey)
        stopAutoRefresh(placementKey)
        Log.d(TAG, "Cache cleared: $placementKey")
    }

    /**
     * Clear toàn bộ cache
     */
    fun clearAllCache() {
        adsCache.values.forEach { it.destroy() }
        adsCache.clear()
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        Log.d(TAG, "All cache cleared")
    }

    /**
     * Preload nhiều Banner Ads cùng lúc
     */
    fun preloadMultipleBannerAds(
        placements: Map<String, Pair<String, Activity>>, // Map<placementKey, Pair<adUnitId, activity>>
        autoRefresh: Boolean = false,
        refreshInterval: Long = DEFAULT_REFRESH_INTERVAL,
        onAllComplete: ((Map<String, Boolean>) -> Unit)? = null
    ) {
        val results = mutableMapOf<String, Boolean>()
        var completedCount = 0

        placements.forEach { (placementKey, pair) ->
            val (adUnitId, activity) = pair
            preloadBannerAd(placementKey, adUnitId, activity, autoRefresh, refreshInterval) { success ->
                synchronized(results) {
                    results[placementKey] = success
                    completedCount++
                    
                    if (completedCount == placements.size) {
                        onAllComplete?.invoke(results)
                    }
                }
            }
        }
    }

    /**
     * Get số lượng ads đã cache
     */
    fun getCachedCount(): Int = adsCache.size

    /**
     * Get danh sách placement keys đã cache
     */
    fun getCachedKeys(): Set<String> = adsCache.keys.toSet()

    // ─── Helper functions for adaptive banner size ───────────────────────────────

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
    private fun getAdSize(activity: Activity): AdSize {
        val display = activity.windowManager.defaultDisplay
        val outMetrics = DisplayMetrics()
        display.getMetrics(outMetrics)
        val density = outMetrics.density
        val adWidthPixels = outMetrics.widthPixels.toFloat()
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }
}
