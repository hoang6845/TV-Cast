# Migration Guide - Chuyển đổi sang Preload Ads Management

## 📖 Tổng quan

Hướng dẫn này giúp bạn chuyển đổi từ cách load ads truyền thống sang Preload Ads Management.

## 🔄 So sánh

### Cách cũ (Traditional)
```kotlin
// Load và show trực tiếp
interstitialAdUtils.loadAd()

// Show khi cần
interstitialAdUtils.showAd(activity) { success ->
    // Handle
}
```

### Cách mới (Preload)
```kotlin
// Preload trước (ở splash hoặc background)
adsManager.preloadInterstitialManagement.load(activity, adKey)

// Show ngay lập tức khi cần
adsManager.preloadInterstitialManagement.show(
    activity, adKey, true,
    onAdClosed = { success ->
        // Handle
    }
)
```

## 📝 Migration Steps

### Step 1: Update AdsManager

#### Trước:
```kotlin
class AdsManager {
    lateinit var interstitialAdUtils: InterstitialAdUtils
    val singleNativeAdUtils: SingleNativeAdUtils
    val bannerAdUtils: BannerAdUtils
}
```

#### Sau:
```kotlin
class AdsManager {
    lateinit var interstitialAdUtils: InterstitialAdUtils
    val singleNativeAdUtils: SingleNativeAdUtils
    val bannerAdUtils: BannerAdUtils
    
    // Thêm preload managers
    val preloadInterstitialManagement: PreloadInterstitialManagement
    val preloadNativeManagement: PreloadNativeManagement
    val preloadBannerManagement: PreloadBannerManagement
}
```

### Step 2: Migrate Interstitial Ads

#### Trước:
```kotlin
class MainActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    fun showAd() {
        // Load
        adsManager.interstitialAdUtils.loadAd()
        
        // Show
        adsManager.interstitialAdUtils.showAd(this) { success ->
            if (success) {
                // Navigate
            }
        }
    }
}
```

#### Sau:
```kotlin
class MainActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload ngay khi vào màn hình
        adsManager.preloadInterstitialManagement.load(
            this,
            "ca-app-pub-xxx/inter"
        )
    }
    
    fun showAd() {
        // Show ngay lập tức
        adsManager.preloadInterstitialManagement.show(
            activity = this,
            adKey = "ca-app-pub-xxx/inter",
            reload = true,
            onAdClosed = { success ->
                if (success) {
                    // Navigate
                }
            }
        )
    }
}
```

### Step 3: Migrate Native Ads

#### Trước:
```kotlin
class DetailActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    fun loadNativeAd() {
        adsManager.singleNativeAdUtils.loadAd(
            activity = this,
            adId = "ca-app-pub-xxx/native",
            onLoadFailed = { error ->
                Log.e("Ads", "Failed: $error")
            },
            onAdLoaded = { nativeAd ->
                // Show ad
                nativeAdView.setNativeAd(nativeAd)
            }
        )
    }
}
```

#### Sau:
```kotlin
class DetailActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get ad đã preload từ splash
        showPreloadedNativeAd()
    }
    
    private fun showPreloadedNativeAd() {
        val nativeAd = adsManager.preloadNativeManagement.getNativeAd(
            adKey = "ca-app-pub-xxx/native",
            removeAfterGet = true
        )
        
        if (nativeAd != null) {
            // Show ad
            nativeAdView.setNativeAd(nativeAd)
            
            // Preload lại cho lần sau
            adsManager.preloadNativeManagement.load(
                this,
                "ca-app-pub-xxx/native"
            )
        } else {
            // Load nếu chưa có
            adsManager.preloadNativeManagement.load(
                this,
                "ca-app-pub-xxx/native"
            ) { loadedAd ->
                if (loadedAd != null) {
                    nativeAdView.setNativeAd(loadedAd)
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        adsManager.preloadNativeManagement.destroy("ca-app-pub-xxx/native")
    }
}
```

### Step 4: Migrate Banner Ads

#### Trước:
```kotlin
class HomeActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    fun loadBanner() {
        adsManager.bannerAdUtils.loadAdaptiveBanner(
            adViewContainer = bannerContainer,
            activity = this,
            adId = "ca-app-pub-xxx/banner",
            onAdLoaded = {
                Log.d("Ads", "Banner loaded")
            },
            onAdFailedToLoad = {
                Log.e("Ads", "Banner failed")
            }
        )
    }
}
```

#### Sau:
```kotlin
class HomeActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Show banner đã preload từ splash
        showPreloadedBanner()
    }
    
    private fun showPreloadedBanner() {
        val success = adsManager.preloadBannerManagement.showBanner(
            adKey = "ca-app-pub-xxx/banner",
            container = bannerContainer,
            removeAfterShow = false
        )
        
        if (!success) {
            // Load nếu chưa có
            adsManager.preloadBannerManagement.loadAdaptiveBanner(
                this,
                "ca-app-pub-xxx/banner"
            ) { adView ->
                if (adView != null) {
                    adsManager.preloadBannerManagement.showBanner(
                        "ca-app-pub-xxx/banner",
                        bannerContainer
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        adsManager.preloadBannerManagement.destroy("ca-app-pub-xxx/banner")
    }
}
```

