package tpt.dev.monetization.ads.preload

import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.nativead.NativeAd
import tpt.dev.monetization.ads.AdsManager

/**
 * Extension functions để show ads từ Fragment
 */

// ============================================================================
// Interstitial Extensions
// ============================================================================

/**
 * Show interstitial ad từ Fragment
 */
fun Fragment.showInterstitialAd(
    adKey: String,
    reload: Boolean = true,
    onAdClosed: ((Boolean) -> Unit)? = null,
    onAdShowed: (() -> Unit)? = null,
) {
    val activity = this.activity ?: run {
        onAdClosed?.invoke(false)
        return
    }
    
    AdsManager.getInstance().preloadInterstitialManagement.show(
        activity = activity,
        adKey = adKey,
        reload = reload,
        onAdShowed = onAdShowed,
        onAdClosed = onAdClosed
    )
}

/**
 * Load interstitial ad từ Fragment
 */
fun Fragment.loadInterstitialAd(
    adKey: String,
    callback: (() -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke()
        return
    }
    
    AdsManager.getInstance().preloadInterstitialManagement.load(
        activity = activity,
        adKey = adKey,
        callback = callback
    )
}

/**
 * Load nhiều interstitial ads với waterfall từ Fragment
 */
fun Fragment.loadInterstitialAdsWithWaterfall(
    adKeys: List<String>,
    delayMs: Long = 300L,
    callback: (() -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke()
        return
    }
    
    AdsManager.getInstance().preloadInterstitialManagement.loadWithWaterfall(
        activity = activity,
        adKeys = adKeys,
        delayMs = delayMs,
        callback = callback
    )
}

/**
 * Check interstitial ad đã load chưa
 */
fun Fragment.isInterstitialAdLoaded(adKey: String): Boolean {
    return AdsManager.getInstance().preloadInterstitialManagement.isLoaded(adKey)
}

// ============================================================================
// Native Extensions
// ============================================================================

/**
 * Load native ad từ Fragment
 */
fun Fragment.loadNativeAd(
    adKey: String,
    numberOfAds: Int = 1,
    callback: ((NativeAd?) -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke(null)
        return
    }
    
    AdsManager.getInstance().preloadNativeManagement.load(
        activity = activity,
        adKey = adKey,
        numberOfAds = numberOfAds,
        callback = callback
    )
}

/**
 * Load nhiều native ads với waterfall từ Fragment
 */
fun Fragment.loadNativeAdsWithWaterfall(
    adKeys: List<String>,
    delayMs: Long = 300L,
    callback: (() -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke()
        return
    }
    
    AdsManager.getInstance().preloadNativeManagement.loadWithWaterfall(
        activity = activity,
        adKeys = adKeys,
        delayMs = delayMs,
        callback = callback
    )
}

/**
 * Get native ad từ pool
 */
fun Fragment.getNativeAd(
    adKey: String,
    removeAfterGet: Boolean = false
): NativeAd? {
    return AdsManager.getInstance().preloadNativeManagement.getNativeAd(
        adKey = adKey,
        removeAfterGet = removeAfterGet
    )
}

/**
 * Get native ad hoặc backup
 */
fun Fragment.getNativeAdOrBackup(
    adKey: String,
    removeAfterGet: Boolean = false
): NativeAd? {
    return AdsManager.getInstance().preloadNativeManagement.getNativeAdOrBackup(
        adKey = adKey,
        removeAfterGet = removeAfterGet
    )
}

/**
 * Check native ad đã load chưa
 */
fun Fragment.isNativeAdLoaded(adKey: String): Boolean {
    return AdsManager.getInstance().preloadNativeManagement.isLoaded(adKey)
}

// ============================================================================
// Banner Extensions
// ============================================================================

/**
 * Load adaptive banner từ Fragment
 */
fun Fragment.loadAdaptiveBanner(
    adKey: String,
    callback: ((android.view.View?) -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke(null)
        return
    }
    
    AdsManager.getInstance().preloadBannerManagement.loadAdaptiveBanner(
        activity = activity,
        adKey = adKey,
        callback = callback
    )
}

/**
 * Load collapsible banner từ Fragment
 */
fun Fragment.loadCollapsibleBanner(
    adKey: String,
    placement: BannerPlacement = BannerPlacement.BOTTOM,
    requestId: String? = null,
    callback: ((android.view.View?, String) -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke(null, "")
        return
    }
    
    AdsManager.getInstance().preloadBannerManagement.loadCollapsibleBanner(
        activity = activity,
        adKey = adKey,
        placement = placement,
        requestId = requestId,
        callback = callback
    )
}

/**
 * Load nhiều banners với waterfall từ Fragment
 */
fun Fragment.loadBannersWithWaterfall(
    adKeys: List<String>,
    delayMs: Long = 300L,
    callback: (() -> Unit)? = null
) {
    val activity = this.activity ?: run {
        callback?.invoke()
        return
    }
    
    AdsManager.getInstance().preloadBannerManagement.loadWithWaterfall(
        activity = activity,
        adKeys = adKeys,
        delayMs = delayMs,
        callback = callback
    )
}

/**
 * Show banner vào container từ Fragment
 */
fun Fragment.showBanner(
    adKey: String,
    container: FrameLayout,
    removeAfterShow: Boolean = false
): Boolean {
    return AdsManager.getInstance().preloadBannerManagement.showBanner(
        adKey = adKey,
        container = container,
        removeAfterShow = removeAfterShow
    )
}

/**
 * Show banner hoặc backup từ Fragment
 */
fun Fragment.showBannerOrBackup(
    adKey: String,
    container: FrameLayout,
    removeAfterShow: Boolean = false
): Boolean {
    return AdsManager.getInstance().preloadBannerManagement.showBannerOrBackup(
        adKey = adKey,
        container = container,
        removeAfterShow = removeAfterShow
    )
}

/**
 * Check banner ad đã load chưa
 */
fun Fragment.isBannerAdLoaded(adKey: String): Boolean {
    return AdsManager.getInstance().preloadBannerManagement.isLoaded(adKey)
}

// ============================================================================
// Utility Extensions
// ============================================================================

/**
 * Enable backup interstitial từ Fragment
 */
fun Fragment.enableBackupInterstitial(backupKey: String) {
    val activity = this.activity ?: return
    AdsManager.getInstance().preloadInterstitialManagement.enableBackup(activity, backupKey)
}

/**
 * Enable backup native từ Fragment
 */
fun Fragment.enableBackupNative(backupKey: String) {
    val activity = this.activity ?: return
    AdsManager.getInstance().preloadNativeManagement.enableBackup(activity, backupKey)
}

/**
 * Enable backup banner từ Fragment
 */
fun Fragment.enableBackupBanner(backupKey: String) {
    val activity = this.activity ?: return
    AdsManager.getInstance().preloadBannerManagement.enableBackup(activity, backupKey)
}
