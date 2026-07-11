# Quick Start Guide - Preload Ads Management

## 🚀 Bắt đầu nhanh trong 5 phút

### Bước 1: Khởi tạo AdsManager

```kotlin
// Trong Application class
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize AdsManager
        val premiumManager = PremiumManager.getInstance(this)
        AdsManager.initialize(this, premiumManager)
    }
}
```

### Bước 2: Preload Ads ở Splash Screen

```kotlin
class SplashActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload interstitial
        adsManager.preloadInterstitialManagement.load(
            this, 
            "ca-app-pub-xxx/inter"
        )
        
        // Preload native
        adsManager.preloadNativeManagement.load(
            this,
            "ca-app-pub-xxx/native"
        )
        
        // Preload banner
        adsManager.preloadBannerManagement.loadAdaptiveBanner(
            this,
            "ca-app-pub-xxx/banner"
        )
        
        // Navigate sau 2s
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}
```

### Bước 3: Show Ads ở Main Screen

```kotlin
class MainActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Show banner
        val bannerContainer = findViewById<FrameLayout>(R.id.banner_container)
        adsManager.preloadBannerManagement.showBanner(
            "ca-app-pub-xxx/banner",
            bannerContainer
        )
    }
    
    fun navigateToNext() {
        // Show interstitial trước khi navigate
        adsManager.preloadInterstitialManagement.show(
            activity = this,
            adKey = "ca-app-pub-xxx/inter",
            reload = true,
            onAdClosed = { success ->
                // Navigate
                startActivity(Intent(this, NextActivity::class.java))
            }
        )
    }
}
```

## 📋 Checklist

- [ ] Initialize AdsManager trong Application
- [ ] Preload ads ở Splash Screen
- [ ] Show banner ở Main Screen
- [ ] Show interstitial khi navigate
- [ ] Destroy ads trong onDestroy()

## 🎯 Use Cases phổ biến

### 1. Show Interstitial khi click button

```kotlin
button.setOnClickListener {
    adsManager.preloadInterstitialManagement.show(
        this, "ad-key", true,
        onAdClosed = { 
            // Do action
        }
    )
}
```

### 2. Show Native trong RecyclerView

```kotlin
val nativeAd = adsManager.preloadNativeManagement.getNativeAd("ad-key", true)
if (nativeAd != null) {
    nativeAdView.setNativeAd(nativeAd)
}
```

### 3. Show Banner ở bottom

```kotlin
adsManager.preloadBannerManagement.showBanner(
    "ad-key",
    bannerContainer,
    removeAfterShow = false
)
```

## ⚡ Performance Tips

1. **Preload sớm**: Load ads ở splash screen
2. **Waterfall**: Dùng waterfall cho nhiều ads
3. **Backup**: Luôn có backup ads
4. **Destroy**: Destroy ads khi không dùng
5. **Check state**: Check isLoaded() trước khi show

## 🐛 Common Issues

### Issue 1: Ads không load
**Solution**: Check premium status và consent

```kotlin
if (!premiumManager.isSubscribed() && 
    googleMobileAdsConsentManager.canRequestAds) {
    // Can load ads
}
```

### Issue 2: Ads không show
**Solution**: Check interval và showing state

```kotlin
if (!adsManager.preloadInterstitialManagement.isShowingAds) {
    // Can show ads
}
```

### Issue 3: Memory leak
**Solution**: Destroy ads trong onDestroy()

```kotlin
override fun onDestroy() {
    super.onDestroy()
    adsManager.preloadNativeManagement.destroy("ad-key")
}
```

## 📚 Đọc thêm

- [PRELOAD_ADS_README.md](PRELOAD_ADS_README.md) - Hướng dẫn chi tiết
- [PreloadAdsExample.kt](src/main/java/tpt/dev/monetization/ads/preload/PreloadAdsExample.kt) - Code examples

## 💡 Tips

- Sử dụng `loadWithWaterfall()` cho nhiều ads
- Enable backup ads với `enableBackup()`
- Check pool statistics với `getLoadedCount()`
- Clear pool với `clearAll()` khi cần

## 🎉 Hoàn thành!

Bây giờ bạn đã biết cách sử dụng Preload Ads Management. Hãy xem [PRELOAD_ADS_README.md](PRELOAD_ADS_README.md) để tìm hiểu thêm về advanced features!
