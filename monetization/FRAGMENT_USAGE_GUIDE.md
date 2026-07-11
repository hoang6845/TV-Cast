# Fragment Usage Guide - Show Ads từ Fragment

## 📖 Tổng quan

Extension functions để show ads trực tiếp từ Fragment mà không cần truyền Activity.

## 🚀 Quick Start

### Import
```kotlin
import tpt.dev.monetization.ads.preload.*
```

## 📱 Sử dụng trong Fragment

### 1. Show Interstitial từ Fragment

```kotlin
class HomeFragment : Fragment() {
    
    fun navigateWithAd() {
        // Show interstitial trực tiếp từ fragment
        showInterstitialAd(
            adKey = "ca-app-pub-xxx/inter",
            reload = true,
            onAdShowed = {
                Log.d("HomeFragment", "Ad showed")
            },
            onAdClosed = { success ->
                Log.d("HomeFragment", "Ad closed: $success")
                // Navigate
                findNavController().navigate(R.id.detailFragment)
            }
        )
    }
}
```

### 2. Load Interstitial từ Fragment

```kotlin
class HomeFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Load interstitial
        loadInterstitialAd("ca-app-pub-xxx/inter") {
            Log.d("HomeFragment", "Interstitial loaded")
        }
        
        // Hoặc load nhiều ads với waterfall
        loadInterstitialAdsWithWaterfall(
            adKeys = listOf(
                "ca-app-pub-xxx/inter-1",
                "ca-app-pub-xxx/inter-2"
            ),
            delayMs = 300L
        ) {
            Log.d("HomeFragment", "All interstitials loaded")
        }
    }
    
    // Check ad đã load chưa
    fun checkAd() {
        if (isInterstitialAdLoaded("ca-app-pub-xxx/inter")) {
            // Ad ready
        }
    }
}
```

### 3. Show Native từ Fragment

```kotlin
class ListFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Load native ad
        loadNativeAd(
            adKey = "ca-app-pub-xxx/native",
            numberOfAds = 1
        ) { nativeAd ->
            if (nativeAd != null) {
                // Populate native ad view
                nativeAdView.setNativeAd(nativeAd)
            }
        }
        
        // Hoặc get từ pool (đã preload)
        val nativeAd = getNativeAdOrBackup(
            adKey = "ca-app-pub-xxx/native",
            removeAfterGet = true
        )
        
        if (nativeAd != null) {
            nativeAdView.setNativeAd(nativeAd)
        }
    }
    
    // Load nhiều native ads
    fun loadMultipleNatives() {
        loadNativeAdsWithWaterfall(
            adKeys = listOf(
                "ca-app-pub-xxx/native-1",
                "ca-app-pub-xxx/native-2"
            ),
            delayMs = 300L
        ) {
            Log.d("ListFragment", "All natives loaded")
        }
    }
    
    // Check native ad
    fun checkNative() {
        if (isNativeAdLoaded("ca-app-pub-xxx/native")) {
            // Native ready
        }
    }
}
```

### 4. Show Banner từ Fragment

```kotlin
class HomeFragment : Fragment() {
    
    private lateinit var bannerContainer: FrameLayout
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        bannerContainer = view.findViewById(R.id.banner_container)
        
        // Load adaptive banner
        loadAdaptiveBanner("ca-app-pub-xxx/banner") { adView ->
            if (adView != null) {
                Log.d("HomeFragment", "Banner loaded")
            }
        }
        
        // Show banner đã preload
        val success = showBannerOrBackup(
            adKey = "ca-app-pub-xxx/banner",
            container = bannerContainer,
            removeAfterShow = false
        )
        
        if (success) {
            Log.d("HomeFragment", "Banner shown")
        }
    }
    
    // Load collapsible banner
    fun loadCollapsible() {
        loadCollapsibleBanner(
            adKey = "ca-app-pub-xxx/banner",
            placement = BannerPlacement.BOTTOM,
            requestId = null
        ) { adView, requestId ->
            if (adView != null) {
                Log.d("HomeFragment", "Collapsible loaded: $requestId")
            }
        }
    }
    
    // Check banner
    fun checkBanner() {
        if (isBannerAdLoaded("ca-app-pub-xxx/banner")) {
            // Banner ready
        }
    }
}
```

### 5. Enable Backup từ Fragment

```kotlin
class SplashFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Enable backup ads
        enableBackupInterstitial("ca-app-pub-xxx/backup-inter")
        enableBackupNative("ca-app-pub-xxx/backup-native")
        enableBackupBanner("ca-app-pub-xxx/backup-banner")
    }
}
```

## 📋 Complete Example

### SplashFragment với Preload

```kotlin
class SplashFragment : BaseSplashFragment<FragmentSplashBinding, SplashViewModel>() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Preload tất cả ads
        preloadAllAds()
    }
    
    private fun preloadAllAds() {
        // 1. Load interstitials với waterfall
        loadInterstitialAdsWithWaterfall(
            adKeys = listOf(
                "ca-app-pub-xxx/inter-1",
                "ca-app-pub-xxx/inter-2"
            ),
            delayMs = 300L
        ) {
            Log.d("Splash", "Interstitials loaded")
        }
        
        // 2. Enable backup
        enableBackupInterstitial("ca-app-pub-xxx/backup-inter")
        
        // 3. Load natives
        loadNativeAdsWithWaterfall(
            adKeys = listOf(
                "ca-app-pub-xxx/native-1",
                "ca-app-pub-xxx/native-2"
            ),
            delayMs = 300L
        ) {
            Log.d("Splash", "Natives loaded")
        }
        
        // 4. Enable backup native
        enableBackupNative("ca-app-pub-xxx/backup-native")
        
        // 5. Load banner
        loadAdaptiveBanner("ca-app-pub-xxx/banner") {
            Log.d("Splash", "Banner loaded")
        }
    }
    
    override fun openHome() {
        // Navigate to home
        findNavController().navigate(R.id.homeFragment)
    }
}
```

