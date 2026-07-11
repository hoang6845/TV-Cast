# Hướng Dẫn Sử Dụng Preload Ads System

## Tổng Quan

Hệ thống Preload Ads giúp tối ưu trải nghiệm người dùng bằng cách:
- **Preload ads trước** khi cần hiển thị
- **Cache ads** đã load thành công
- **Auto reload** sau khi ads được sử dụng
- **Quản lý timeout** và retry logic
- **Thread-safe** với Kotlin Coroutines

## Cấu Trúc

```
monetization/ads/preload/
├── PreloadNativeAdManager.kt        # Quản lý Native Ads
├── PreloadInterstitialAdManager.kt  # Quản lý Interstitial Ads
└── PreloadBannerAdManager.kt        # Quản lý Banner Ads
```

---

## 1. PreloadNativeAdManager

### 1.1. Khởi Tạo

```kotlin
// Đã được tích hợp sẵn trong AdsManager
val adsManager = AdsManager.getInstance()
val nativeAdManager = adsManager.preloadNativeAdManager
```

### 1.2. Preload Native Ad

```kotlin
// Preload một native ad
nativeAdManager.preloadNativeAd(
    placementKey = "home_native",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    autoReload = true,
    onLoadComplete = { success ->
        if (success) {
            Log.d("Ads", "Native ad preloaded successfully")
        } else {
            Log.e("Ads", "Failed to preload native ad")
        }
    }
)
```

### 1.3. Preload Nhiều Native Ads

```kotlin
val placements = mapOf(
    "home_native" to "ca-app-pub-xxxxx/xxxxx",
    "detail_native" to "ca-app-pub-xxxxx/yyyyy",
    "list_native" to "ca-app-pub-xxxxx/zzzzz"
)

nativeAdManager.preloadMultipleNativeAds(placements) { results ->
    results.forEach { (key, success) ->
        Log.d("Ads", "Placement $key: ${if (success) "Success" else "Failed"}")
    }
}
```

### 1.4. Lấy và Hiển Thị Native Ad

```kotlin
// Lấy native ad từ cache
val nativeAd = nativeAdManager.getNativeAd(
    placementKey = "home_native",
    autoReload = true,  // Tự động reload sau khi lấy
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)

if (nativeAd != null) {
    // Hiển thị native ad
    populateNativeAdView(nativeAdView, nativeAd)
} else {
    // Không có ad, có thể trigger preload
    nativeAdManager.preloadNativeAd("home_native", "ca-app-pub-xxxxx/xxxxx")
}
```

### 1.5. Kiểm Tra Trạng Thái

```kotlin
// Kiểm tra ad đã load chưa
if (nativeAdManager.isNativeAdLoaded("home_native")) {
    Log.d("Ads", "Native ad is ready")
}

// Kiểm tra đang load không
if (nativeAdManager.isLoading("home_native")) {
    Log.d("Ads", "Native ad is loading...")
}

// Lấy số lượng ads đã cache
val cachedCount = nativeAdManager.getCachedCount()
Log.d("Ads", "Cached native ads: $cachedCount")

// Lấy danh sách placement keys đã cache
val cachedKeys = nativeAdManager.getCachedKeys()
Log.d("Ads", "Cached keys: $cachedKeys")
```

### 1.6. Quản Lý Cache

```kotlin
// Clear cache của một placement
nativeAdManager.clearCache("home_native")

// Clear toàn bộ cache
nativeAdManager.clearAllCache()

// Hủy load đang chạy
nativeAdManager.cancelLoad("home_native")
```

---

## 2. PreloadInterstitialAdManager

### 2.1. Khởi Tạo

```kotlin
val adsManager = AdsManager.getInstance()
val interstitialAdManager = adsManager.preloadInterstitialAdManager
```

### 2.2. Cấu Hình Show Interval

```kotlin
// Đặt thời gian tối thiểu giữa các lần show (mặc định: 20 giây)
interstitialAdManager.setShowInterval(30000L) // 30 giây
```

### 2.3. Preload Interstitial Ad

