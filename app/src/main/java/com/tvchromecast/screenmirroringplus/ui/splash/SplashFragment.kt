package com.tvchromecast.screenmirroringplus.ui.splash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.tvchromecast.screenmirroringplus.databinding.FragmentSplashBinding
import com.tvchromecast.screenmirroringplus.ui.language_activity.LanguageActivity
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.navigate
import hoang.dqm.codebase.firebase.AppRemoteConfig
import hoang.dqm.codebase.service.session.isFirst
import hoang.dqm.codebase.service.session.saveFirst
import hoang.dqm.codebase.ui.features.splash.BaseSplashFragment
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.premium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random


class SplashFragment : BaseSplashFragment<FragmentSplashBinding, SplashViewModel>() {
    private var job: Job? = null
    private var isPaused = false
    private var isInternetAvailable = true
//    private val adsManager by lazy { AdsManager.getInstance() }
    private var hasNavigated = false
    private var isOpeningLanguage = false
    private val openLanguageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            releaseLog(
                "SplashFragment.languageResult: resultCode=${result.resultCode}, hasNavigated=$hasNavigated"
            )

            isOpeningLanguage = false

            if (hasNavigated) {
                releaseLog("SplashFragment.languageResult: ignored because already navigated")
                return@registerForActivityResult
            }

