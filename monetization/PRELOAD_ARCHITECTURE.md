# Preload Ads System - Architecture

## 📐 Kiến Trúc Tổng Quan

```
┌─────────────────────────────────────────────────────────────┐
│                        AdsManager                            │
│  (Singleton - Entry point cho toàn bộ ads system)           │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ contains
                            ▼
        ┌───────────────────────────────────────┐
        │      Preload Managers (Lazy Init)     │
        └───────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   Preload    │   │   Preload    │   │   Preload    │
│   Native     │   │Interstitial  │   │   Banner     │
│  AdManager   │   │  AdManager   │   │  AdManager   │
└──────────────┘   └──────────────┘   └──────────────┘
        │                   │                   │
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  NativeAd    │   │Interstitial  │   │   AdView     │
│   Cache      │   │    Cache     │   │   Cache      │
└──────────────┘   └──────────────┘   └──────────────┘
```

## 🏗️ Component Details

### 1. AdsManager (Singleton)

**Vai trò:** Entry point và coordinator cho toàn bộ ads system

**Responsibilities:**
- Khởi tạo và quản lý lifecycle của ads
- Cung cấp access đến các preload managers
- Quản lý consent và premium status
- Coordinate giữa các loại ads

**Key Properties:**
```kotlin
class AdsManager {
    val preloadNativeAdManager: PreloadNativeAdManager
    val preloadInterstitialAdManager: PreloadInterstitialAdManager
    val preloadBannerAdManager: PreloadBannerAdManager
    
    // Legacy managers (backward compatibility)
    val singleNativeAdUtils: SingleNativeAdUtils
    val interstitialAdUtils: InterstitialAdUtils
    val bannerAdUtils: BannerAdUtils
}
```

### 2. PreloadNativeAdManager

**Vai trò:** Quản lý preload và cache Native Ads

**Architecture:**
```
PreloadNativeAdManager
├── adsCache: Map<String, NativeAd>
├── loadingJobs: Map<String, Job>
└── scope: CoroutineScope
```

**Key Features:**
- ✅ Preload multiple native ads
- ✅ Cache management với placement key
- ✅ Auto reload sau khi lấy ad
- ✅ Timeout và retry logic
- ✅ Thread-safe với coroutines

**Flow Diagram:**
```
┌─────────────┐
│   Preload   │
│   Request   │
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌──────────────┐
│   Check     │────▶│   Premium?   │──Yes──▶ Skip
│  Consent    │     └──────────────┘
└──────┬──────┘              │
       │                     No
       ▼                     │
┌─────────────┐              │
│   Already   │              │
│   Cached?   │──Yes──▶ Return
└──────┬──────┘              │
       │                     │
       No                    │
       │                     │
       ▼                     ▼
┌─────────────┐     ┌──────────────┐
│   Start     │────▶│  Load with   │
│   Loading   │     │   Timeout    │
└─────────────┘     └──────┬───────┘
                           │
                    ┌──────┴──────┐
                    │             │
                Success        Failure
                    │             │
                    ▼             ▼
            ┌──────────┐   ┌──────────┐
            │  Cache   │   │  Retry   │
            │   Ad     │   │  Logic   │
            └──────────┘   └──────────┘
```

### 3. PreloadInterstitialAdManager

**Vai trò:** Quản lý preload, cache và show Interstitial Ads

**Architecture:**
```
PreloadInterstitialAdManager
├── adsCache: Map<String, InterstitialAd>
├── loadingJobs: Map<String, Job>
├── lastShowTime: Long
├── showInterval: Long
├── isShowingAd: Boolean
└── scope: CoroutineScope
```

**Key Features:**
- ✅ Preload multiple interstitial ads
- ✅ Show với capping control
- ✅ Force show option
- ✅ Auto reload sau khi show
- ✅ Status bar management
- ✅ Loading dialog

**Show Flow Diagram:**
```
┌─────────────┐
│    Show     │
│   Request   │
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌──────────────┐
│   Check     │────▶│   Premium?   │──Yes──▶ Skip
│  Consent    │     └──────────────┘
└──────┬──────┘              │
       │                     No
       ▼                     │
┌─────────────┐              │
│  Already    │              │
│  Showing?   │──Yes──▶ Skip │
└──────┬──────┘              │
       │                     │
       No                    │
       │                     │
       ▼                     ▼
┌─────────────┐     ┌──────────────┐
│  Capping    │────▶│ Force Show?  │──Yes──▶ Show
│   Check     │     └──────────────┘
└──────┬──────┘              │
       │                     No
    Passed                   │
       │                     │
       ▼                     ▼
┌─────────────┐         ┌────────┐
│    Show     │         │  Skip  │
│   Dialog    │         └────────┘
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Hide     │
│ Status Bar  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Show     │
│     Ad      │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Restore   │
│ Status Bar  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Auto     │
│   Reload    │
└─────────────┘
```