```kotlin
// Preload một interstitial ad
interstitialAdManager.preloadInterstitialAd(
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    onLoadComplete = { success ->
        if (success) {
            Log.d("Ads", "Interstitial ad preloaded successfully")
        }
    }
)
```

### 2.4. Preload Nhiều Interstitial Ads

```kotlin
val placements = mapOf(
    "level_complete" to "ca-app-pub-xxxxx/xxxxx",
    "game_over" to "ca-app-pub-xxxxx/yyyyy",
    "reward_claim" to "ca-app-pub-xxxxx/zzzzz"
)

interstitialAdManager.preloadMultipleInterstitialAds(placements) { results ->
    results.forEach { (key, success) ->
        Log.d("Ads", "Placement $key: ${if (success) "Success" else "Failed"}")
    }
}
```

### 2.5. Show Interstitial Ad

```kotlin
// Show interstitial ad với auto reload
interstitialAdManager.showInterstitialAd(
    activity = this,
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    forceShow = false,  // Bỏ qua capping time nếu true
    onAdShowed = {
        Log.d("Ads", "Interstitial ad showed")
    },
    onAdClosed = { wasShown ->
        if (wasShown) {
            Log.d("Ads", "User closed the ad")
            // Continue game flow
        } else {
            Log.d("Ads", "Ad was not shown")
            // Continue without ad
        }
    }
)
```

### 2.6. Force Show (Bỏ Qua Capping)

```kotlin
// Show ngay lập tức, bỏ qua thời gian capping
interstitialAdManager.showInterstitialAd(
    activity = this,
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    forceShow = true,  // ✅ Bỏ qua capping
    onAdClosed = { wasShown ->
        // Handle close
    }
)
```

### 2.7. Kiểm Tra Trạng Thái

```kotlin
// Kiểm tra ad đã load chưa
if (interstitialAdManager.isInterstitialAdLoaded("level_complete")) {
    Log.d("Ads", "Interstitial ad is ready")
}

// Kiểm tra đang show ad không
if (interstitialAdManager.isShowingInterstitialAd()) {
    Log.d("Ads", "An interstitial ad is currently showing")
}

// Reset thời gian show cuối cùng (cho phép show ngay)
interstitialAdManager.resetLastShowTime()
```

### 2.8. Quản Lý Cache

```kotlin
// Clear cache của một placement
interstitialAdManager.clearCache("level_complete")

// Clear toàn bộ cache
interstitialAdManager.clearAllCache()

// Hủy load đang chạy
interstitialAdManager.cancelLoad("level_complete")
```

---

## 3. PreloadBannerAdManager

### 3.1. Khởi Tạo

```kotlin
val adsManager = AdsManager.getInstance()
val bannerAdManager = adsManager.preloadBannerAdManager
```

### 3.2. Preload Banner Ad

```kotlin
// Preload một banner ad
bannerAdManager.preloadBannerAd(
    placementKey = "home_banner",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    activity = this,
    autoRefresh = true,  // Tự động refresh
    refreshInterval = 60000L,  // 60 giây
    onLoadComplete = { success ->
        if (success) {
            Log.d("Ads", "Banner ad preloaded successfully")
        }
    }
)
```

### 3.3. Preload Nhiều Banner Ads

```kotlin
val placements = mapOf(
    "home_banner" to Pair("ca-app-pub-xxxxx/xxxxx", this),
    "detail_banner" to Pair("ca-app-pub-xxxxx/yyyyy", this)
)

bannerAdManager.preloadMultipleBannerAds(
    placements = placements,
    autoRefresh = true,
    refreshInterval = 60000L
) { results ->
    results.forEach { (key, success) ->
        Log.d("Ads", "Placement $key: ${if (success) "Success" else "Failed"}")
    }
}
```

### 3.4. Show Banner Ad

```kotlin
// Show banner ad vào container
val bannerContainer = findViewById<FrameLayout>(R.id.banner_container)

bannerAdManager.showBannerAd(
    placementKey = "home_banner",
    container = bannerContainer,
    onAdShowed = {
        Log.d("Ads", "Banner ad showed")
        bannerContainer.visibility = View.VISIBLE
    },
    onAdFailed = {
        Log.e("Ads", "Banner ad failed to show")
        bannerContainer.visibility = View.GONE
    }
)
```