            if (result.resultCode == Activity.RESULT_OK) {
//                val goToIntro =
//                    result.data?.getBooleanExtra("go_to_intro", false) ?: false
//
//                if (goToIntro) {
//                    hasNavigated = true
//                    navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment, isPop = true)
//                }
                releaseLog("SplashFragment.languageResult: navigate introFragment")
                navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment)
            } else {
                releaseLog("SplashFragment.languageResult: no navigation for resultCode=${result.resultCode}")
            }
    }

    override fun onAttach(context: Context) {
        releaseLog("SplashFragment.onAttach")
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        releaseLog("SplashFragment.onCreate: savedState=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        releaseLog("SplashFragment.onViewCreated: before super")
        super.onViewCreated(view, savedInstanceState)
        releaseLog("SplashFragment.onViewCreated: after super")
    }

    override fun openHome() {
        releaseLog("SplashFragment.openHome")
        navigateToNextScreen()
//        if (AppMonetization.premium.isSubscribed()) {
//            navigateToNextScreen()
//            return
//        }
//
//        val splashAdKey = getString(R.string.ads_inter_splash)
//
//        if (!adsManager.preloadInterstitialManagement.isLoaded(splashAdKey)) {
//            var waitCount = 0
//            val maxWait = 50
//
//            CoroutineScope(Dispatchers.Main).launch {
//                while (waitCount < maxWait && !adsManager.preloadInterstitialManagement.isLoaded(
//                        splashAdKey
//                    )
//                ) {
//                    delay(100)
//                    waitCount++
//                }
//
//                if (adsManager.preloadInterstitialManagement.isLoaded(splashAdKey)) {
//                } else {
//                }
//
//                showSplashAdAndNavigate()
//            }
//        } else {
//            showSplashAdAndNavigate()
//        }
    }

    private fun showSplashAdAndNavigate() {
        releaseLog("SplashFragment.showSplashAdAndNavigate")
        navigateToNextScreen()
//        if (!adsManager.isGlobalAdsEnabled()) {
//            navigateToNextScreen()
//            return
//        }
//
//        if (!adsManager.isInterSplashEnabled()) {
//            navigateToNextScreen()
//            return
//        }
//
//        adsManager.preloadInterstitialManagement.show(
//            requireActivity(), getString(R.string.ads_inter_splash), true, null, onAdClosed = {
//                navigateToNextScreen()
//            })
    }

    private fun navigateToNextScreen() {
        releaseLog(
            "SplashFragment.navigateToNextScreen: hasNavigated=$hasNavigated, isOpeningLanguage=$isOpeningLanguage, isFirst=${isFirst()}"
        )
        if (hasNavigated || isOpeningLanguage) {
            releaseLog("SplashFragment.navigateToNextScreen: blocked by navigation guard")
            return
        }

//        adsManager.markSplashCompleted()

        if (isFirst()) {
            releaseLog("SplashFragment.navigateToNextScreen: opening LanguageActivity")
            isOpeningLanguage = true
            saveFirst(false)
            val intent = Intent(requireContext(), LanguageActivity::class.java).apply {
                putExtra("isFromSplash", true)
            }

            openLanguageLauncher.launch(intent)

        } else {
            hasNavigated = true

            val isSubscribed = AppMonetization.premium.isSubscribed()
            releaseLog("SplashFragment.navigateToNextScreen: isSubscribed=$isSubscribed")
            if (isSubscribed) {
                releaseLog("SplashFragment.navigateToNextScreen: navigate homeFragment subscribed")
                navigate(com.tvchromecast.screenmirroringplus.R.id.homeFragment, isPop = true)
            } else {
                val bundle = Bundle().apply {
                    putBoolean("isFromSplash", true)
                }

                releaseLog("SplashFragment.navigateToNextScreen: navigate homeFragment with isFromSplash")
                navigate(com.tvchromecast.screenmirroringplus.R.id.homeFragment, bundle, isPop = true)
            }
        }
    }

    override fun initView() {
        releaseLog("SplashFragment.initView: before super")
        super.initView()
        releaseLog("SplashFragment.initView: after super")
//        AppMonetization.premium.updateSubscribedState(true)
        if (isFirst()) {
            binding.tvLoading.text =
                resources.getStringArray(R.array.text_first_time).random()
            releaseLog("SplashFragment.initView: first user text selected")
        } else {
            binding.tvLoading.text = getString(R.string.loading_text)
            releaseLog("SplashFragment.initView: normal loading text selected")
        }
        setupLoading()
        checkConsentShow()
    }

    override fun isInternetConnected(isInternet: Boolean) {
        releaseLog("SplashFragment.isInternetConnected: isInternet=$isInternet")
        isInternetAvailable = isInternet
        isPaused = !isInternet
    }

    override fun onFetchConfigSuccess() {
        releaseLog("SplashFragment.onFetchConfigSuccess")
        Log.d("SplashFragment", "=== Config fetched, updating to 80% ===")
        job?.cancel()

        loadRemoteConfigVariables()

        // Nếu đã subscribe, update lên 100% luôn
        val isSubscribed = AppMonetization.premium.isSubscribed()
        releaseLog("SplashFragment.onFetchConfigSuccess: isSubscribed=$isSubscribed")
        if (isSubscribed) {
            updateUI(100)
        } else {
            updateUI(80)
        }
    }


    override fun isUserSubscribed(): Boolean {
        val isSubscribed = AppMonetization.premium.isSubscribed()
        releaseLog("SplashFragment.isUserSubscribed: $isSubscribed")
        return isSubscribed
    }

    fun loadRemoteConfigVariables() {
        releaseLog("SplashFragment.loadRemoteConfigVariables")
        // Load timing configs
        val timeDelayInterSplashVsOpen = AppRemoteConfig.getLongValue(
            AppRemoteConfig.TIME_DELAY_INTER_SPLASH_OPEN, 20000
        )
        val timeDelayInter = AppRemoteConfig.getLongValue(
            AppRemoteConfig.TIME_DELAY_SHOW_INTER, 20000
        )

        val isShowAdsOpen = AppRemoteConfig.getBooleanValue(
            AppRemoteConfig.IS_SHOW_AD_OPEN, true
        )
        val isShowAdsApp = AppRemoteConfig.getBooleanValue(
            AppRemoteConfig.IS_SHOW_ADS_APP, true
        )
        val isShowInterAfterSplash = AppRemoteConfig.getBooleanValue(
            AppRemoteConfig.IS_SHOW_INTER_SPLASH, true
        )
        releaseLog(
            "SplashFragment.remoteConfig: openDelay=$timeDelayInterSplashVsOpen, interDelay=$timeDelayInter, showOpen=$isShowAdsOpen, showAds=$isShowAdsApp, showInterSplash=$isShowInterAfterSplash"
        )

//        // Áp dụng cấu hình timing
//        AppMonetization.ads.updateTimeIntervalShowInterVsOpen(timeDelayInterSplashVsOpen.milliseconds)
//        AppMonetization.ads.updateTimeIntervalShowInterstitialAd(timeDelayInter.milliseconds)
//
//        // Áp dụng cấu hình enable/disable
//        AppMonetization.ads.setGlobalAdsEnabled(isShowAdsApp)
//        AppMonetization.ads.setAppOpenAdEnabled(isShowAdsOpen)
//        AppMonetization.ads.setInterSplashEnabled(isShowInterAfterSplash)

        // Log để debug
        Log.d(
            "RemoteConfig", """
            ===== ADS CONFIG =====
            Time Inter ↔ Open: ${timeDelayInterSplashVsOpen}ms
            Time Inter Interval: ${timeDelayInter}ms
            Show Open Ad: $isShowAdsOpen
            Show All Ads: $isShowAdsApp
            Show Inter Splash: $isShowInterAfterSplash
            ======================
        """.trimIndent()
        )
    }

    /**
     * Override để preload ads
     */
    override fun onPreloadAds(activity: Activity, onComplete: () -> Unit) {
        // Ads disabled for now: complete splash preload immediately.
        releaseLog("SplashFragment.onPreloadAds: ads disabled, complete immediately")
        updateUI(100)
        onComplete()
    }

    override fun onAdsPreloadComplete() {
        releaseLog("SplashFragment.onAdsPreloadComplete")
        updateUI(100)
    }

    private fun getInterstitialAdKeys(): List<String> {
        return listOf(
//            getString(R.string.ads_inter_splash),
//            getString(R.string.full_back)
        )
    }

    private fun getBackupInterstitialKey(): String {
        return "" // "ca-app-pub-xxx/backup-inter"
    }

    private fun getNativeAdKeys(): List<String> {
        return listOf(
//            getString(R.string.ads_native_language_id),
//            getString(R.string.ads_native_language_click),
//            getString(R.string.ads_native_intro1),
//            getString(R.string.ads_native_intro2),
//            getString(R.string.ads_native_intro_full_id),
//            getString(R.string.ads_native_home),
//            getString(R.string.ads_collapse_channel),
        )
    }

    private fun getBackupNativeKey(): String {
        return "" // "ca-app-pub-xxx/backup-native"
    }

    private fun getBannerAdKeys(): List<String> {
        return listOf(
        )
    }

    private fun setupLoading() {
        releaseLog("SplashFragment.setupLoading")
        binding.apply {
//            tvPercentLoading.visible()
            progressBar.max = 100
            progressBar.progress = 0
            updateUI(0)
        }
        startLoading()
    }

    private suspend fun waitIfPaused() {
        while (isPaused) {
            releaseLog("SplashFragment.waitIfPaused: paused, waiting")
            delay(500)
        }
    }

    private fun startLoading() {
        releaseLog("SplashFragment.startLoading")
        job = CoroutineScope(Dispatchers.Main).launch {
            delay(1000L)
            var progress = 0

            while (progress < 60) {
                waitIfPaused()
                progress += 1
                updateUI(progress)
                delay(Random.nextLong(50, 200))
            }

            while (progress < 70) {
                waitIfPaused()
                progress += 1
                updateUI(progress)
                delay(Random.nextLong(500, 1200))
            }

            while (progress < 80) {
                waitIfPaused()
                progress += 1
                updateUI(progress)
                delay(Random.nextLong(1000, 1500))
            }
        }
    }

    private fun updateUI(progress: Int) {
        if (!isAdded || isDetached || view == null) {
            releaseLog(
                "SplashFragment.updateUI: skipped progress=$progress, isAdded=$isAdded, isDetached=$isDetached, viewNull=${view == null}"
            )
            return
        }
        if (progress == 0 || progress == 60 || progress == 70 || progress == 80 || progress == 100) {
            releaseLog("SplashFragment.updateUI: progress=$progress")
        }
        binding.progressBar.setProgressCompat(progress, true)
        binding.tvPercentLoading.text = "$progress%"
    }

    override fun onResume() {
        releaseLog("SplashFragment.onResume: before super")
        super.onResume()
        releaseLog("SplashFragment.onResume: after super")
    }

    override fun onPause() {
        releaseLog("SplashFragment.onPause")
        super.onPause()
    }

    override fun onDestroyView() {
        releaseLog("SplashFragment.onDestroyView")
        super.onDestroyView()
    }

    override fun onDestroy() {
        releaseLog("SplashFragment.onDestroy")
        super.onDestroy()
        job?.cancel()
    }

    private fun checkConsentShow() {
        releaseLog("SplashFragment.checkConsentShow")
        view?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
            isPaused = if (!hasFocus) {
                true
            } else {
                !isInternetAvailable
            }
            releaseLog("SplashFragment.windowFocusChanged: hasFocus=$hasFocus, isPaused=$isPaused")
        }
    }

    private fun releaseLog(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "TVCastReleaseLog"
    }
}
