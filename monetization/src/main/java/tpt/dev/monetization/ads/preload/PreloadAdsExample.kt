package tpt.dev.monetization.ads.preload

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import tpt.dev.monetization.ads.AdsManager

/**
 * Example implementation của Preload Ads Management
 * Đây là file mẫu để tham khảo cách sử dụng
 */

// ============================================================================
// EXAMPLE 1: Splash Screen với Preload
// ============================================================================
class ExampleSplashActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload tất cả ads cần thiết
        preloadAllAds()
        
        // Navigate sau 2 giây
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToHome()
        }, 2000)
    }
    
    private fun preloadAllAds() {
        // 1. Preload Interstitial ads với waterfall
        val interstitialKeys = listOf(
            "ca-app-pub-xxx/home-inter-high",
            "ca-app-pub-xxx/home-inter-medium",
            "ca-app-pub-xxx/home-inter-low"
        )
        
        adsManager.preloadInterstitialManagement.loadWithWaterfall(
            activity = this,
            adKeys = interstitialKeys,
            delayMs = 300L
        ) {
            Log.d("Splash", "All interstitials preloaded")
        }
        
        // 2. Enable backup interstitial
        adsManager.preloadInterstitialManagement.enableBackup(
            this,
            "ca-app-pub-xxx/backup-inter"
        )
        
        // 3. Preload Native ads
        val nativeKeys = listOf(
            "ca-app-pub-xxx/list-native-1",
            "ca-app-pub-xxx/list-native-2",
            "ca-app-pub-xxx/detail-native"
        )
        
        adsManager.preloadNativeManagement.loadWithWaterfall(
            activity = this,
            adKeys = nativeKeys,
            delayMs = 300L
        ) {
            Log.d("Splash", "All natives preloaded")
        }
        
        // 4. Enable backup native
        adsManager.preloadNativeManagement.enableBackup(
            this,
            "ca-app-pub-xxx/backup-native"
        )
        
        // 5. Preload Banner ads
        adsManager.preloadBannerManagement.loadAdaptiveBanner(
            activity = this,
            adKey = "ca-app-pub-xxx/home-banner"
        ) { adView ->
            Log.d("Splash", "Banner preloaded: ${adView != null}")
        }
    }
    
    private fun navigateToHome() {
        startActivity(Intent(this, ExampleHomeActivity::class.java))
        finish()
    }
}

// ============================================================================
// EXAMPLE 2: Home Screen với Banner và Interstitial
// ============================================================================
class ExampleHomeActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    private lateinit var bannerContainer: FrameLayout
    private lateinit var btnNavigate: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup views
        setupViews()
        
        // Show banner đã preload
        showBanner()
        
        // Preload ads cho màn hình tiếp theo
        preloadNextScreenAds()
        
        // Setup navigation button
        btnNavigate.setOnClickListener {
            navigateToDetailWithAd()
        }
    }
    
    private fun setupViews() {
        // bannerContainer = findViewById(R.id.banner_container)
        // btnNavigate = findViewById(R.id.btn_navigate)
    }
    
    private fun showBanner() {
        // Show banner đã preload từ splash
        val success = adsManager.preloadBannerManagement.showBannerOrBackup(
            adKey = "ca-app-pub-xxx/home-banner",
            container = bannerContainer,
            removeAfterShow = false
        )
        
        if (success) {
            Log.d("Home", "Banner shown successfully")
        } else {
            Log.e("Home", "Banner not available, loading now")
            // Load ngay nếu chưa có
            adsManager.preloadBannerManagement.loadAdaptiveBanner(
                this,
                "ca-app-pub-xxx/home-banner"
            ) { adView ->
                if (adView != null) {
                    adsManager.preloadBannerManagement.showBanner(
                        "ca-app-pub-xxx/home-banner",
                        bannerContainer
                    )
                }
            }
        }
    }
    
    private fun preloadNextScreenAds() {
        // Preload interstitial cho detail screen
        adsManager.preloadInterstitialManagement.load(
            activity = this,
            adKey = "ca-app-pub-xxx/detail-inter"
        ) {
            Log.d("Home", "Detail interstitial preloaded")
        }
        
        // Preload native cho detail screen
        adsManager.preloadNativeManagement.load(
            activity = this,
            adKey = "ca-app-pub-xxx/detail-native"
        ) { nativeAd ->
            Log.d("Home", "Detail native preloaded: ${nativeAd != null}")
        }
    }
    
    private fun navigateToDetailWithAd() {
        // Check nếu ad đã load
        if (adsManager.preloadInterstitialManagement.isLoaded("ca-app-pub-xxx/detail-inter")) {
            // Show ad trước khi navigate
            adsManager.preloadInterstitialManagement.show(
                activity = this,
                adKey = "ca-app-pub-xxx/detail-inter",
                reload = true,
                onAdShowed = {
                    Log.d("Home", "Interstitial showed")
                },
                onAdClosed = { success ->
                    Log.d("Home", "Interstitial closed: $success")
                    // Navigate regardless of ad result
                    navigateToDetail()
                }
            )
        } else {
            // Navigate trực tiếp nếu ad chưa load
            Log.w("Home", "Interstitial not ready, navigate directly")
            navigateToDetail()
        }
    }
    
    private fun navigateToDetail() {
        startActivity(Intent(this, ExampleDetailActivity::class.java))
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Destroy banner khi không dùng nữa
        adsManager.preloadBannerManagement.destroy("ca-app-pub-xxx/home-banner")
    }
}