### 3.5. Auto Refresh

```kotlin
// Banner sẽ tự động refresh sau mỗi 60 giây
bannerAdManager.preloadBannerAd(
    placementKey = "home_banner",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    activity = this,
    autoRefresh = true,
    refreshInterval = 60000L
)

// Stop auto refresh khi không cần nữa
bannerAdManager.stopAutoRefresh("home_banner")
```

### 3.6. Kiểm Tra Trạng Thái

```kotlin
// Kiểm tra ad đã load chưa
if (bannerAdManager.isBannerAdLoaded("home_banner")) {
    Log.d("Ads", "Banner ad is ready")
}

// Kiểm tra đang load không
if (bannerAdManager.isLoading("home_banner")) {
    Log.d("Ads", "Banner ad is loading...")
}
```

### 3.7. Quản Lý Cache

```kotlin
// Clear cache và stop auto refresh
bannerAdManager.clearCache("home_banner")

// Clear toàn bộ cache
bannerAdManager.clearAllCache()

// Hủy load đang chạy
bannerAdManager.cancelLoad("home_banner")
```

---

## 4. Best Practices

### 4.1. Preload Ads Sớm

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val adsManager = AdsManager.getInstance()
        
        // Preload ads ngay khi app khởi động
        adsManager.preloadNativeAdManager.preloadNativeAd(
            "home_native", 
            "ca-app-pub-xxxxx/xxxxx"
        )
        
        adsManager.preloadInterstitialAdManager.preloadInterstitialAd(
            "level_complete",
            "ca-app-pub-xxxxx/yyyyy"
        )
        
        adsManager.preloadBannerAdManager.preloadBannerAd(
            "home_banner",
            "ca-app-pub-xxxxx/zzzzz",
            this
        )
    }
}
```

### 4.2. Preload Trước Khi Cần

```kotlin
// Preload interstitial trước khi user hoàn thành level
fun onLevelStarted() {
    adsManager.preloadInterstitialAdManager.preloadInterstitialAd(
        "level_complete",
        "ca-app-pub-xxxxx/xxxxx"
    )
}

fun onLevelCompleted() {
    // Ad đã sẵn sàng để show
    adsManager.preloadInterstitialAdManager.showInterstitialAd(
        activity = this,
        placementKey = "level_complete",
        adUnitId = "ca-app-pub-xxxxx/xxxxx",
        onAdClosed = { wasShown ->
            navigateToNextLevel()
        }
    )
}
```

### 4.3. Quản Lý Lifecycle

```kotlin
class GameFragment : Fragment() {
    private lateinit var adsManager: AdsManager
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adsManager = AdsManager.getInstance()
        
        // Preload ads khi fragment được tạo
        preloadAds()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        
        // Clear cache khi fragment bị destroy
        adsManager.preloadNativeAdManager.clearCache("game_native")
        adsManager.preloadBannerAdManager.clearCache("game_banner")
    }
    
    private fun preloadAds() {
        adsManager.preloadNativeAdManager.preloadNativeAd(
            "game_native",
            "ca-app-pub-xxxxx/xxxxx"
        )
    }
}
```

### 4.4. Error Handling

```kotlin
// Luôn handle trường hợp ad không load được
fun showInterstitialWithFallback() {
    val adManager = adsManager.preloadInterstitialAdManager
    
    if (adManager.isInterstitialAdLoaded("level_complete")) {
        adManager.showInterstitialAd(
            activity = this,
            placementKey = "level_complete",
            adUnitId = "ca-app-pub-xxxxx/xxxxx",
            onAdClosed = { wasShown ->
                if (wasShown) {
                    Log.d("Ads", "Ad was shown")
                } else {
                    Log.w("Ads", "Ad failed to show")
                }
                continueGameFlow()
            }
        )
    } else {
        // Không có ad, tiếp tục game flow
        Log.w("Ads", "No ad available")
        continueGameFlow()
    }
}
```

### 4.5. Preload Strategy

```kotlin
class AdsPreloadStrategy {
    private val adsManager = AdsManager.getInstance()
    