### HomeFragment với Show Ads

```kotlin
class HomeFragment : Fragment() {
    
    private lateinit var bannerContainer: FrameLayout
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        bannerContainer = view.findViewById(R.id.banner_container)
        
        // Show banner đã preload
        showBannerOrBackup(
            adKey = "ca-app-pub-xxx/banner",
            container = bannerContainer
        )
        
        // Setup button
        view.findViewById<Button>(R.id.btn_next).setOnClickListener {
            navigateWithAd()
        }
    }
    
    private fun navigateWithAd() {
        // Show interstitial trước khi navigate
        showInterstitialAd(
            adKey = "ca-app-pub-xxx/inter",
            reload = true,
            onAdClosed = { success ->
                // Navigate regardless of ad result
                findNavController().navigate(R.id.detailFragment)
            }
        )
    }
}
```

### DetailFragment với Native Ad

```kotlin
class DetailFragment : Fragment() {
    
    private lateinit var nativeAdView: NativeAdView
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        nativeAdView = view.findViewById(R.id.native_ad_view)
        
        // Get native ad đã preload
        showNativeAd()
    }
    
    private fun showNativeAd() {
        val nativeAd = getNativeAdOrBackup(
            adKey = "ca-app-pub-xxx/native",
            removeAfterGet = true
        )
        
        if (nativeAd != null) {
            // Populate native ad view
            nativeAdView.setNativeAd(nativeAd)
            
            // Preload lại cho lần sau
            loadNativeAd("ca-app-pub-xxx/native")
        } else {
            // Load nếu chưa có
            loadNativeAd("ca-app-pub-xxx/native") { ad ->
                if (ad != null) {
                    nativeAdView.setNativeAd(ad)
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Native ad sẽ tự destroy khi view destroy
    }
}
```

## 🎯 Best Practices

### 1. Preload ở Splash
```kotlin
class SplashFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Preload tất cả ads cần thiết
        preloadAllAds()
    }
}
```

### 2. Show ở Home
```kotlin
class HomeFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Show ads đã preload
        showBannerOrBackup("banner-key", bannerContainer)
    }
}
```

### 3. Check trước khi show
```kotlin
fun showAdIfReady() {
    if (isInterstitialAdLoaded("ad-key")) {
        showInterstitialAd("ad-key") { success ->
            // Handle
        }
    } else {
        // Ad not ready, skip
    }
}
```

### 4. Handle Fragment lifecycle
```kotlin
class MyFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Load ads
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Ads sẽ tự cleanup
    }
}
```

## ⚠️ Important Notes

### 1. Fragment phải attached
```kotlin
// Extension functions sẽ check activity != null
// Nếu fragment chưa attached, callback sẽ được gọi với null/false
```

### 2. Lifecycle aware
```kotlin
// Chỉ load/show ads khi fragment visible
override fun onResume() {
    super.onResume()
    if (isAdded && !isDetached) {
        // Safe to show ads
    }
}
```

### 3. Memory management
```kotlin
// Native ads và banners sẽ tự cleanup khi fragment destroy
// Không cần manual cleanup
```

## 📊 API Reference

### Interstitial
```kotlin
// Show
showInterstitialAd(adKey, reload, onAdShowed, onAdClosed)

// Load
loadInterstitialAd(adKey, callback)
loadInterstitialAdsWithWaterfall(adKeys, delayMs, callback)

// Check
isInterstitialAdLoaded(adKey): Boolean

// Backup
enableBackupInterstitial(backupKey)
```

### Native
```kotlin
// Load
loadNativeAd(adKey, numberOfAds, callback)
loadNativeAdsWithWaterfall(adKeys, delayMs, callback)

// Get
getNativeAd(adKey, removeAfterGet): NativeAd?
getNativeAdOrBackup(adKey, removeAfterGet): NativeAd?

// Check
isNativeAdLoaded(adKey): Boolean

// Backup
enableBackupNative(backupKey)
```

### Banner
```kotlin
// Load
loadAdaptiveBanner(adKey, callback)
loadCollapsibleBanner(adKey, placement, requestId, callback)
loadBannersWithWaterfall(adKeys, delayMs, callback)

// Show
showBanner(adKey, container, removeAfterShow): Boolean
showBannerOrBackup(adKey, container, removeAfterShow): Boolean

// Check
isBannerAdLoaded(adKey): Boolean

// Backup
enableBackupBanner(backupKey)
```

## 🎉 Summary

Extension functions giúp:
- ✅ Show ads trực tiếp từ Fragment
- ✅ Không cần truyền Activity
- ✅ Null-safe (check activity != null)
- ✅ Clean code
- ✅ Easy to use

**Happy coding! 🚀**
