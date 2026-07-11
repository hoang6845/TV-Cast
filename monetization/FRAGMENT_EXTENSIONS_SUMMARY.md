# Fragment Extensions Summary

## ✅ Hoàn thành

Đã tạo **Fragment Extension Functions** để show ads trực tiếp từ Fragment.

## 📦 Files đã tạo

### 1. FragmentExtensions.kt ✅
**Path**: `monetization/src/main/java/tpt/dev/monetization/ads/preload/FragmentExtensions.kt`

**Content**: Extension functions cho Fragment để:
- Load và show Interstitial ads
- Load và get Native ads  
- Load và show Banner ads
- Enable backup ads
- Check ad status

### 2. FRAGMENT_USAGE_GUIDE.md ✅
**Path**: `monetization/FRAGMENT_USAGE_GUIDE.md`

**Content**: Hướng dẫn chi tiết cách sử dụng từ Fragment

### 3. SplashFragment.kt ✅ (Updated)
**Path**: `app/src/main/java/com/silverlabtech/iptv/smartplayer/ui/splash/SplashFragment.kt`

**Changes**: Sử dụng Fragment Extensions thay vì gọi trực tiếp AdsManager

## 🎯 Cách sử dụng

### Import
```kotlin
import tpt.dev.monetization.ads.preload.*
```

### Show Interstitial từ Fragment
```kotlin
class HomeFragment : Fragment() {
    fun navigateWithAd() {
        showInterstitialAd(
            adKey = "ca-app-pub-xxx/inter",
            reload = true,
            onAdClosed = { success ->
                // Navigate
                findNavController().navigate(R.id.detailFragment)
            }
        )
    }
}
```

### Load Native từ Fragment
```kotlin
class ListFragment : Fragment() {
    fun loadNative() {
        loadNativeAd(
            adKey = "ca-app-pub-xxx/native",
            numberOfAds = 1
        ) { nativeAd ->
            if (nativeAd != null) {
                nativeAdView.setNativeAd(nativeAd)
            }
        }
    }
}
```

### Show Banner từ Fragment
```kotlin
class HomeFragment : Fragment() {
    fun showBanner() {
        val success = showBannerOrBackup(
            adKey = "ca-app-pub-xxx/banner",
            container = bannerContainer,
            removeAfterShow = false
        )
    }
}
```

## 📋 API Reference

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

## ✨ Benefits

### 1. Clean Code
```kotlin
// Before
AdsManager.getInstance().preloadInterstitialManagement.show(
    activity = requireActivity(),
    adKey = "key",
    ...
)

// After
showInterstitialAd("key") { success ->
    // Handle
}
```

### 2. Null Safety
```kotlin
// Extension tự động check activity != null
// Nếu fragment chưa attached, callback sẽ được gọi với null/false
```

### 3. Easy to Use
```kotlin
// Chỉ cần import và gọi trực tiếp
import tpt.dev.monetization.ads.preload.*

showInterstitialAd("key") { }
```

## 🎉 Summary

- ✅ Tạo Fragment Extensions
- ✅ Update SplashFragment sử dụng extensions
- ✅ Tạo documentation đầy đủ
- ✅ Null-safe và easy to use

**Ready to use! 🚀**
