package tpt.dev.monetization.ads.preload

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.base.BaseAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager
import kotlin.random.Random

/**
 * Preload Native Management
 * Quản lý việc preload và hiển thị native ads với cơ chế pool
 */
class PreloadNativeManagement(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) : BaseAdUtils(premiumManager) {

    // Pool chứa các native ads đã load
    private val adsPool: MutableMap<String, NativeAd> = HashMap()
    
    // Set chứa các key đang trong quá trình load
    private val loadingPools: MutableSet<String> = mutableSetOf()
    
    // Key backup ads
    private var backupAdKey: String = ""

    companion object {
        private const val TAG = "PreloadNative"
        private const val DEFAULT_DELAY_WATERFALL = 300L
    }

    /**
     * Load native ad với ad key
     */
    fun load(
        activity: Activity,
        adKey: String,
        numberOfAds: Int = 1,
        callback: ((NativeAd?) -> Unit)? = null
    ) {
        Log.d(TAG, "load: $adKey")

        if (!appAllowShowAd) {
            Log.d(TAG, "load: $adKey - app not allow show ad")
            callback?.invoke(null)
            return
        }

        if (!googleMobileAdsConsentManager.canRequestAds) {
            Log.d(TAG, "load: $adKey - cannot request ads")
            callback?.invoke(null)
            return
        }

        // Kiểm tra đã load rồi
        if (adsPool.containsKey(adKey)) {
            Log.d(TAG, "load: $adKey - already loaded")
            callback?.invoke(adsPool[adKey])
            return
        }

        // Kiểm tra đang load
        if (loadingPools.contains(adKey)) {
            Log.d(TAG, "load: $adKey - already loading")
            callback?.invoke(null)
            return
        }

        // Thêm vào loading pool
        loadingPools.add(adKey)

        // Video options
        val videoOptions = VideoOptions.Builder()
            .setStartMuted(true)
            .build()

        // Native ad options
        val nativeAdOptions = NativeAdOptions.Builder()
            .setVideoOptions(videoOptions)
            .build()

        // Ad listener
        val adListener = object : AdListener() {
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

        // Build ad loader
        val adLoader = AdLoader.Builder(activity, adKey)
            .forNativeAd { nativeAd ->
                // Check activity state
                if (activity.isDestroyed || activity.isFinishing || activity.isChangingConfigurations) {
                    nativeAd.destroy()
                    loadingPools.remove(adKey)
                    callback?.invoke(null)
                    return@forNativeAd
                }

                Log.d(TAG, "onNativeAdLoaded: $adKey")
                adsPool[adKey] = nativeAd
                loadingPools.remove(adKey)
                callback?.invoke(nativeAd)
            }
            .withAdListener(adListener)
            .withNativeAdOptions(nativeAdOptions)
            .build()

        // Load ads
        if (numberOfAds > 1) {
            adLoader.loadAds(defaultAdRequest, numberOfAds)
        } else {
            adLoader.loadAd(defaultAdRequest)
        }
    }

    /**
     * Load nhiều native ads với waterfall delay
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
     * Get native ad từ pool
     */
    fun getNativeAd(adKey: String, removeAfterGet: Boolean = false): NativeAd? {
        val ad = adsPool[adKey]
        if (removeAfterGet) {
            adsPool.remove(adKey)
        }
        return ad
    }

    /**
     * Get native ad hoặc backup
     */
    fun getNativeAdOrBackup(adKey: String, removeAfterGet: Boolean = false): NativeAd? {
        var ad = getNativeAd(adKey, removeAfterGet)
        if (ad == null && backupAdKey.isNotEmpty()) {
            Log.d(TAG, "getNativeAdOrBackup: using backup $backupAdKey")
            ad = getNativeAd(backupAdKey, removeAfterGet)
        }
        return ad
    }

    /**
     * Destroy native ad
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
