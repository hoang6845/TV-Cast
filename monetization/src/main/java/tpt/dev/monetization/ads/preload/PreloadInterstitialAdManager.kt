package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.PrepareLoadingAdsDialog
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager

/**
 * PreloadInterstitialAdManager - Quản lý preload Interstitial Ads
 * 
 * Chức năng:
 * - Preload nhiều Interstitial Ads theo placement key
 * - Cache ads đã load thành công
 * - Tự động reload sau khi show
 * - Quản lý thời gian giữa các lần show (capping)
 * - Hỗ trợ timeout và retry logic
 * - Thread-safe với coroutines
 */
class PreloadInterstitialAdManager(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) {
    private val adsCache = mutableMapOf<String, InterstitialAd>()
    private val loadingJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var lastShowTime = 0L
    private var showInterval = DEFAULT_SHOW_INTERVAL
    private var isShowingAd = false
    
    companion object {
        private const val TAG = "PreloadInterstitialAdManager"
        private const val DEFAULT_TIMEOUT = 15000L // 15 seconds
        private const val MAX_RETRY = 2
        private const val DEFAULT_SHOW_INTERVAL = 20000L // 20 seconds between shows
    }

    /**
     * Cập nhật thời gian tối thiểu giữa các lần show ads
     */
    fun setShowInterval(intervalMillis: Long) {
        showInterval = intervalMillis
        Log.d(TAG, "Show interval updated: $intervalMillis ms")
    }

    /**
     * Preload một Interstitial Ad theo placement key
     */
    fun preloadInterstitialAd(
        placementKey: String,
        adUnitId: String,
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
                    loadSuccess = loadInterstitialAdInternal(placementKey, adUnitId)
                    if (!loadSuccess) {
                        retryCount++
                        if (retryCount < MAX_RETRY) {
                            delay(1000L * retryCount) // Exponential backoff
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading interstitial ad: $placementKey", e)
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
     * Load Interstitial Ad internal với timeout
     */
    private suspend fun loadInterstitialAdInternal(
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

            val adRequest = AdRequest.Builder().build()
            
            InterstitialAd.load(
                context,
                adUnitId,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(interstitialAd: InterstitialAd) {
                        if (!isCompleted) {
                            isCompleted = true
                            timeoutJob.cancel()
                            interstitialAd.setImmersiveMode(true)
                            adsCache[placementKey] = interstitialAd
                            Log.d(TAG, "Interstitial ad loaded successfully: $placementKey")
                            continuation.resume(true) {}
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (!isCompleted) {
                            isCompleted = true
                            timeoutJob.cancel()
                            Log.e(TAG, "Interstitial ad failed to load: $placementKey, error: ${error.message}")
                            continuation.resume(false) {}
                        }
                    }
                }
            )
        }
    }

    /**
     * Show Interstitial Ad với auto reload
     */
    fun showInterstitialAd(
        activity: AppCompatActivity,
        placementKey: String,
        adUnitId: String,
        forceShow: Boolean = false,
        onAdShowed: (() -> Unit)? = null,
        onAdClosed: ((Boolean) -> Unit)? = null
    ) {
        // Kiểm tra premium
        if (premiumManager.isSubscribed()) {
            Log.d(TAG, "User is premium, skip show: $placementKey")
            onAdClosed?.invoke(false)
            return
        }

        // Kiểm tra đang show ad khác
        if (isShowingAd) {
            Log.d(TAG, "Already showing another ad")
            onAdClosed?.invoke(false)
            return
        }

        // Kiểm tra capping time (trừ khi force show)
        if (!forceShow) {
            val timeSinceLastShow = System.currentTimeMillis() - lastShowTime
            if (timeSinceLastShow < showInterval) {
                Log.d(TAG, "Show interval not reached: $timeSinceLastShow ms < $showInterval ms")
                onAdClosed?.invoke(false)
                return
            }
        }

        // Lấy ad từ cache
        val interstitialAd = adsCache.remove(placementKey)
        
        if (interstitialAd == null) {
            Log.w(TAG, "No cached interstitial ad for: $placementKey")
            // Trigger preload cho lần sau
            preloadInterstitialAd(placementKey, adUnitId)
            onAdClosed?.invoke(false)
            return
        }

        // Kiểm tra lifecycle
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "App not in foreground, skip show")
            adsCache[placementKey] = interstitialAd // Put back to cache
            onAdClosed?.invoke(false)
            return
        }

        Log.d(TAG, "Showing interstitial ad: $placementKey")
        isShowingAd = true

        // Setup callbacks
        interstitialAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isShowingAd = false
                lastShowTime = System.currentTimeMillis()
                Log.d(TAG, "Ad dismissed: $placementKey")
                
                // Restore status bar
                activity.restoreStatusBar()
                
                // Auto reload
                preloadInterstitialAd(placementKey, adUnitId)
                
                onAdClosed?.invoke(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                isShowingAd = false
                Log.e(TAG, "Ad failed to show: $placementKey, error: ${adError.message}")
                
                // Restore status bar
                activity.restoreStatusBar()
                
                // Reload
                preloadInterstitialAd(placementKey, adUnitId)
                
                onAdClosed?.invoke(false)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Ad showed: $placementKey")
                onAdShowed?.invoke()
            }
        }