### 4. PreloadBannerAdManager

**Vai trò:** Quản lý preload, cache và auto refresh Banner Ads

**Architecture:**
```
PreloadBannerAdManager
├── adsCache: Map<String, AdView>
├── loadingJobs: Map<String, Job>
├── refreshJobs: Map<String, Job>
└── scope: CoroutineScope
```

**Key Features:**
- ✅ Preload adaptive banner ads
- ✅ Auto refresh với interval
- ✅ Cache AdView instances
- ✅ Support Android 11+ và legacy
- ✅ Container management

**Auto Refresh Flow:**
```
┌─────────────┐
│   Preload   │
│   Banner    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Load     │
│   AdView    │
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌──────────────┐
│   Cache     │────▶│Auto Refresh? │──No──▶ Done
│   AdView    │     └──────────────┘
└─────────────┘              │
                            Yes
                             │
                             ▼
                    ┌──────────────┐
                    │    Start     │
                    │ Refresh Job  │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │    Wait      │
                    │  Interval    │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │   Reload     │
                    │   AdView     │
                    └──────┬───────┘
                           │
                           └──────▶ Loop
```

## 🔄 Data Flow

### Preload Flow

```
User Action
    │
    ▼
Activity/Fragment
    │
    ▼
AdsManager.preloadXxxAdManager
    │
    ▼
PreloadXxxAdManager.preloadAd()
    │
    ├──▶ Check Premium ──▶ Skip if premium
    │
    ├──▶ Check Consent ──▶ Skip if no consent
    │
    ├──▶ Check Cache ──▶ Return if cached
    │
    └──▶ Start Loading
         │
         ├──▶ Create Coroutine Job
         │
         ├──▶ Load Ad with Timeout
         │
         ├──▶ Retry on Failure (max 2 times)
         │
         └──▶ Cache on Success
              │
              └──▶ Callback onLoadComplete
```

### Show Flow (Interstitial)

```
User Action
    │
    ▼
Activity
    │
    ▼
AdsManager.preloadInterstitialAdManager
    │
    ▼
showInterstitialAd()
    │
    ├──▶ Check Premium ──▶ Skip if premium
    │
    ├──▶ Check isShowingAd ──▶ Skip if showing
    │
    ├──▶ Check Capping ──▶ Skip if not passed (unless forceShow)
    │
    ├──▶ Get Ad from Cache ──▶ Skip if not cached
    │
    └──▶ Show Ad
         │
         ├──▶ Show Loading Dialog
         │
         ├──▶ Hide Status Bar
         │
         ├──▶ Show InterstitialAd
         │
         ├──▶ Wait for Dismiss
         │
         ├──▶ Restore Status Bar
         │
         ├──▶ Auto Reload
         │
         └──▶ Callback onAdClosed
```

## 🧵 Threading Model

### Coroutines Usage

```kotlin
// Main scope for UI operations
private val scope = CoroutineScope(Dispatchers.Main)

// Load ad in background
scope.launch {
    // Retry logic
    while (retryCount < MAX_RETRY && !loadSuccess) {
        try {
            loadSuccess = loadAdInternal() // Suspend function
            if (!loadSuccess) {
                delay(1000L * retryCount) // Exponential backoff
            }
        } catch (e: Exception) {
            // Handle error
        }
    }
}

// Timeout handling
val timeoutJob = scope.launch {
    delay(DEFAULT_TIMEOUT)
    if (!isCompleted) {
        // Handle timeout
    }
}
```

### Thread Safety

- ✅ All cache operations on Main thread
- ✅ Coroutines for async operations
- ✅ Synchronized blocks for shared state
- ✅ AtomicBoolean for flags

## 💾 Cache Management

### Cache Structure

```kotlin
// Key: Placement Key (e.g., "home_native")
// Value: Ad Instance (NativeAd, InterstitialAd, AdView)
private val adsCache = mutableMapOf<String, AdType>()

// Loading jobs tracking
private val loadingJobs = mutableMapOf<String, Job>()

// Refresh jobs (Banner only)
private val refreshJobs = mutableMapOf<String, Job>()
```

