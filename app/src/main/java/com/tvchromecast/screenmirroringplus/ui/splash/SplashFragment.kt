package com.tvchromecast.screenmirroringplus.ui.splash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import hoang.dqm.codebase.utils.ads
import hoang.dqm.codebase.utils.premium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.AdsManager
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds


class SplashFragment : BaseSplashFragment<FragmentSplashBinding, SplashViewModel>() {
    private var job: Job? = null
    private var isPaused = false
    private var isInternetAvailable = true
    private val adsManager by lazy { AdsManager.getInstance() }
    private var hasNavigated = false
    private var isOpeningLanguage = false
    private val openLanguageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            isOpeningLanguage = false

            if (hasNavigated) return@registerForActivityResult

            if (result.resultCode == Activity.RESULT_OK) {
//                val goToIntro =
//                    result.data?.getBooleanExtra("go_to_intro", false) ?: false
//
//                if (goToIntro) {
//                    hasNavigated = true
//                    navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment, isPop = true)
//                }
                navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment)
            }
        }

    override fun openHome() {
        if (AppMonetization.premium.isSubscribed()) {
            navigateToNextScreen()
            return
        }

        val splashAdKey = getString(R.string.ads_inter_splash)

        if (!adsManager.preloadInterstitialManagement.isLoaded(splashAdKey)) {
            var waitCount = 0
            val maxWait = 50

            CoroutineScope(Dispatchers.Main).launch {
                while (waitCount < maxWait && !adsManager.preloadInterstitialManagement.isLoaded(
                        splashAdKey
                    )
                ) {
                    delay(100)
                    waitCount++
                }

                if (adsManager.preloadInterstitialManagement.isLoaded(splashAdKey)) {
                } else {
                }

                showSplashAdAndNavigate()
            }
        } else {
            showSplashAdAndNavigate()
        }
    }

    private fun showSplashAdAndNavigate() {
        if (!adsManager.isGlobalAdsEnabled()) {
            navigateToNextScreen()
            return
        }

        if (!adsManager.isInterSplashEnabled()) {
            navigateToNextScreen()
            return
        }

        adsManager.preloadInterstitialManagement.show(
            requireActivity(), getString(R.string.ads_inter_splash), true, null, onAdClosed = {
                navigateToNextScreen()
            })
    }

    private fun navigateToNextScreen() {
        if (hasNavigated || isOpeningLanguage) return

        adsManager.markSplashCompleted()

        if (isFirst()) {
            isOpeningLanguage = true
            saveFirst(false)
            val intent = Intent(requireContext(), LanguageActivity::class.java).apply {
                putExtra("isFromSplash", true)
            }

            openLanguageLauncher.launch(intent)

        } else {
            hasNavigated = true

            if (AppMonetization.premium.isSubscribed()) {
                navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment, isPop = true)
            } else {
                val bundle = Bundle().apply {
                    putBoolean("isFromSplash", true)
                }

                navigate(com.tvchromecast.screenmirroringplus.R.id.introFragment, bundle, isPop = true)
            }
        }
    }

    override fun initView() {
        super.initView()
//        AppMonetization.premium.updateSubscribedState(true)
        if (isFirst()) {
            binding.tvLoading.text =
                resources.getStringArray(R.array.text_first_time).random()
        } else {
            binding.tvLoading.text = getString(R.string.loading_text)
        }
        setupLoading()
        checkConsentShow()
    }

    override fun isInternetConnected(isInternet: Boolean) {
        isInternetAvailable = isInternet
        isPaused = !isInternet
    }

    override fun onFetchConfigSuccess() {
        Log.d("SplashFragment", "=== Config fetched, updating to 80% ===")
        job?.cancel()

        loadRemoteConfigVariables()

        // Nếu đã subscribe, update lên 100% luôn
        if (AppMonetization.premium.isSubscribed()) {
            updateUI(100)
        } else {
            updateUI(80)
        }
    }


    override fun isUserSubscribed(): Boolean {
        val isSubscribed = AppMonetization.premium.isSubscribed()
        return isSubscribed
    }

    fun loadRemoteConfigVariables() {
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

        // Áp dụng cấu hình timing
        AppMonetization.ads.updateTimeIntervalShowInterVsOpen(timeDelayInterSplashVsOpen.milliseconds)
        AppMonetization.ads.updateTimeIntervalShowInterstitialAd(timeDelayInter.milliseconds)

        // Áp dụng cấu hình enable/disable
        AppMonetization.ads.setGlobalAdsEnabled(isShowAdsApp)
        AppMonetization.ads.setAppOpenAdEnabled(isShowAdsOpen)
        AppMonetization.ads.setInterSplashEnabled(isShowInterAfterSplash)

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
        updateUI(85)

        var loadedCount = 0
        var isCompleted = false
        val totalAds = 1
        CoroutineScope(Dispatchers.Main).launch {
            delay(10000L)
            if (!isCompleted) {
                isCompleted = true
                onComplete()
            }
        }

        val checkComplete = {
            loadedCount++
            val progress = 85 + (loadedCount * 5) // 85 -> 100
            updateUI(progress)

            if (loadedCount >= totalAds && !isCompleted) {
                isCompleted = true
                onComplete()
            }
        }

        val interstitialKeys = getInterstitialAdKeys()
        if (interstitialKeys.isNotEmpty()) {
            adsManager.preloadInterstitialManagement.loadWithWaterfall(
                activity = activity,
                adKeys = interstitialKeys,
                delayMs = 300L
            ) {
                checkComplete()
            }

            // Enable backup
            val backupKey = getBackupInterstitialKey()
            if (backupKey.isNotEmpty()) {
                adsManager.preloadInterstitialManagement.enableBackup(
                    activity,
                    backupKey
                )
            }
        } else {
            checkComplete()
        }

        // 2. Preload Native ads với waterfall
        val nativeKeys = getNativeAdKeys()
        if (nativeKeys.isNotEmpty()) {
            adsManager.preloadNativeManagement.loadWithWaterfall(
                activity = activity,
                adKeys = nativeKeys,
                delayMs = 300L
            ) {
                checkComplete()
            }

            // Enable backup
            val backupKey = getBackupNativeKey()
            if (backupKey.isNotEmpty()) {
                adsManager.preloadNativeManagement.enableBackup(
                    activity,
                    backupKey
                )
            }
        } else {
            checkComplete()
        }

        // 3. Preload Banner ads
        val bannerKeys = getBannerAdKeys()
        if (bannerKeys.isNotEmpty()) {
            adsManager.preloadBannerManagement.loadWithWaterfall(
                activity = activity,
                adKeys = bannerKeys,
                delayMs = 300L
            ) {
                checkComplete()
            }
        } else {
            checkComplete()
        }
    }

    override fun onAdsPreloadComplete() {
        updateUI(100)
    }

    private fun getInterstitialAdKeys(): List<String> {
        return listOf(
            getString(R.string.ads_inter_splash),
            getString(R.string.full_back)
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
            delay(500)
        }
    }

    private fun startLoading() {
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
        if (!isAdded || isDetached || view == null) return
        binding.progressBar.setProgressCompat(progress, true)
        binding.tvPercentLoading.text = "$progress%"
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }

    private fun checkConsentShow() {
        view?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
            isPaused = if (!hasFocus) {
                true
            } else {
                !isInternetAvailable
            }
        }
    }
}