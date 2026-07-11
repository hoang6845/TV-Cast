# Preload Ads Management - Hướng dẫn sử dụng

## Tổng quan

Hệ thống Preload Ads Management cung cấp cơ chế preload và quản lý ads (Interstitial, Native, Banner) với pool-based architecture, giúp tối ưu hiệu suất và trải nghiệm người dùng.

## Kiến trúc

### 1. PreloadInterstitialManagement
Quản lý việc preload và hiển thị Interstitial Ads

### 2. PreloadNativeManagement
Quản lý việc preload và hiển thị Native Ads

### 3. PreloadBannerManagement
Quản lý việc preload và hiển thị Banner Ads

## Tính năng chính

### ✅ Pool-based Architecture
- Lưu trữ ads đã load trong memory pool
- Tránh load lại ads không cần thiết
- Quản lý lifecycle của ads hiệu quả

### ✅ Waterfall Loading
- Load nhiều ads theo thứ tự ưu tiên
- Delay giữa các lần load để tránh rate limit
- Random delay để tối ưu performance

### ✅ Backup Ads
- Tự động fallback sang backup ads khi primary ads fail
- Cấu hình backup key linh hoạt

### ✅ Loading State Management
- Track trạng thái loading của từng ad
- Tránh duplicate loading
- Callback khi load xong

### ✅ Interval Control
- Kiểm soát thời gian giữa các lần show ads
- Tránh spam ads cho user

## Cách sử dụng

### 1. Khởi tạo

```kotlin
// Trong Application hoặc Activity
val adsManager = AdsManager.getInstance()

// Access preload managers
val preloadInterstitial = adsManager.preloadInterstitialManagement
val preloadNative = adsManager.preloadNativeManagement
val preloadBanner = adsManager.preloadBannerManagement
```

### 2. Preload Interstitial Ads

#### 2.1. Load đơn lẻ

```kotlin
// Load một interstitial ad
preloadInterstitial.load(activity, "ca-app-pub-xxx/yyy") {
    Log.d("Ads", "Interstitial loaded")
}
```

#### 2.2. Load với Waterfall

```kotlin
// Load nhiều ads theo thứ tự ưu tiên
val adKeys = listOf(
    "ca-app-pub-xxx/primary",
    "ca-app-pub-xxx/secondary",
    "ca-app-pub-xxx/tertiary"
)

preloadInterstitial.loadWithWaterfall(
    activity = activity,
    adKeys = adKeys,
    delayMs = 300L
) {
    Log.d("Ads", "All interstitials loaded")
}
```

#### 2.3. Enable Backup

```kotlin
// Enable backup ad
preloadInterstitial.enableBackup(activity, "ca-app-pub-xxx/backup")
```

#### 2.4. Show Interstitial

```kotlin
// Show ad với loading dialog
preloadInterstitial.show(
    activity = activity,
    adKey = "ca-app-pub-xxx/primary",
    reload = true,
    onAdShowed = {
        Log.d("Ads", "Ad showed")
    },
    onAdClosed = { success ->
        Log.d("Ads", "Ad closed: $success")
        // Navigate to next screen
    }
)
```

#### 2.5. Check trạng thái

```kotlin
// Kiểm tra ad đã load chưa
if (preloadInterstitial.isLoaded("ca-app-pub-xxx/primary")) {
    // Show ad
}

// Kiểm tra ad đang load
if (preloadInterstitial.isLoading("ca-app-pub-xxx/primary")) {
    // Wait for loading
}

// Kiểm tra đang show ads
if (preloadInterstitial.isShowingAds) {
    // Don't show another ad
}
```

### 3. Preload Native Ads

#### 3.1. Load đơn lẻ

```kotlin
// Load một native ad
preloadNative.load(
    activity = activity,
    adKey = "ca-app-pub-xxx/native",
    numberOfAds = 1
) { nativeAd ->
    if (nativeAd != null) {
        Log.d("Ads", "Native ad loaded")
    }
}
```

#### 3.2. Load nhiều ads

```kotlin
// Load nhiều native ads cùng lúc
preloadNative.load(
    activity = activity,
    adKey = "ca-app-pub-xxx/native",
    numberOfAds = 5
) { nativeAd ->
    // Callback được gọi cho mỗi ad load xong
}
```

#### 3.3. Load với Waterfall

```kotlin
val nativeAdKeys = listOf(
    "ca-app-pub-xxx/native-1",
    "ca-app-pub-xxx/native-2",
    "ca-app-pub-xxx/native-3"
)

preloadNative.loadWithWaterfall(
    activity = activity,
    adKeys = nativeAdKeys,
    delayMs = 300L
) {
    Log.d("Ads", "All native ads loaded")
}
```

#### 3.4. Get và Show Native Ad

```kotlin
// Get native ad từ pool
val nativeAd = preloadNative.getNativeAd(
    adKey = "ca-app-pub-xxx/native",
    removeAfterGet = true
)

if (nativeAd != null) {
    // Populate native ad view
    nativeAdView.setNativeAd(nativeAd)
}

// Hoặc get với backup
val nativeAdWithBackup = preloadNative.getNativeAdOrBackup(
    adKey = "ca-app-pub-xxx/native",
    removeAfterGet = true
)
```

