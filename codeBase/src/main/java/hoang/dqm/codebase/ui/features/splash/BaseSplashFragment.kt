package hoang.dqm.codebase.ui.features.splash

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import hoang.dqm.codebase.event.subscribeEventNetwork
import hoang.dqm.codebase.firebase.AppRemoteConfig
import hoang.dqm.codebase.service.session.isFirstSplash
import hoang.dqm.codebase.service.session.setFirstSplash
import hoang.dqm.codebase.utils.isNetworkAvailable
import kotlinx.coroutines.launch
import tpt.dev.monetization.ads.AdsManager

abstract class BaseSplashFragment<VB : ViewBinding, VM : BaseViewModel> :
    BaseFragment<VB, VM>() {

    private val adsManager by lazy { AdsManager.getInstance() }
    private var isConfigFetched = false
    private var isAdsPreloaded = false

    override fun initView() {
        // LUÔN gather consent và fetch config, không phân biệt lần đầu hay không
        // Vì ads pool là in-memory, khi app bị kill thì mất hết
        gatherConsentAndFetch()
        
        // Chỉ set first splash flag để tracking
        if (isFirstSplash()) {
            setFirstSplash(false)
        }
    }
    
    private fun gatherConsentAndFetch() {
        try {
            val fragmentActivity = requireActivity()

            adsManager.gatherConsent(
                activity = fragmentActivity,
                listener = object : AdsManager.OnGatherConsentListener {
                    override fun onCompletion(error: String?) {
                        if (error != null) {
                        } else {
                        }
                        
                        val isOnline = isNetworkAvailable()
                        isInternetConnected(isOnline && isAdded)
                        
                        if (isOnline && isAdded && !isConfigFetched) {
                            fetch()
                        } else {
                        }
                        
                        // Subscribe to network events cho các thay đổi sau này
                        subscribeEventNetwork { online ->
                            isInternetConnected(online && isAdded)
                            if (online && isAdded && !isConfigFetched) {
                                Log.d("BaseSplash", "Network online, fetching config...")
                                fetch()
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("BaseSplash", "Error gathering consent: ${e.message}", e)
            // Fallback: vẫn tiếp tục fetch config
            val isOnline = isNetworkAvailable()
            isInternetConnected(isOnline && isAdded)
            if (isOnline && isAdded && !isConfigFetched) {
                fetch()
            }
            subscribeEventNetwork { online ->
                isInternetConnected(online && isAdded)
                if (online && isAdded && !isConfigFetched) {
                    fetch()
                }
            }
        }
    }

    override fun initListener() {
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        Log.d("BaseSplash", "=== onResume ===")
        subscribeEventNetwork { online ->
            Log.d("BaseSplash", "onResume network event: online=$online, isAdded=$isAdded")
            if (online && isAdded) {
                checkAndOpenHome()
            }
        }
    }

    private fun fetch() {
        Log.d("BaseSplash", "=== Fetching remote config ===")
        activity?.let {
            AppRemoteConfig.fetchConfig(
                callback = {
                    lifecycleScope.launch {
                        Log.d("BaseSplash", "✓ Config fetched successfully")
                        isConfigFetched = true
                        onFetchConfigSuccess()
                        
                        // Kiểm tra subscription status
                        if (isUserSubscribed()) {
                            Log.d("BaseSplash", "✓ User is subscribed, skip ads preload")
                            isAdsPreloaded = true
                            checkAndOpenHome()
                        } else {
                            Log.d("BaseSplash", "User not subscribed, starting preload ads...")
                            preloadAds()
                        }
                    }
                })
        } ?: run {
            Log.e("BaseSplash", "!!! Activity is null, cannot fetch config !!!")
        }
    }

    /**
     * Preload tất cả ads cần thiết
     */
    private fun preloadAds() {
        try {
            val fragmentActivity = requireActivity()
            Log.d("BaseSplash", "Starting preload ads...")
            
            // Callback khi preload xong
            val onPreloadComplete = {
                Log.d("BaseSplash", "Ads preloaded successfully")
                isAdsPreloaded = true
                onAdsPreloadComplete()
                checkAndOpenHome()
            }
            
            // Gọi hàm preload từ subclass
            onPreloadAds(fragmentActivity, onPreloadComplete)
        } catch (e: Exception) {
            Log.e("BaseSplash", "Error preloading ads: ${e.message}")
            // Nếu có lỗi, vẫn mark as complete để không block user
            isAdsPreloaded = true
            checkAndOpenHome()
        }
    }

    /**
     * Check và open home khi cả config và ads đều ready
     */
    private fun checkAndOpenHome() {
        Log.d("BaseSplash", "checkAndOpenHome: isConfigFetched=$isConfigFetched, isAdsPreloaded=$isAdsPreloaded")
        if (isConfigFetched && isAdsPreloaded) {
            Log.d("BaseSplash", "=== Config and Ads ready, opening home ===")
            lifecycleScope.launch {
                openHome()
            }
        } else {
            Log.d("BaseSplash", "Not ready yet: config=$isConfigFetched, ads=$isAdsPreloaded")
        }
    }

    /**
     * Check xem user đã subscribe premium chưa
     * Override nếu cần custom logic
     */
    protected open fun isUserSubscribed(): Boolean {
        return false // Default: chưa subscribe
    }

    /**
     * Override để preload ads theo nhu cầu của từng app
     * Default: không preload gì
     */
    protected open fun onPreloadAds(activity: android.app.Activity, onComplete: () -> Unit) {
        // Default: mark as complete immediately
        onComplete()
    }

    /**
     * Callback khi ads preload xong
     */
    protected open fun onAdsPreloadComplete() {
        // Override nếu cần
    }

    abstract fun openHome()
    abstract fun isInternetConnected(isInternet: Boolean = true)
    abstract fun onFetchConfigSuccess()

}
