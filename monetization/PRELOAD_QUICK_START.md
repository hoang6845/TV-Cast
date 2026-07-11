# Preload Ads - Quick Start Guide

## 🚀 Giới Thiệu

Hệ thống Preload Ads giúp load ads trước khi cần hiển thị, tối ưu trải nghiệm người dùng.

## 📦 Cài Đặt

Không cần cài đặt thêm, đã tích hợp sẵn trong `AdsManager`.

## 🎯 Sử Dụng Cơ Bản

### 1. Native Ads

```kotlin
val adsManager = AdsManager.getInstance()

// Preload
adsManager.preloadNativeAdManager.preloadNativeAd(
    placementKey = "home_native",
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)

// Show
val nativeAd = adsManager.preloadNativeAdManager.getNativeAd(
    placementKey = "home_native",
    autoReload = true,
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)
```

### 2. Interstitial Ads

```kotlin
// Preload
adsManager.preloadInterstitialAdManager.preloadInterstitialAd(
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)

// Show
adsManager.preloadInterstitialAdManager.showInterstitialAd(
    activity = this,
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    onAdClosed = { wasShown ->
        // Continue flow
    }
)
```

### 3. Banner Ads

```kotlin
// Preload
adsManager.preloadBannerAdManager.preloadBannerAd(
    placementKey = "home_banner",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    activity = this,
    autoRefresh = true
)

// Show
adsManager.preloadBannerAdManager.showBannerAd(
    placementKey = "home_banner",
    container = bannerContainer,
    onAdShowed = { /* Success */ },
    onAdFailed = { /* Failed */ }
)
```

## 💡 Best Practices

### ✅ DO

```kotlin
// 1. Preload sớm (trong splash/onCreate)
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    preloadAds()
}

// 2. Preload trước khi cần
fun onLevelStarted() {
    preloadInterstitialAd() // Preload trước khi level kết thúc
}

// 3. Clear cache khi không dùng
override fun onDestroy() {
    super.onDestroy()
    adsManager.preloadNativeAdManager.clearCache("home_native")
}

// 4. Check trạng thái trước khi show
if (adsManager.preloadInterstitialAdManager.isInterstitialAdLoaded("level_complete")) {
    showAd()
}
```

### ❌ DON'T

```kotlin
// 1. Không preload quá nhiều ads cùng lúc
// BAD: Preload 20 ads cùng lúc
for (i in 1..20) {
    preloadNativeAd("native_$i")
}

// 2. Không giữ ads trong cache quá lâu
// BAD: Preload và không bao giờ clear

// 3. Không spam ads
// BAD: Show ads liên tục không có interval
showInterstitialAd(forceShow = true) // Mỗi lần click

// 4. Không quên destroy native ads
// BAD: Không destroy khi fragment/activity bị destroy
```

## 🔧 Cấu Hình

### Show Interval (Interstitial)

```kotlin
// Đặt thời gian tối thiểu giữa các lần show (mặc định: 20s)
adsManager.preloadInterstitialAdManager.setShowInterval(30000L) // 30 giây
```

### Auto Refresh (Banner)

```kotlin
// Banner tự động refresh sau mỗi 60 giây
adsManager.preloadBannerAdManager.preloadBannerAd(
    placementKey = "home_banner",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    activity = this,
    autoRefresh = true,
    refreshInterval = 60000L
)
```

## 📊 Kiểm Tra Trạng Thái

```kotlin
// Native
val isLoaded = adsManager.preloadNativeAdManager.isNativeAdLoaded("home_native")
val isLoading = adsManager.preloadNativeAdManager.isLoading("home_native")
val cachedCount = adsManager.preloadNativeAdManager.getCachedCount()

// Interstitial
val isLoaded = adsManager.preloadInterstitialAdManager.isInterstitialAdLoaded("level_complete")
val isShowing = adsManager.preloadInterstitialAdManager.isShowingInterstitialAd()

// Banner
val isLoaded = adsManager.preloadBannerAdManager.isBannerAdLoaded("home_banner")
```

## 🎮 Ví Dụ Thực Tế

### Game Flow

```kotlin
class GameActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload interstitial khi game bắt đầu
        adsManager.preloadInterstitialAdManager.preloadInterstitialAd(
            "level_complete",
            "ca-app-pub-xxxxx/xxxxx"
        )
    }
    
    fun onLevelCompleted() {
        // Show ad khi level kết thúc
        adsManager.preloadInterstitialAdManager.showInterstitialAd(
            activity = this,
            placementKey = "level_complete",
            adUnitId = "ca-app-pub-xxxxx/xxxxx",
            onAdClosed = { wasShown ->
                navigateToNextLevel()
            }
        )
    }
}
```

### Splash Screen

```kotlin
class SplashActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload tất cả ads cần thiết
        preloadAllAds()
    }
    
    private fun preloadAllAds() {
        val placements = mapOf(
            "home_native" to "ca-app-pub-xxxxx/native",
            "level_complete" to "ca-app-pub-xxxxx/inter"
        )
        
        // Preload native
        adsManager.preloadNativeAdManager.preloadMultipleNativeAds(
            placements.filterKeys { it.contains("native") }
        ) { results ->
            Log.d("Splash", "Native ads loaded: ${results.count { it.value }}")
        }
        
        // Preload interstitial
        adsManager.preloadInterstitialAdManager.preloadMultipleInterstitialAds(
            placements.filterKeys { it.contains("complete") }
        ) { results ->
            Log.d("Splash", "Interstitial ads loaded: ${results.count { it.value }}")
            navigateToMain()
        }
    }
}
```

## 🐛 Troubleshooting

### Ads không load?

```kotlin
// 1. Check consent
if (!googleMobileAdsConsentManager.canRequestAds) {
    Log.e("Ads", "Consent not granted")
}

// 2. Check premium
if (premiumManager.isSubscribed()) {
    Log.e("Ads", "User is premium")
}

// 3. Check loading status
if (adsManager.preloadNativeAdManager.isLoading("home_native")) {
    Log.d("Ads", "Still loading...")
}
```

### Ads không show?

```kotlin
// 1. Check if loaded
if (!adsManager.preloadInterstitialAdManager.isInterstitialAdLoaded("level_complete")) {
    Log.e("Ads", "Ad not loaded")
}

// 2. Reset capping time
adsManager.preloadInterstitialAdManager.resetLastShowTime()

// 3. Force show
adsManager.preloadInterstitialAdManager.showInterstitialAd(
    activity = this,
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    forceShow = true // Bỏ qua capping
)
```

## 📚 Tài Liệu Đầy Đủ

Xem [PRELOAD_ADS_GUIDE.md](PRELOAD_ADS_GUIDE.md) để biết thêm chi tiết.

## 🎯 Key Features

- ✅ **Preload trước** - Ads luôn sẵn sàng
- ✅ **Auto reload** - Tự động reload sau khi sử dụng
- ✅ **Cache management** - Quản lý cache hiệu quả
- ✅ **Timeout & Retry** - Xử lý lỗi tự động
- ✅ **Thread-safe** - An toàn với coroutines
- ✅ **Capping control** - Kiểm soát tần suất show ads

## 📞 Support

Nếu có vấn đề, check:
1. [PRELOAD_ADS_GUIDE.md](PRELOAD_ADS_GUIDE.md) - Hướng dẫn đầy đủ
2. [PreloadAdsExample.kt](src/main/java/tpt/dev/monetization/ads/preload/PreloadAdsExample.kt) - Code examples

---

**Happy Coding! 🚀**