#### 3.5. Destroy Native Ad

```kotlin
// Destroy khi không dùng nữa
preloadNative.destroy("ca-app-pub-xxx/native")
```

### 4. Preload Banner Ads

#### 4.1. Load Adaptive Banner

```kotlin
// Load adaptive banner
preloadBanner.loadAdaptiveBanner(
    activity = activity,
    adKey = "ca-app-pub-xxx/banner"
) { adView ->
    if (adView != null) {
        Log.d("Ads", "Banner loaded")
    }
}
```

#### 4.2. Load Collapsible Banner

```kotlin
// Load collapsible banner
preloadBanner.loadCollapsibleBanner(
    activity = activity,
    adKey = "ca-app-pub-xxx/banner",
    placement = BannerPlacement.BOTTOM,
    requestId = null // Auto generate
) { adView, requestId ->
    if (adView != null) {
        Log.d("Ads", "Collapsible banner loaded: $requestId")
    }
}
```

#### 4.3. Load với Waterfall

```kotlin
val bannerAdKeys = listOf(
    "ca-app-pub-xxx/banner-1",
    "ca-app-pub-xxx/banner-2"
)

preloadBanner.loadWithWaterfall(
    activity = activity,
    adKeys = bannerAdKeys,
    delayMs = 300L
) {
    Log.d("Ads", "All banners loaded")
}
```

#### 4.4. Show Banner

```kotlin
// Show banner vào container
val container = findViewById<FrameLayout>(R.id.banner_container)

val success = preloadBanner.showBanner(
    adKey = "ca-app-pub-xxx/banner",
    container = container,
    removeAfterShow = false
)

// Hoặc show với backup
val successWithBackup = preloadBanner.showBannerOrBackup(
    adKey = "ca-app-pub-xxx/banner",
    container = container,
    removeAfterShow = false
)
```

## Best Practices

### 1. Preload Strategy

#### Splash Screen
```kotlin
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val adsManager = AdsManager.getInstance()
        
        // Preload interstitial cho home screen
        adsManager.preloadInterstitialManagement.load(
            this, 
            "ca-app-pub-xxx/home-inter"
        )
        
        // Preload native cho list
        adsManager.preloadNativeManagement.load(
            this,
            "ca-app-pub-xxx/list-native",
            numberOfAds = 5
        )
        
        // Preload banner
        adsManager.preloadBannerManagement.loadAdaptiveBanner(
            this,
            "ca-app-pub-xxx/home-banner"
        )
        
        // Navigate sau khi preload
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}
```

#### Home Screen
```kotlin
class MainActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Show banner đã preload
        val bannerContainer = findViewById<FrameLayout>(R.id.banner_container)
        adsManager.preloadBannerManagement.showBanner(
            "ca-app-pub-xxx/home-banner",
            bannerContainer
        )
        
        // Preload cho màn hình tiếp theo
        preloadNextScreenAds()
    }
    
    private fun preloadNextScreenAds() {
        // Preload interstitial cho detail screen
        adsManager.preloadInterstitialManagement.load(
            this,
            "ca-app-pub-xxx/detail-inter"
        )
    }
    
    fun navigateToDetail() {
        // Show interstitial trước khi navigate
        adsManager.preloadInterstitialManagement.show(
            activity = this,
            adKey = "ca-app-pub-xxx/detail-inter",
            reload = true,
            onAdClosed = { success ->
                // Navigate regardless of ad result
                startActivity(Intent(this, DetailActivity::class.java))
            }
        )
    }
}
```

### 2. Memory Management

```kotlin
class MyActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Destroy native ads khi không dùng nữa
        adsManager.preloadNativeManagement.destroy("ca-app-pub-xxx/native")
        
        // Destroy banner ads
        adsManager.preloadBannerManagement.destroy("ca-app-pub-xxx/banner")
    }
}
```

### 3. Error Handling

```kotlin
// Load với error handling
adsManager.preloadInterstitialManagement.load(
    activity = this,
    adKey = "ca-app-pub-xxx/inter"
) {
    if (adsManager.preloadInterstitialManagement.isLoaded("ca-app-pub-xxx/inter")) {
        Log.d("Ads", "Load success")
    } else {
        Log.e("Ads", "Load failed, try backup")
        // Load backup
        adsManager.preloadInterstitialManagement.load(
            this,
            "ca-app-pub-xxx/backup"
        )
    }
}
```

### 4. Waterfall với Priority