        // Show loading dialog
        var prepareDialog: PrepareLoadingAdsDialog? = null
        try {
            prepareDialog = PrepareLoadingAdsDialog(activity)
            prepareDialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing prepare dialog", e)
        }

        // Delay để show dialog trước
        mainHandler.postDelayed({
            try {
                if (prepareDialog?.isShowing == true && !activity.isDestroyed) {
                    prepareDialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing prepare dialog", e)
            }
            
            // Hide status bar và show ad
            activity.hideStatusBar()
            interstitialAd.show(activity)
        }, 500)
    }

    /**
     * Kiểm tra xem Interstitial Ad đã được preload chưa
     */
    fun isInterstitialAdLoaded(placementKey: String): Boolean {
        return adsCache.containsKey(placementKey)
    }

    /**
     * Kiểm tra xem đang show ad không
     */
    fun isShowingInterstitialAd(): Boolean = isShowingAd

    /**
     * Kiểm tra xem Interstitial Ad đang được load không
     */
    fun isLoading(placementKey: String): Boolean {
        return loadingJobs.containsKey(placementKey)
    }

    /**
     * Hủy load một Interstitial Ad
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
        adsCache.remove(placementKey)
        Log.d(TAG, "Cache cleared: $placementKey")
    }

    /**
     * Clear toàn bộ cache
     */
    fun clearAllCache() {
        adsCache.clear()
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        Log.d(TAG, "All cache cleared")
    }

    /**
     * Preload nhiều Interstitial Ads cùng lúc
     */
    fun preloadMultipleInterstitialAds(
        placements: Map<String, String>, // Map<placementKey, adUnitId>
        onAllComplete: ((Map<String, Boolean>) -> Unit)? = null
    ) {
        val results = mutableMapOf<String, Boolean>()
        var completedCount = 0

        placements.forEach { (placementKey, adUnitId) ->
            preloadInterstitialAd(placementKey, adUnitId) { success ->
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
     * Reset thời gian show ad cuối cùng
     */
    fun resetLastShowTime() {
        lastShowTime = 0L
        Log.d(TAG, "Last show time reset")
    }

    /**
     * Get số lượng ads đã cache
     */
    fun getCachedCount(): Int = adsCache.size

    /**
     * Get danh sách placement keys đã cache
     */
    fun getCachedKeys(): Set<String> = adsCache.keys.toSet()

    // ─── Helper functions for status bar ───────────────────────────────────────

    private fun Activity.hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun Activity.restoreStatusBar() {
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
}