    // Preload tất cả ads cần thiết cho app
    fun preloadAllAds(activity: Activity) {
        // Native ads
        val nativePlacements = mapOf(
            "home_native" to "ca-app-pub-xxxxx/native1",
            "detail_native" to "ca-app-pub-xxxxx/native2",
            "list_native" to "ca-app-pub-xxxxx/native3"
        )
        adsManager.preloadNativeAdManager.preloadMultipleNativeAds(nativePlacements)
        
        // Interstitial ads
        val interstitialPlacements = mapOf(
            "level_complete" to "ca-app-pub-xxxxx/inter1",
            "game_over" to "ca-app-pub-xxxxx/inter2"
        )
        adsManager.preloadInterstitialAdManager.preloadMultipleInterstitialAds(interstitialPlacements)
        
        // Banner ads
        val bannerPlacements = mapOf(
            "home_banner" to Pair("ca-app-pub-xxxxx/banner1", activity),
            "game_banner" to Pair("ca-app-pub-xxxxx/banner2", activity)
        )
        adsManager.preloadBannerAdManager.preloadMultipleBannerAds(
            placements = bannerPlacements,
            autoRefresh = true,
            refreshInterval = 60000L
        )
    }
}
```

---

## 5. Ví Dụ Thực Tế

### 5.1. Splash Screen với Preload

```kotlin
class SplashActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Preload ads trong splash screen
        preloadAdsAndNavigate()
    }
    
    private fun preloadAdsAndNavigate() {
        val placements = mapOf(
            "home_native" to "ca-app-pub-xxxxx/xxxxx",
            "detail_native" to "ca-app-pub-xxxxx/yyyyy"
        )
        
        adsManager.preloadNativeAdManager.preloadMultipleNativeAds(placements) { results ->
            Log.d("Splash", "Preload completed: $results")
            
            // Navigate to main activity sau khi preload xong
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }, 1000)
        }
    }
}
```

### 5.2. Game với Interstitial

```kotlin
class GameActivity : AppCompatActivity() {
    private val adsManager by lazy { AdsManager.getInstance() }
    private var currentLevel = 1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        
        // Preload interstitial khi game bắt đầu
        preloadLevelCompleteAd()
    }
    
    private fun preloadLevelCompleteAd() {
        adsManager.preloadInterstitialAdManager.preloadInterstitialAd(
            placementKey = "level_complete",
            adUnitId = "ca-app-pub-xxxxx/xxxxx"
        )
    }
    
    fun onLevelCompleted() {
        currentLevel++
        
        // Show ad sau mỗi 3 level
        if (currentLevel % 3 == 0) {
            showLevelCompleteAd()
        } else {
            navigateToNextLevel()
        }
    }
    
    private fun showLevelCompleteAd() {
        adsManager.preloadInterstitialAdManager.showInterstitialAd(
            activity = this,
            placementKey = "level_complete",
            adUnitId = "ca-app-pub-xxxxx/xxxxx",
            onAdClosed = { wasShown ->
                navigateToNextLevel()
            }
        )
    }
    
    private fun navigateToNextLevel() {
        // Load next level
        loadLevel(currentLevel)
        
        // Preload ad cho lần sau
        preloadLevelCompleteAd()
    }
}
```

### 5.3. RecyclerView với Native Ads

```kotlin
class ArticleListFragment : Fragment() {
    private val adsManager by lazy { AdsManager.getInstance() }
    private val nativeAds = mutableListOf<NativeAd>()
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Preload nhiều native ads cho list
        preloadNativeAdsForList()
    }
    
    private fun preloadNativeAdsForList() {
        val placements = mapOf(
            "list_native_1" to "ca-app-pub-xxxxx/xxxxx",
            "list_native_2" to "ca-app-pub-xxxxx/xxxxx",
            "list_native_3" to "ca-app-pub-xxxxx/xxxxx"
        )
        
        adsManager.preloadNativeAdManager.preloadMultipleNativeAds(placements) { results ->
            // Lấy các ads đã load thành công
            results.filter { it.value }.keys.forEach { key ->
                val ad = adsManager.preloadNativeAdManager.getNativeAd(
                    placementKey = key,
                    autoReload = false
                )
                ad?.let { nativeAds.add(it) }
            }
            
            // Update adapter với ads
            updateAdapterWithAds()
        }
    }
    
    private fun updateAdapterWithAds() {
        // Insert native ads vào list items
        // Ví dụ: mỗi 5 items có 1 ad
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        
        // Destroy native ads
        nativeAds.forEach { it.destroy() }
        nativeAds.clear()
    }
}
```

---

## 6. Lưu Ý Quan Trọng

### 6.1. Memory Management
- Luôn **destroy** native ads khi không dùng nữa
- **Clear cache** khi fragment/activity bị destroy
- Không giữ quá nhiều ads trong cache cùng lúc

### 6.2. Lifecycle
- Preload ads khi app **foreground**
- Clear cache khi app **background** (nếu cần)
- Handle **configuration changes** đúng cách

### 6.3. Performance
- Không preload quá nhiều ads cùng lúc
- Sử dụng **timeout** hợp lý
- Monitor **memory usage**

### 6.4. User Experience
- Đặt **show interval** hợp lý (không spam ads)
- Luôn có **fallback** khi ads không load được
- Không block UI khi load ads

---

## 7. Troubleshooting

### 7.1. Ads Không Load

```kotlin
// Check consent
if (!googleMobileAdsConsentManager.canRequestAds) {
    Log.e("Ads", "Cannot request ads - consent not granted")
}

