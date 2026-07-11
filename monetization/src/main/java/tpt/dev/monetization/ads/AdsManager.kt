package tpt.dev.monetization.ads

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.MobileAds
import tpt.dev.monetization.ads.bannerAd.BannerAdUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tpt.dev.monetization.ads.appOpenAd.AppOpenAdUtils
import tpt.dev.monetization.ads.appOpenAd.OnShowAdCompleteListener
import tpt.dev.monetization.ads.constants.AbstractAdsConstants
import tpt.dev.monetization.ads.interstitlaAd.InterstitialAdUtils
import tpt.dev.monetization.ads.interstitlaAd.InterstitialType
import tpt.dev.monetization.ads.nativeAd.SingleNativeAdUtils
import tpt.dev.monetization.ads.preload.PreloadBannerManagement
import tpt.dev.monetization.ads.preload.PreloadInterstitialManagement
import tpt.dev.monetization.ads.preload.PreloadNativeManagement
import tpt.dev.monetization.ads.preload.PreloadRewardedManagement
import tpt.dev.monetization.ads.rewardedAd.RewardedAdUtils
import tpt.dev.monetization.ads.ump.GoogleMobileAdsConsentManager
import tpt.dev.monetization.common.premium.IPremiumManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AdsManager private constructor(
    private val application: Application,
    private val premiumManager: IPremiumManager
) : DefaultLifecycleObserver {
    private val activityLifecycleCallbacks = AdActivityLifecycleCallbacks()
    private lateinit var disableAppOpenAdActivities: List<Class<*>>
    lateinit var appOpenAdUtils: AppOpenAdUtils
    lateinit var interstitialAdUtils: InterstitialAdUtils
    lateinit var rewardedAdUtils: RewardedAdUtils
    private var lastTimeShowInterstitialAd = 0L
    private var timeIntervalShowInterstitialAd: Duration =
        DEFAULT_TIME_INTERVAL_SHOW_INTERSTITIAL_AD

    private var lastTimeShowOpenAd = 0L

    private var isShowingFullScreenAd = AtomicBoolean(false)
    
    // Flag để track xem app đã qua splash chưa
    // true = đã qua splash, có thể show App Open Ad khi comeback
    // false = chưa qua splash (cold start), không show App Open Ad
    private var hasCompletedSplash = AtomicBoolean(false)

    private val googleMobileAdsConsentManager =
        GoogleMobileAdsConsentManager.Companion.getInstance(application)
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)
    val singleNativeAdUtils: SingleNativeAdUtils by lazy {
        SingleNativeAdUtils(premiumManager, googleMobileAdsConsentManager)
    }
    val bannerAdUtils: BannerAdUtils by lazy {
        BannerAdUtils(premiumManager, googleMobileAdsConsentManager)
    }

    // Preload Management
    val preloadInterstitialManagement: PreloadInterstitialManagement by lazy {
        PreloadInterstitialManagement(application, this, premiumManager, googleMobileAdsConsentManager)
    }
    val preloadNativeManagement: PreloadNativeManagement by lazy {
        PreloadNativeManagement(application, premiumManager, googleMobileAdsConsentManager)
    }
    val preloadBannerManagement: PreloadBannerManagement by lazy {
        PreloadBannerManagement(application, premiumManager, googleMobileAdsConsentManager)
    }
    val preloadRewardedManagement: PreloadRewardedManagement by lazy {
        PreloadRewardedManagement(application, this, premiumManager, googleMobileAdsConsentManager)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appOpenAdTimeoutRunnable: Runnable? = null
    private var appOpenLoadingAdsDialog: AppOpenLoadingAdsDialog? = null
    private var timeIntervalShowInterVsOpen: Duration= DEFAULT_TIME_INTERVAL_SHOW_INTERSTITIAL_AD
    
    private var isGlobalAdsEnabled = AtomicBoolean(true)
    private var isAppOpenAdEnabled = AtomicBoolean(true)
    private var isInterSplashEnabled = AtomicBoolean(true)

    fun configure(
        adsConstants: AbstractAdsConstants,
        disableAppOpenAdActivities: List<Class<*>> = emptyList(),
        isAllowShowOpenAd: Boolean
    ) {
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        this.disableAppOpenAdActivities = disableAppOpenAdActivities

        appOpenAdUtils = AppOpenAdUtils(
            isAllowShowOpenAd = isAllowShowOpenAd,
            adsConstants.ADMOB_APP_OPEN_ID,
            application,
            this,
            premiumManager,
            googleMobileAdsConsentManager
        )

        interstitialAdUtils = InterstitialAdUtils(
            adsConstants.ADMOB_INTERSTITIAL_ID,
            application,
            this,
            premiumManager,
            googleMobileAdsConsentManager,
            InterstitialType.COMMON
        )

        rewardedAdUtils = RewardedAdUtils(
            adsConstants.REWARD_ID,
            this,
            premiumManager,
            googleMobileAdsConsentManager
        )

//        openInterstitialAdUtils = InterstitialAdUtils(
//            adsConstants.ADMOB_LAUNCH_INTERSTITIAL_ID,
//            application,
//            this,
//            premiumManager,
//            googleMobileAdsConsentManager,
//            InterstitialType.OPEN
//        )
    }

    fun gatherConsent(
        activity: Activity,
        listener: OnGatherConsentListener
    ) {
        googleMobileAdsConsentManager.gatherConsent(activity) { consentError ->
            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk {
                    listener.onCompletion(consentError?.message)
                }
            } else {
                listener.onCompletion(consentError?.message)
            }
        }

//        if (googleMobileAdsConsentManager.canRequestAds) {
//            initializeMobileAdsSdk()
//        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Initialize the Google Mobile Ads SDK on a background thread.
            MobileAds.initialize(application)
            withContext(Dispatchers.Main) {
                // Load an ad on the main thread.
                interstitialAdUtils.loadAd()
//                openInterstitialAdUtils.loadAd()
            }
        }
    }

    private fun initializeMobileAdsSdk(onInitialized: (() -> Unit)? = null) {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            onInitialized?.invoke()  // đã init rồi thì gọi luôn
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(application)
            withContext(Dispatchers.Main) {
                interstitialAdUtils.loadAd()
//                openInterstitialAdUtils.loadAd()
                onInitialized?.invoke()  // báo init xong
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)

        if (!isGlobalAdsEnabled.get()) {
            Log.d("AppLifecycle", "Skip: Global ads disabled")
            return
        }

        if (!isAppOpenAdEnabled.get()) {
            Log.d("AppLifecycle", "Skip: App Open Ad disabled")
            return
        }

        if (!hasCompletedSplash.get()) {
            Log.d("AppLifecycle", "Skip: Splash chưa hoàn thành")
            return
        }

        if (checkIsShowingFullScreenAd()) {
            Log.d("AppLifecycle", "Skip: Đang hiển thị fullscreen ad")
            return
        }

        val currentActivity = activityLifecycleCallbacks.currentActivity as? AppCompatActivity
        if (currentActivity == null) {
            Log.d("AppLifecycle", "Skip: currentActivity null hoặc không phải AppCompatActivity")
            return
        }

        Log.d("AppLifecycle", "Current Activity: ${currentActivity.javaClass.name}")

        if (disableAppOpenAdActivities.any { it.name == currentActivity.javaClass.name }) {
            Log.d("AppLifecycle", "Skip: Activity bị disable app open ad")
            return
        }

        if (appOpenLoadingAdsDialog?.isShowing == true) {
            Log.d("AppLifecycle", "Skip: Loading dialog đang hiển thị")
            return
        }

        Log.d("AppLifecycle", "Show App Open Ad triggered")
        showAppOpenAdWithLoading(currentActivity)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)

    }

    private fun showAppOpenAdWithLoading(activity: AppCompatActivity) {
        Log.d("AppLifecycle", "showAppOpenAdWithLoading: start")

        if (activity.isFinishing || activity.isDestroyed) {
            Log.d("AppLifecycle", "showAppOpenAdWithLoading: activity invalid (finishing/destroyed)")
            return
        }

        Log.d("AppLifecycle", "showAppOpenAdWithLoading: show loading dialog")
        showAppOpenLoadingAdsDialog(activity)

        Log.d("AppLifecycle", "showAppOpenAdWithLoading: start timeout")
        startAppOpenAdTimeout()

        Log.d("AppLifecycle", "showAppOpenAdWithLoading: call showAdIfAvailable")

        appOpenAdUtils.showAdIfAvailable(activity, object : OnShowAdCompleteListener {
            override fun onShowAdComplete() {
                Log.d("AppLifecycle", "onShowAdComplete: app open ad finished")

                clearAppOpenAdTimeout()
                Log.d("AppLifecycle", "onShowAdComplete: timeout cleared")

                dismissAppOpenLoadingAdsDialog()
                Log.d("AppLifecycle", "onShowAdComplete: loading dialog dismissed")

                appOpenAdUtils.setPendingShowAd(false)
                Log.d("AppLifecycle", "onShowAdComplete: pending show reset")
            }
        })
    }
    private fun showAppOpenLoadingAdsDialog(activity: Activity) {
        dismissAppOpenLoadingAdsDialog()
        try {
            appOpenLoadingAdsDialog = AppOpenLoadingAdsDialog(activity)
            appOpenLoadingAdsDialog?.show()
        } catch (e: Exception) {
            appOpenLoadingAdsDialog = null
        }
    }

    private fun dismissAppOpenLoadingAdsDialog() {
        try {
            if (appOpenLoadingAdsDialog?.isShowing == true) {
                appOpenLoadingAdsDialog?.dismiss()
            }
        } catch (e: Exception) {
        } finally {
            appOpenLoadingAdsDialog = null
        }
    }

    private fun startAppOpenAdTimeout() {
        clearAppOpenAdTimeout()
        appOpenAdTimeoutRunnable = Runnable {
            appOpenAdUtils.setPendingShowAd(false)
            dismissAppOpenLoadingAdsDialog()
        }
        mainHandler.postDelayed(
            appOpenAdTimeoutRunnable!!,
            APP_OPEN_AD_TIMEOUT.inWholeMilliseconds
        )
    }

    private fun clearAppOpenAdTimeout() {
        appOpenAdTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        appOpenAdTimeoutRunnable = null
    }

    fun updateTimeIntervalShowInterstitialAd(interval: Duration) {
        timeIntervalShowInterstitialAd = interval
    }

    internal fun getTimeIntervalShowInterstitialAd() = timeIntervalShowInterstitialAd

    internal fun updateLastTimeShowInterstitialAd(value: Long) {
        lastTimeShowInterstitialAd = value
    }

    internal fun getLastTimeShowInterstitialAd() = lastTimeShowInterstitialAd


    internal fun updateLastTimeShowOpenAd(value: Long) {
        lastTimeShowOpenAd = value
    }

    fun updateTimeIntervalShowInterVsOpen(interval: Duration) {
        timeIntervalShowInterVsOpen = interval
    }

    internal fun getTimeIntervalShowInterVsOpen() = timeIntervalShowInterVsOpen
    
    /**
     * Bật/tắt tất cả ads trong app
     */
    fun setGlobalAdsEnabled(enabled: Boolean) {
        isGlobalAdsEnabled.set(enabled)
        Log.d("AdsManager", "Global ads enabled: $enabled")
    }
    
    fun isGlobalAdsEnabled() = isGlobalAdsEnabled.get()
    
    /**
     * Bật/tắt App Open Ad
     */
    fun setAppOpenAdEnabled(enabled: Boolean) {
        isAppOpenAdEnabled.set(enabled)
        Log.d("AdsManager", "App Open Ad enabled: $enabled")
    }
    
    fun isAppOpenAdEnabled() = isAppOpenAdEnabled.get()
    
    /**
     * Bật/tắt Interstitial sau splash
     */
    fun setInterSplashEnabled(enabled: Boolean) {
        isInterSplashEnabled.set(enabled)
        Log.d("AdsManager", "Inter Splash enabled: $enabled")
    }
    
    fun isInterSplashEnabled() = isInterSplashEnabled.get()



    internal fun getLastTimeShowOpenAd() = lastTimeShowOpenAd


    internal fun checkIsShowingFullScreenAd() = isShowingFullScreenAd.get()

    internal fun updateIsShowingFullScreenAd(value: Boolean) {
        isShowingFullScreenAd.set(value)
    }
    
    fun markSplashCompleted() {
        hasCompletedSplash.set(true)
    }

    fun resetSplashFlag() {
        hasCompletedSplash.set(false)
    }

    interface OnGatherConsentListener {
        fun onCompletion(error: String?)
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        markSplashCompleted()
        Log.d("AppLifecycle", "pause")

    }

    companion object {
        private val DEFAULT_TIME_INTERVAL_SHOW_INTERSTITIAL_AD: Duration = 20.seconds
        private val APP_OPEN_AD_TIMEOUT: Duration = 10.seconds

        @Volatile
        private var instance: AdsManager? = null

        /**
         * Returns the singleton instance of AdsManager.
         *
         * @return The AdsManager instance.
         */
        fun getInstance(): AdsManager =
            instance ?: synchronized(this) {
                instance ?: throw AssertionError("You have to call initialize first")
            }

        fun initialize(
            application: Application,
            premiumManager: IPremiumManager
        ): AdsManager {
            if (instance != null) throw AssertionError("You already initialized me")
            return AdsManager(application, premiumManager).also { instance = it }
        }
    }
}



