# Rewarded Ads Integration Guide

## Overview
Rewarded Ads đã được tích hợp vào hệ thống ads với 2 cách sử dụng:
1. **Direct Load & Show** - Load và show ngay (existing `RewardedAdUtils`)
2. **Preload Management** - Preload trước và show khi cần (new `PreloadRewardedManagement`)

---

## 1. Direct Load & Show (RewardedAdUtils)

### Đặc điểm:
- Load ad ngay khi cần show
- Có loading dialog trong quá trình load
- Đơn giản, phù hợp cho các màn hình ít dùng rewarded ads

### Cách dùng trong BaseActivity:

```kotlin
class YourActivity : BaseActivity<ActivityYourBinding, YourViewModel>() {
    
    private fun showRewardedAd() {
        showRewardedAd(
            onAdShowed = {
                // Ad đã hiển thị
                Log.d("Rewarded", "Ad showed")
            },
            onAdDismissed = {
                // User đóng ad (có thể đã xem hết hoặc skip)
                Log.d("Rewarded", "Ad dismissed")
            },
            onLoadFailed = {
                // Load ad thất bại
                Toast.makeText(this, "Failed to load ad", Toast.LENGTH_SHORT).show()
            },
            onRewarded = { rewardItem ->
                // User đã xem hết ad và nhận reward
                val amount = rewardItem.amount
                val type = rewardItem.type
                Log.d("Rewarded", "User earned: $amount $type")
                
                // Thực hiện logic reward (coins, premium features, etc.)
                giveRewardToUser(amount, type)
            }
        )
    }
    
    private fun giveRewardToUser(amount: Int, type: String) {
        // Logic trao thưởng cho user
        // Ví dụ: cộng coins, unlock feature, v.v.
    }
}
```

### Cách dùng trong BaseFragment:

```kotlin
class YourFragment : BaseFragment<FragmentYourBinding, YourViewModel>() {
    
    private fun requestReward() {
        showRewardedAd(
            onRewarded = { rewardItem ->
                // User earned reward
                viewModel.addCoins(rewardItem.amount)
            },
            onLoadFailed = {
                // Show error message
                showToast("Ad not available")
            }
        )
    }
}
```

---

## 2. Preload Management (PreloadRewardedManagement)

### Đặc điểm:
- Preload nhiều ads trước
- Pool management (quản lý nhiều ads cùng lúc)
- Waterfall loading với delay
- Backup ad support
- Performance tốt hơn (không load khi show)

### Khởi tạo và Preload trong Application/Activity:

```kotlin
class MainActivity : BaseMainActivity<ActivityMainBinding, MainViewModel>() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload rewarded ads
        preloadRewardedAds()
    }
    
    private fun preloadRewardedAds() {
        val rewardedAdKeys = listOf(
            getString(R.string.admob_rewarded_coins),
            getString(R.string.admob_rewarded_unlock),
            getString(R.string.admob_rewarded_premium)
        )
        
        // Load với waterfall (delay giữa các request)
        AppMonetization.ads.preloadRewardedManagement.loadWithWaterfall(
            activity = this,
            adKeys = rewardedAdKeys,
            delayMs = 300L
        ) {
            Log.d("Rewarded", "All rewarded ads preloaded")
        }
        
        // Enable backup ad
        AppMonetization.ads.preloadRewardedManagement.enableBackup(
            activity = this,
            backupKey = getString(R.string.admob_rewarded_backup)
        )
    }
}
```

### Sử dụng Preloaded Ads:

```kotlin
class ShopFragment : BaseFragment<FragmentShopBinding, ShopViewModel>() {
    
    private fun watchAdForCoins() {
        val adKey = getString(R.string.admob_rewarded_coins)
        
        // Kiểm tra ad đã load chưa
        if (!AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)) {
            showToast("Ad is not ready yet")
            return
        }
        
        // Show ad
        AppMonetization.ads.preloadRewardedManagement.show(
            activity = requireActivity(),
            adKey = adKey,
            reload = true, // Auto reload sau khi show
            onAdShowed = {
                Log.d("Rewarded", "Ad showing")
            },
            onAdClosed = {
                Log.d("Rewarded", "Ad closed")
            },
            onRewarded = { rewardItem ->
                // User earned reward
                val coins = rewardItem.amount
                viewModel.addCoins(coins)
                showToast("You earned $coins coins!")
            },
            onLoadFailed = {
                showToast("Ad not available")
            }
        )
    }
    
    // Kiểm tra trạng thái ad để enable/disable button
    private fun updateWatchAdButton() {
        val adKey = getString(R.string.admob_rewarded_coins)
        val isAdReady = AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)
        
        binding.btnWatchAd.isEnabled = isAdReady
        binding.btnWatchAd.alpha = if (isAdReady) 1f else 0.5f
    }
}
```

---

## 3. Use Cases & Best Practices