// Check premium status
if (premiumManager.isSubscribed()) {
    Log.e("Ads", "User is premium - ads disabled")
}

// Check loading status
if (adsManager.preloadNativeAdManager.isLoading("home_native")) {
    Log.d("Ads", "Ad is still loading...")
}
```

### 7.2. Ads Không Show

```kotlin
// Check if ad is loaded
if (!adsManager.preloadInterstitialAdManager.isInterstitialAdLoaded("level_complete")) {
    Log.e("Ads", "Ad not loaded yet")
}

// Check capping time
adsManager.preloadInterstitialAdManager.resetLastShowTime()

// Force show
adsManager.preloadInterstitialAdManager.showInterstitialAd(
    activity = this,
    placementKey = "level_complete",
    adUnitId = "ca-app-pub-xxxxx/xxxxx",
    forceShow = true  // Bỏ qua capping
)
```

### 7.3. Memory Leaks

```kotlin
// Luôn clear cache trong onDestroy
override fun onDestroy() {
    super.onDestroy()
    adsManager.preloadNativeAdManager.clearAllCache()
    adsManager.preloadBannerAdManager.clearAllCache()
}
```

---

## 8. Migration từ Code Cũ

### 8.1. Từ SingleNativeAdUtils

**Trước:**
```kotlin
singleNativeAdUtils.loadAd(
    activity = this,
    adId = "ca-app-pub-xxxxx/xxxxx",
    onAdLoaded = { nativeAd ->
        // Show ad
    },
    onLoadFailed = { error ->
        // Handle error
    }
)
```

**Sau:**
```kotlin
// Preload trước
adsManager.preloadNativeAdManager.preloadNativeAd(
    placementKey = "home_native",
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)

// Lấy và show khi cần
val nativeAd = adsManager.preloadNativeAdManager.getNativeAd(
    placementKey = "home_native",
    autoReload = true,
    adUnitId = "ca-app-pub-xxxxx/xxxxx"
)
```

### 8.2. Từ InterstitialAdUtils

**Trước:**
```kotlin
interstitialAdUtils.loadAd()
// ...
interstitialAdUtils.showAd(
    activity = this,
    onAdsClosed = { wasShown ->
        // Handle close
    }
)
```

**Sau:**
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
        // Handle close
    }
)
```

---

## Kết Luận

Hệ thống Preload Ads giúp:
- ✅ Tăng **fill rate** (ads luôn sẵn sàng)
- ✅ Giảm **latency** (không phải đợi load)
- ✅ Tối ưu **UX** (mượt mà hơn)
- ✅ Dễ **quản lý** (centralized cache)
- ✅ **Thread-safe** (coroutines)

Hãy sử dụng preload managers thay vì load ads trực tiếp để có trải nghiệm tốt nhất!