### Cache Lifecycle

```
┌─────────────┐
│   Preload   │
│   Request   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Load     │
│     Ad      │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Cache    │
│     Ad      │
└──────┬──────┘
       │
       ├──▶ Get Ad ──▶ Remove from Cache
       │
       ├──▶ Auto Reload ──▶ Load Again
       │
       └──▶ Clear Cache ──▶ Destroy Ad
```

## 🔐 Security & Privacy

### Premium Check

```kotlin
if (premiumManager.isSubscribed()) {
    // Skip ads for premium users
    return
}
```

### Consent Check

```kotlin
if (!googleMobileAdsConsentManager.canRequestAds) {
    // Skip ads if consent not granted
    return
}
```

## 📊 Performance Considerations

### Memory Management

- ✅ Lazy initialization của managers
- ✅ Clear cache khi không dùng
- ✅ Destroy ads properly
- ✅ Cancel jobs khi không cần

### Network Optimization

- ✅ Preload trước khi cần
- ✅ Retry với exponential backoff
- ✅ Timeout để tránh block
- ✅ Parallel loading cho multiple ads

### UI Performance

- ✅ Async loading với coroutines
- ✅ Loading dialog để feedback
- ✅ Smooth transitions
- ✅ No blocking operations

## 🔄 Backward Compatibility

Hệ thống mới **không thay thế** các managers cũ, mà **bổ sung thêm**:

```kotlin
class AdsManager {
    // Legacy managers (vẫn hoạt động)
    val singleNativeAdUtils: SingleNativeAdUtils
    val interstitialAdUtils: InterstitialAdUtils
    val bannerAdUtils: BannerAdUtils
    
    // New preload managers
    val preloadNativeAdManager: PreloadNativeAdManager
    val preloadInterstitialAdManager: PreloadInterstitialAdManager
    val preloadBannerAdManager: PreloadBannerAdManager
}
```

Developers có thể:
- ✅ Tiếp tục dùng legacy managers
- ✅ Migrate dần sang preload managers
- ✅ Mix cả hai approaches

## 🎯 Design Principles

### 1. Single Responsibility
Mỗi manager chỉ quản lý một loại ad

### 2. Separation of Concerns
- Loading logic tách biệt với showing logic
- Cache management tách biệt với ad loading
- Lifecycle management tách biệt với business logic

### 3. Dependency Injection
Managers nhận dependencies qua constructor:
```kotlin
class PreloadNativeAdManager(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
)
```

### 4. Fail-Safe
- Luôn có fallback khi ads không load
- Graceful degradation
- No crashes on errors

### 5. Developer-Friendly
- Simple API
- Clear naming
- Comprehensive documentation
- Example code

## 📈 Scalability

Hệ thống được thiết kế để dễ dàng mở rộng:

### Thêm loại ad mới

```kotlin
class PreloadRewardedAdManager(
    private val context: Context,
    private val premiumManager: IPremiumManager,
    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager
) {
    // Similar structure to other managers
}

// Add to AdsManager
class AdsManager {
    val preloadRewardedAdManager: PreloadRewardedAdManager by lazy {
        PreloadRewardedAdManager(application, premiumManager, googleMobileAdsConsentManager)
    }
}
```

### Thêm features mới

```kotlin
// Add priority loading
fun preloadWithPriority(
    placements: List<Pair<String, Int>>, // (key, priority)
    onComplete: (Map<String, Boolean>) -> Unit
) {
    // Sort by priority and load
}

// Add batch operations
fun preloadBatch(
    placements: List<String>,
    batchSize: Int = 3
) {
    // Load in batches to avoid overwhelming
}
```

## 🔍 Monitoring & Debugging

### Logging

Tất cả managers có comprehensive logging:

```kotlin
Log.d(TAG, "Start preload: $placementKey")
Log.d(TAG, "Preload completed: $placementKey, success=$loadSuccess")
Log.e(TAG, "Error loading ad: $placementKey", e)
```

### Status Checking

```kotlin
// Check cache status
val cachedCount = manager.getCachedCount()
val cachedKeys = manager.getCachedKeys()

// Check loading status
val isLoading = manager.isLoading(placementKey)
val isLoaded = manager.isAdLoaded(placementKey)
```

---

**Architecture Version:** 1.0  
**Last Updated:** 2026-04-29  
**Maintainer:** Monetization Team