// ============================================================================
// EXAMPLE 3: Detail Screen với Native Ad
// ============================================================================
class ExampleDetailActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    // private lateinit var nativeAdView: NativeAdView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Show native ad đã preload
        showNativeAd()
    }
    
    private fun showNativeAd() {
        // Get native ad từ pool
        val nativeAd = adsManager.preloadNativeManagement.getNativeAdOrBackup(
            adKey = "ca-app-pub-xxx/detail-native",
            removeAfterGet = true
        )
        
        if (nativeAd != null) {
            Log.d("Detail", "Native ad available")
            // Populate native ad view
            // populateNativeAdView(nativeAdView, nativeAd)
            
            // Preload lại cho lần sau
            adsManager.preloadNativeManagement.load(
                this,
                "ca-app-pub-xxx/detail-native"
            )
        } else {
            Log.e("Detail", "Native ad not available, loading now")
            // Load ngay nếu chưa có
            adsManager.preloadNativeManagement.load(
                activity = this,
                adKey = "ca-app-pub-xxx/detail-native"
            ) { loadedAd ->
                if (loadedAd != null) {
                    // populateNativeAdView(nativeAdView, loadedAd)
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Destroy native ad khi không dùng nữa
        adsManager.preloadNativeManagement.destroy("ca-app-pub-xxx/detail-native")
    }
}

// ============================================================================
// EXAMPLE 4: List Screen với Multiple Native Ads
// ============================================================================
class ExampleListActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    private val nativeAds = mutableListOf<Any>() // Mix of items and ads
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load multiple native ads
        loadMultipleNativeAds()
    }
    
    private fun loadMultipleNativeAds() {
        // Load 5 native ads cùng lúc
        adsManager.preloadNativeManagement.load(
            activity = this,
            adKey = "ca-app-pub-xxx/list-native",
            numberOfAds = 5
        ) { nativeAd ->
            if (nativeAd != null) {
                Log.d("List", "Native ad loaded")
                // Add to list
                nativeAds.add(nativeAd)
                // Update adapter
                // adapter.notifyDataSetChanged()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Destroy all native ads
        adsManager.preloadNativeManagement.clearAll()
    }
}

// ============================================================================
// EXAMPLE 5: Settings Screen với Collapsible Banner
// ============================================================================
class ExampleSettingsActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    private lateinit var bannerContainer: FrameLayout
    private var collapsibleRequestId: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load collapsible banner
        loadCollapsibleBanner()
    }
    
    private fun loadCollapsibleBanner() {
        adsManager.preloadBannerManagement.loadCollapsibleBanner(
            activity = this,
            adKey = "ca-app-pub-xxx/settings-banner",
            placement = BannerPlacement.BOTTOM,
            requestId = null
        ) { adView, requestId ->
            if (adView != null) {
                Log.d("Settings", "Collapsible banner loaded: $requestId")
                collapsibleRequestId = requestId
                
                // Show banner
                adsManager.preloadBannerManagement.showBanner(
                    adKey = "ca-app-pub-xxx/settings-banner",
                    container = bannerContainer,
                    removeAfterShow = false
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        adsManager.preloadBannerManagement.destroy("ca-app-pub-xxx/settings-banner")
    }
}

// ============================================================================
// EXAMPLE 6: Advanced - Custom Waterfall Strategy
// ============================================================================
class ExampleAdvancedActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    
    // Define ad priority based on eCPM
    private val adPriority = listOf(
        "ca-app-pub-xxx/high-ecpm-inter",    // $10 eCPM
        "ca-app-pub-xxx/medium-ecpm-inter",  // $5 eCPM
        "ca-app-pub-xxx/low-ecpm-inter",     // $2 eCPM
        "ca-app-pub-xxx/backup-inter"        // Backup
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load all ads theo priority
        loadAdsWithPriority()
    }
    
    private fun loadAdsWithPriority() {
        adsManager.preloadInterstitialManagement.loadWithWaterfall(
            activity = this,
            adKeys = adPriority,
            delayMs = 300L
        ) {
            Log.d("Advanced", "All ads loaded with priority")
            // Show pool statistics
            showPoolStatistics()
        }
    }
    
    private fun showPoolStatistics() {
        val loadedCount = adPriority.count { 
            adsManager.preloadInterstitialManagement.isLoaded(it) 
        }
        
        Log.d("Advanced", """
            Pool Statistics:
            - Total ads: ${adPriority.size}
            - Loaded: $loadedCount
            - Success rate: ${(loadedCount * 100 / adPriority.size)}%
        """.trimIndent())
    }
    
    fun showFirstAvailableAd() {
        // Tìm ad đầu tiên available theo priority
        for (adKey in adPriority) {
            if (adsManager.preloadInterstitialManagement.isLoaded(adKey)) {
                Log.d("Advanced", "Showing ad: $adKey")
                adsManager.preloadInterstitialManagement.show(
                    activity = this,
                    adKey = adKey,
                    reload = true,
                    onAdClosed = { success ->
                        Log.d("Advanced", "Ad closed: $success")
                    }
                )
                return
            }
        }
        
        Log.w("Advanced", "No ads available")
    }
}

// ============================================================================
// EXAMPLE 7: Memory Management Best Practices
// ============================================================================
class ExampleMemoryManagementActivity : AppCompatActivity() {
    
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load ads
        loadAds()
    }
    
    private fun loadAds() {
        // Load với limit
        val maxNativeAds = 3
        adsManager.preloadNativeManagement.load(
            this,
            "ca-app-pub-xxx/native",
            numberOfAds = maxNativeAds
        )
    }
    
    override fun onPause() {
        super.onPause()
        // Clear ads khi pause để giải phóng memory
        if (isFinishing) {
            clearAllAds()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure cleanup
        clearAllAds()
    }
    
    private fun clearAllAds() {
        adsManager.preloadNativeManagement.clearAll()
        adsManager.preloadBannerManagement.clearAll()
        // Note: Không clear interstitial pool vì có thể dùng lại
    }
}