### Use Case 1: Earn Coins
```kotlin
private fun earnCoinsWithAd() {
    showRewardedAd(
        onRewarded = { rewardItem ->
            val coins = rewardItem.amount
            // Save coins to database/preferences
            viewModel.addCoins(coins)
            
            // Update UI
            updateCoinsDisplay()
            
            // Analytics
            logEvent("rewarded_ad_coins_earned", coins)
        },
        onLoadFailed = {
            // Fallback: maybe offer alternative way
            showAlternativeRewardOptions()
        }
    )
}
```

### Use Case 2: Unlock Premium Feature
```kotlin
private fun unlockFeatureWithAd() {
    showRewardedAd(
        onRewarded = { rewardItem ->
            // Unlock feature for limited time
            val unlockDuration = 24 * 60 * 60 * 1000L // 24 hours
            val unlockUntil = System.currentTimeMillis() + unlockDuration
            
            preferences.setUnlockUntil(unlockUntil)
            
            // Navigate to unlocked feature
            navigateToFeature()
        }
    )
}
```

### Use Case 3: Extra Lives/Continues
```kotlin
private fun continueGameWithAd() {
    showRewardedAd(
        onRewarded = {
            // Give extra life
            gameViewModel.addLife()
            gameViewModel.resumeGame()
        },
        onAdDismissed = {
            // Ad closed without reward
            gameViewModel.endGame()
            navigateToGameOver()
        },
        onLoadFailed = {
            // Ad not available, just end game
            gameViewModel.endGame()
            navigateToGameOver()
        }
    )
}
```

---

## 4. Advanced: Preload Strategy

### Strategy 1: Preload on App Start
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Sau khi khởi tạo ads
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                preloadFrequentAds()
            }
        })
    }
    
    private fun preloadFrequentAds() {
        val mainActivity = /* get main activity */
        val adKeys = listOf(
            getString(R.string.admob_rewarded_main),
            getString(R.string.admob_rewarded_shop)
        )
        
        AppMonetization.ads.preloadRewardedManagement.loadWithWaterfall(
            activity = mainActivity,
            adKeys = adKeys
        )
    }
}
```

### Strategy 2: Preload Before Show
```kotlin
class ShopFragment : BaseFragment<FragmentShopBinding, ShopViewModel>() {
    
    override fun onResume() {
        super.onResume()
        // Preload ngay khi vào fragment
        preloadRewardedAd()
    }
    
    private fun preloadRewardedAd() {
        val adKey = getString(R.string.admob_rewarded_shop)
        
        if (!AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)) {
            AppMonetization.ads.preloadRewardedManagement.load(
                activity = requireActivity(),
                adKey = adKey
            )
        }
    }
}
```

---

## 5. Tips & Notes

### ✅ DO:
1. **Always handle onLoadFailed** - Ad có thể không load được
2. **Validate reward** - Kiểm tra reward trước khi trao
3. **Show loading state** - UI feedback khi đang load ad
4. **Preload frequently used ads** - Tăng UX
5. **Use backup ads** - Đảm bảo luôn có ad để show

### ❌ DON'T:
1. **Không spam rewarded ads** - Giới hạn số lần xem/ngày
2. **Không force user xem ads** - Phải voluntary
3. **Không trao reward ngay trong onAdShowed** - Phải đợi onRewarded
4. **Không preload quá nhiều ads** - Tốn tài nguyên
5. **Không show ad khi app inactive** - Check lifecycle state

### Common Pitfalls:
```kotlin
// ❌ WRONG: Trao reward sớm
onAdShowed = {
    giveReward() // SAI! User có thể skip ad
}

// ✅ CORRECT: Trao reward đúng chỗ
onRewarded = { rewardItem ->
    giveReward(rewardItem.amount)
}
```

---

## 6. Testing

### Test Rewarded Ads:
```kotlin
// Sử dụng test ad unit ID
val TEST_REWARDED_AD_ID = "ca-app-pub-3940256099942544/5224354917"

// Hoặc add test device
AppMonetization.ads.addTestDevice("YOUR_DEVICE_ID")
```

### Debug Logs:
```kotlin
// Enable debug để xem logs
adb logcat | grep "PreloadRewarded"
adb logcat | grep "RewardedAdUtils"
```

---

## 7. Kết luận

### Khi nào dùng gì?

| Tình huống | Nên dùng |
|------------|----------|
| Ad ít khi dùng, không quan trọng timing | `RewardedAdUtils` (Direct) |
| Ad dùng nhiều, cần show ngay không delay | `PreloadRewardedManagement` |
| Multiple ad placements trong app | `PreloadRewardedManagement` |
| App có shop/coins system | `PreloadRewardedManagement` |
| One-time reward screens | `RewardedAdUtils` |

### Quick Reference:

**Direct Load:**
```kotlin
showRewardedAd(onRewarded = { /* reward user */ })
```

**Preload:**
```kotlin
// 1. Preload
ads.preloadRewardedManagement.load(activity, adKey)

// 2. Check
if (ads.preloadRewardedManagement.isLoaded(adKey)) { ... }

// 3. Show
ads.preloadRewardedManagement.show(activity, adKey, onRewarded = { ... })
```
