package tpt.dev.monetization.ads.preload

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.nativead.NativeAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

/**
 * PreloadNativeAdManager - Quản lý preload Native Ads
 * 
 * Chức năng:
 * - Preload nhiều Native Ads theo placement key
 * - Cache ads đã load thành công
 * - Tự động reload khi ads được sử dụng
 * - Hỗ trợ timeout và retry logic
 * - Thread-safe với coroutines
 */
class PreloadNativeAdManager(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) {
    private val adsCache = mutableMapOf<String, NativeAd>()
    private val loadingJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Main)
    
    companion object {
        private const val TAG = "PreloadNativeAdManager"
        private const val DEFAULT_TIMEOUT = 10000L // 10 seconds
        private const val MAX_RETRY = 2
    }

    /**
     * Preload một Native Ad theo placement key
     * @param placementKey Key định danh cho placement
     * @param adUnitId Ad Unit ID từ AdMob
     * @param autoReload Tự động reload sau khi ad được lấy ra
     * @param onLoadComplete Callback khi load xong (success hoặc fail)
     */
    fun preloadNativeAd(
        placementKey: String,
        adUnitId: String,
        autoReload: Boolean = true,
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
                    loadSuccess = loadNativeAdInternal(placementKey, adUnitId)
                    if (!loadSuccess) {
                        retryCount++
                        if (retryCount < MAX_RETRY) {
                            delay(1000L * retryCount) // Exponential backoff
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading native ad: $placementKey", e)
                    retryCount++
                    if (retryCount < MAX_RETRY) {
                        delay(1000L * retryCount)
                    }
                }
            }

            loadingJobs.remove(placementKey)
            onLoadComplete?.invoke(loadSuccess)
            
            Log.d(TAG, "Preload completed: $placementKey, success=$loadSuccess")
        }

        loadingJobs[placementKey] = job
    }

    /**
     * Load Native Ad internal với timeout
     */
    private suspend fun loadNativeAdInternal(
        placementKey: String,
        adUnitId: String
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

            val adLoader = com.google.android.gms.ads.AdLoader.Builder(context, adUnitId)
                .forNativeAd { nativeAd ->
                    if (!isCompleted) {
                        isCompleted = true
                        timeoutJob.cancel()
                        adsCache[placementKey] = nativeAd
                        Log.d(TAG, "Native ad loaded successfully: $placementKey")
                        continuation.resume(true) {}
                    } else {
                        nativeAd.destroy()
                    }
                }
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                        if (!isCompleted) {
                            isCompleted = true
                            timeoutJob.cancel()
                            Log.e(TAG, "Native ad failed to load: $placementKey, error: ${error.message}")
                            continuation.resume(false) {}
                        }
                    }
                })
                .build()

            adLoader.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
        }
    }

    /**
     * Lấy Native Ad đã preload
     * @param placementKey Key của placement
     * @param autoReload Tự động reload sau khi lấy
     * @param adUnitId Ad Unit ID để reload (bắt buộc nếu autoReload = true)
     * @return NativeAd hoặc null nếu chưa load
     */
    fun getNativeAd(
        placementKey: String,
        autoReload: Boolean = true,
        adUnitId: String? = null
    ): NativeAd? {
        val ad = adsCache.remove(placementKey)
        
        if (ad != null) {
            Log.d(TAG, "Native ad retrieved: $placementKey")
            
            // Auto reload nếu cần
            if (autoReload && adUnitId != null) {
                preloadNativeAd(placementKey, adUnitId)
            }
        } else {
            Log.w(TAG, "No cached native ad for: $placementKey")
        }
        
        return ad
    }

    /**
     * Kiểm tra xem Native Ad đã được preload chưa
     */
    fun isNativeAdLoaded(placementKey: String): Boolean {
        return adsCache.containsKey(placementKey)
    }

    /**
     * Kiểm tra xem Native Ad đang được load không
     */
    fun isLoading(placementKey: String): Boolean {
        return loadingJobs.containsKey(placementKey)
    }

    /**
     * Hủy load một Native Ad
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
        Log.d(TAG, "All cache cleared")
    }

    /**
     * Preload nhiều Native Ads cùng lúc
     */
    fun preloadMultipleNativeAds(
        placements: Map<String, String>, // Map<placementKey, adUnitId>
        onAllComplete: ((Map<String, Boolean>) -> Unit)? = null
    ) {
        val results = mutableMapOf<String, Boolean>()
        var completedCount = 0

        placements.forEach { (placementKey, adUnitId) ->
            preloadNativeAd(placementKey, adUnitId) { success ->
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
}