```kotlin
// Define ad priority
val adPriority = listOf(
    "ca-app-pub-xxx/high-ecpm",    // Highest eCPM
    "ca-app-pub-xxx/medium-ecpm",  // Medium eCPM
    "ca-app-pub-xxx/low-ecpm",     // Lowest eCPM
    "ca-app-pub-xxx/backup"        // Backup
)

// Load theo priority
adsManager.preloadInterstitialManagement.loadWithWaterfall(
    activity = this,
    adKeys = adPriority,
    delayMs = 300L
) {
    Log.d("Ads", "Waterfall loading completed")
}

// Show ad đầu tiên available
fun showFirstAvailableAd() {
    for (adKey in adPriority) {
        if (adsManager.preloadInterstitialManagement.isLoaded(adKey)) {
            adsManager.preloadInterstitialManagement.show(
                activity = this,
                adKey = adKey,
                reload = true,
                onAdClosed = { success ->
                    // Handle close
                }
            )
            break
        }
    }
}
```

## Advanced Usage

### 1. Custom Interval

```kotlin
// Set custom interval cho interstitial
adsManager.updateTimeIntervalShowInterstitialAd(30.seconds)

// Reset interval
adsManager.preloadInterstitialManagement.resetAdShowedTime()
```

### 2. Batch Loading

```kotlin
fun preloadAllAds() {
    val interstitialKeys = listOf("inter-1", "inter-2", "inter-3")
    val nativeKeys = listOf("native-1", "native-2", "native-3")
    val bannerKeys = listOf("banner-1", "banner-2")
    
    // Load all interstitials
    adsManager.preloadInterstitialManagement.loadWithWaterfall(
        this, interstitialKeys, 300L
    )
    
    // Load all natives
    adsManager.preloadNativeManagement.loadWithWaterfall(
        this, nativeKeys, 300L
    )
    
    // Load all banners
    adsManager.preloadBannerManagement.loadWithWaterfall(
        this, bannerKeys, 300L
    )
}
```

### 3. Pool Statistics

```kotlin
// Get pool statistics
val interLoadedCount = adsManager.preloadInterstitialManagement.adsPool.size
val nativeLoadedCount = adsManager.preloadNativeManagement.getLoadedCount()
val nativeLoadingCount = adsManager.preloadNativeManagement.getLoadingCount()
val bannerLoadedCount = adsManager.preloadBannerManagement.getLoadedCount()

Log.d("Ads", """
    Pool Statistics:
    - Interstitial: $interLoadedCount loaded
    - Native: $nativeLoadedCount loaded, $nativeLoadingCount loading
    - Banner: $bannerLoadedCount loaded
""".trimIndent())
```

### 4. Clear Pool

```kotlin
// Clear all ads in pool
adsManager.preloadInterstitialManagement.clearAll()
adsManager.preloadNativeManagement.clearAll()
adsManager.preloadBannerManagement.clearAll()
```

## Troubleshooting

### 1. Ads không load

**Nguyên nhân:**
- Premium user (ads bị disable)
- Consent chưa được cấp
- Ad ID không đúng
- Network issue

**Giải pháp:**
```kotlin
// Check premium status
if (!adsManager.premiumManager.isSubscribed()) {
    // User is not premium, ads should load
}

// Check consent
if (googleMobileAdsConsentManager.canRequestAds) {
    // Can request ads
}

// Enable logging
Log.d("Ads", "Loading ad: $adKey")
```

### 2. Ads load nhưng không show

**Nguyên nhân:**
- Interval chưa đủ
- Đang show ads khác
- Activity destroyed

**Giải pháp:**
```kotlin
// Check interval
if (System.currentTimeMillis() - lastShowTime > interval) {
    // Can show
}

// Check showing state
if (!adsManager.preloadInterstitialManagement.isShowingAds) {
    // Can show
}

// Check activity state
if (!activity.isDestroyed && !activity.isFinishing) {
    // Can show
}
```

### 3. Memory leak

**Nguyên nhân:**
- Không destroy ads khi không dùng
- Giữ reference đến activity

**Giải pháp:**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    // Destroy ads
    adsManager.preloadNativeManagement.destroy(adKey)
    adsManager.preloadBannerManagement.destroy(adKey)
}
```

## Performance Tips

1. **Preload sớm**: Load ads ở splash screen hoặc background
2. **Waterfall delay**: Sử dụng delay 300-500ms giữa các lần load
3. **Limit pool size**: Không load quá nhiều ads cùng lúc
4. **Destroy unused**: Destroy ads khi không dùng nữa
5. **Check state**: Luôn check trạng thái trước khi show ads

## Migration từ code cũ

### Trước đây:
```kotlin
// Load và show trực tiếp
interstitialAdUtils.loadAd()
interstitialAdUtils.showAd(activity) { success ->
    // Handle
}
```

### Bây giờ:
```kotlin
// Preload trước
adsManager.preloadInterstitialManagement.load(activity, adKey)

// Show sau
adsManager.preloadInterstitialManagement.show(
    activity, adKey, true,
    onAdClosed = { success ->
        // Handle
    }
)
```

## Kết luận

Hệ thống Preload Ads Management cung cấp:
- ✅ Performance tốt hơn với preloading
- ✅ UX tốt hơn với loading dialog
- ✅ Quản lý ads hiệu quả với pool
- ✅ Backup ads tự động
- ✅ Waterfall loading thông minh
- ✅ Memory management tốt

Sử dụng đúng cách sẽ giúp tăng revenue và cải thiện trải nghiệm người dùng.