### Step 5: Update Splash Screen

#### Trước:
```kotlin
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Chỉ navigate
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}
```

#### Sau:
```kotlin
class SplashActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload tất cả ads
        preloadAllAds()
        
        // Navigate
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
    
    private fun preloadAllAds() {
        // Interstitial
        val interKeys = listOf(
            "ca-app-pub-xxx/home-inter",
            "ca-app-pub-xxx/detail-inter"
        )
        adsManager.preloadInterstitialManagement.loadWithWaterfall(
            this, interKeys, 300L
        )
        
        // Native
        val nativeKeys = listOf(
            "ca-app-pub-xxx/list-native",
            "ca-app-pub-xxx/detail-native"
        )
        adsManager.preloadNativeManagement.loadWithWaterfall(
            this, nativeKeys, 300L
        )
        
        // Banner
        adsManager.preloadBannerManagement.loadAdaptiveBanner(
            this,
            "ca-app-pub-xxx/home-banner"
        )
        
        // Enable backups
        adsManager.preloadInterstitialManagement.enableBackup(
            this,
            "ca-app-pub-xxx/backup-inter"
        )
        adsManager.preloadNativeManagement.enableBackup(
            this,
            "ca-app-pub-xxx/backup-native"
        )
    }
}
```

## 🎯 Best Practices sau khi migrate

### 1. Preload Strategy

```kotlin
// Splash Screen: Preload tất cả
class SplashActivity {
    fun preloadAll() {
        preloadInterstitials()
        preloadNatives()
        preloadBanners()
    }
}

// Home Screen: Preload cho màn hình tiếp theo
class HomeActivity {
    fun preloadNext() {
        adsManager.preloadInterstitialManagement.load(
            this,
            "next-screen-inter"
        )
    }
}
```

### 2. Memory Management

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // Destroy native ads
    adsManager.preloadNativeManagement.destroy(adKey)
    
    // Destroy banner ads
    adsManager.preloadBannerManagement.destroy(adKey)
    
    // Note: Không destroy interstitial vì có thể dùng lại
}
```

### 3. Error Handling

```kotlin
fun showAdWithFallback() {
    if (adsManager.preloadInterstitialManagement.isLoaded(primaryKey)) {
        // Show primary
        adsManager.preloadInterstitialManagement.show(
            this, primaryKey, true,
            onAdClosed = { /* Handle */ }
        )
    } else if (adsManager.preloadInterstitialManagement.isLoaded(backupKey)) {
        // Show backup
        adsManager.preloadInterstitialManagement.show(
            this, backupKey, true,
            onAdClosed = { /* Handle */ }
        )
    } else {
        // No ads available
        Log.w("Ads", "No ads available")
    }
}
```

## 📊 Performance Comparison

### Trước (Traditional)
- Load time: 2-3 giây
- User wait: 2-3 giây
- UX: ⭐⭐⭐

### Sau (Preload)
- Load time: 0 giây (đã preload)
- User wait: 0 giây
- UX: ⭐⭐⭐⭐⭐

## ✅ Checklist Migration

- [ ] Update AdsManager với preload managers
- [ ] Migrate interstitial ads
- [ ] Migrate native ads
- [ ] Migrate banner ads
- [ ] Update splash screen với preload
- [ ] Add destroy() trong onDestroy()
- [ ] Test tất cả flows
- [ ] Monitor performance

## 🐛 Common Migration Issues

### Issue 1: Ads không show sau migrate
**Nguyên nhân**: Quên preload ở splash

**Solution**:
```kotlin
// Thêm preload ở splash
adsManager.preloadInterstitialManagement.load(this, adKey)
```

### Issue 2: Memory leak
**Nguyên nhân**: Quên destroy ads

**Solution**:
```kotlin
override fun onDestroy() {
    super.onDestroy()
    adsManager.preloadNativeManagement.destroy(adKey)
}
```

### Issue 3: Ads load chậm
**Nguyên nhân**: Không dùng waterfall

**Solution**:
```kotlin
// Dùng waterfall thay vì load tuần tự
adsManager.preloadInterstitialManagement.loadWithWaterfall(
    this, adKeys, 300L
)
```

## 📈 Expected Results

Sau khi migrate, bạn sẽ thấy:

1. **Performance tốt hơn**
   - Ads show ngay lập tức
   - Không có loading time
   - UX mượt mà hơn

2. **Revenue tăng**
   - Fill rate cao hơn
   - eCPM tốt hơn
   - Impression nhiều hơn

3. **Code sạch hơn**
   - Dễ maintain
   - Dễ test
   - Dễ scale

## 🎉 Hoàn thành!

Bạn đã hoàn thành migration sang Preload Ads Management. Hãy monitor performance và adjust strategy cho phù hợp với app của bạn!

## 📚 Tài liệu tham khảo

- [PRELOAD_ADS_README.md](PRELOAD_ADS_README.md) - Hướng dẫn chi tiết
- [QUICK_START_GUIDE.md](QUICK_START_GUIDE.md) - Bắt đầu nhanh
- [PreloadAdsExample.kt](src/main/java/tpt/dev/monetization/ads/preload/PreloadAdsExample.kt) - Code examples
