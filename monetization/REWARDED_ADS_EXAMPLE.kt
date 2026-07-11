// ============================================================================
// REWARDED ADS USAGE EXAMPLES
// ============================================================================

package tpt.dev.monetization.examples

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import hoang.dqm.codebase.base.activity.BaseActivity
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.utils.AppMonetization

// ============================================================================
// EXAMPLE 1: Simple Rewarded Ad in Activity
// ============================================================================
class ShopActivity : BaseActivity<ActivityShopBinding, ShopViewModel>() {
    
    override fun initView() {
        // Setup UI
    }
    
    override fun initListener() {
        // Button để xem ad nhận coins
        binding.btnWatchAdForCoins.setOnClickListener {
            watchAdForCoins()
        }
    }
    
    override fun initData() {
        // Load data
    }
    
    private fun watchAdForCoins() {
        // Cách 1: Direct Load & Show (đơn giản)
        showRewardedAd(
            onRewarded = { rewardItem ->
                // User đã xem xong ad, trao thưởng
                val coins = rewardItem.amount
                viewModel.addCoins(coins)
                Toast.makeText(this, "You earned $coins coins!", Toast.LENGTH_SHORT).show()
            },
            onLoadFailed = {
                // Ad không load được
                Toast.makeText(this, "Ad not available", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ============================================================================
// EXAMPLE 2: Preload Rewarded Ads in Main Activity
// ============================================================================
class MainActivity : BaseActivity<ActivityMainBinding, MainViewModel>() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Preload rewarded ads khi vào app
        preloadRewardedAds()
    }
    
    private fun preloadRewardedAds() {
        // Define các ad keys cần preload
        val adKeys = listOf(
            getString(R.string.admob_rewarded_coins),
            getString(R.string.admob_rewarded_premium),
            getString(R.string.admob_rewarded_unlock)
        )
        
        // Load với waterfall (có delay giữa các request)
        AppMonetization.ads.preloadRewardedManagement.loadWithWaterfall(
            activity = this,
            adKeys = adKeys,
            delayMs = 300L
        ) {
            // Callback khi tất cả ads đã load xong
            Log.d("MainActivity", "All rewarded ads preloaded")
        }
        
        // Setup backup ad (dự phòng)
        AppMonetization.ads.preloadRewardedManagement.enableBackup(
            activity = this,
            backupKey = getString(R.string.admob_rewarded_backup)
        )
    }
}

// ============================================================================
// EXAMPLE 3: Use Preloaded Ad in Fragment
// ============================================================================
class CoinsShopFragment : BaseFragment<FragmentCoinsShopBinding, CoinsShopViewModel>() {
    
    private val adKey by lazy { getString(R.string.admob_rewarded_coins) }
    
    override fun initView() {
        updateWatchAdButton()
    }
    
    override fun initListener() {
        binding.btnWatchAd.setOnClickListener {
            showPreloadedRewardedAd()
        }
    }
    
    override fun initData() {
        // Load data
    }
    
    private fun showPreloadedRewardedAd() {
        // Check xem ad đã load chưa
        if (!AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)) {
            Toast.makeText(requireContext(), "Ad is loading...", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show preloaded ad
        AppMonetization.ads.preloadRewardedManagement.show(
            activity = requireActivity(),
            adKey = adKey,
            reload = true, // Auto reload sau khi show
            onAdShowed = {
                // Ad đang hiển thị
            },
            onAdClosed = {
                // Ad đã đóng
                updateWatchAdButton()
            },
            onRewarded = { rewardItem ->
                // User earned reward
                val coins = rewardItem.amount
                viewModel.addCoins(coins)
                Toast.makeText(requireContext(), "You earned $coins coins!", Toast.LENGTH_SHORT).show()
            },
            onLoadFailed = {
                Toast.makeText(requireContext(), "Ad not available", Toast.LENGTH_SHORT).show()
                updateWatchAdButton()
            }
        )
    }
    
    private fun updateWatchAdButton() {
        // Update button state dựa trên ad availability
        val isAdReady = AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)
        binding.btnWatchAd.isEnabled = isAdReady
        binding.btnWatchAd.alpha = if (isAdReady) 1f else 0.5f
    }
}

// ============================================================================
// EXAMPLE 4: Game Continue with Rewarded Ad
// ============================================================================
class GameFragment : BaseFragment<FragmentGameBinding, GameViewModel>() {
    
    override fun initListener() {
        binding.btnContinue.setOnClickListener {
            continueGameWithAd()
        }
    }
    
    private fun continueGameWithAd() {
        showRewardedAd(
            onRewarded = { rewardItem ->
                // User xem xong ad, cho phép continue
                viewModel.addLife()
                viewModel.resumeGame()
                Toast.makeText(requireContext(), "Extra life granted!", Toast.LENGTH_SHORT).show()
            },
            onAdDismissed = {
                // User đóng ad mà không xem hết -> game over
                viewModel.endGame()
                navigateToGameOver()
            },
            onLoadFailed = {
                // Không có ad -> game over luôn
                viewModel.endGame()
                navigateToGameOver()
            }
        )
    }
    
    private fun navigateToGameOver() {
        // Navigate to game over screen
    }
}

// ============================================================================
// EXAMPLE 5: Unlock Premium Feature Temporarily
// ============================================================================
class SettingsFragment : BaseFragment<FragmentSettingsBinding, SettingsViewModel>() {
    
    override fun initListener() {
        binding.btnUnlockPremium.setOnClickListener {
            unlockPremiumWithAd()
        }
    }
    
    private fun unlockPremiumWithAd() {
        showRewardedAd(
            onRewarded = { rewardItem ->
                // Unlock premium for 24 hours
                val unlockDuration = 24 * 60 * 60 * 1000L // 24 hours
                val unlockUntil = System.currentTimeMillis() + unlockDuration
                
                // Save to preferences
                viewModel.setTemporaryPremiumUnlock(unlockUntil)
                
                Toast.makeText(
                    requireContext(), 
                    "Premium unlocked for 24 hours!", 
                    Toast.LENGTH_LONG
                ).show()
                
                // Update UI
                updatePremiumStatus()
            },
            onLoadFailed = {
                Toast.makeText(
                    requireContext(),
                    "Please try again later",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
    
    private fun updatePremiumStatus() {
        // Update UI based on premium status
    }
}

// ============================================================================
// EXAMPLE 6: Multiple Reward Options
// ============================================================================
class RewardCenterFragment : BaseFragment<FragmentRewardCenterBinding, RewardCenterViewModel>() {
    
    override fun initListener() {
        binding.btnWatch10Coins.setOnClickListener { watchAdForReward(RewardType.COINS_10) }
        binding.btnWatch50Coins.setOnClickListener { watchAdForReward(RewardType.COINS_50) }
        binding.btnWatchUnlockTheme.setOnClickListener { watchAdForReward(RewardType.UNLOCK_THEME) }
    }
    
    private fun watchAdForReward(rewardType: RewardType) {
        val adKey = when (rewardType) {
            RewardType.COINS_10 -> getString(R.string.admob_rewarded_coins_small)
            RewardType.COINS_50 -> getString(R.string.admob_rewarded_coins_large)
            RewardType.UNLOCK_THEME -> getString(R.string.admob_rewarded_theme)
        }
        
        AppMonetization.ads.preloadRewardedManagement.show(
            activity = requireActivity(),
            adKey = adKey,
            reload = true,
            onRewarded = { rewardItem ->
                processReward(rewardType, rewardItem)
            },
            onLoadFailed = {
                Toast.makeText(requireContext(), "Ad not available", Toast.LENGTH_SHORT).show()
            }
        )
    }
    
    private fun processReward(rewardType: RewardType, rewardItem: RewardItem) {
        when (rewardType) {
            RewardType.COINS_10 -> {
                viewModel.addCoins(10)
                showToast("You earned 10 coins!")
            }
            RewardType.COINS_50 -> {
                viewModel.addCoins(50)
                showToast("You earned 50 coins!")
            }
            RewardType.UNLOCK_THEME -> {
                viewModel.unlockTheme()
                showToast("Theme unlocked!")
            }
        }
    }
    
    enum class RewardType {
        COINS_10,
        COINS_50,
        UNLOCK_THEME
    }
}

// ============================================================================
// EXAMPLE 7: Check Ad Availability Before Showing Button
// ============================================================================
class DailyRewardsFragment : BaseFragment<FragmentDailyRewardsBinding, DailyRewardsViewModel>() {
    
    private val adKey by lazy { getString(R.string.admob_rewarded_daily) }
    
    override fun onResume() {
        super.onResume()
        
        // Preload ad khi vào fragment
        preloadAdIfNeeded()
        
        // Check và update UI
        updateAdButton()
    }
    
    private fun preloadAdIfNeeded() {
        if (!AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey) &&
            !AppMonetization.ads.preloadRewardedManagement.isLoading(adKey)) {
            
            AppMonetization.ads.preloadRewardedManagement.load(
                activity = requireActivity(),
                adKey = adKey
            ) {
                // Ad loaded, update UI
                updateAdButton()
            }
        }
    }
    
    private fun updateAdButton() {
        val isLoaded = AppMonetization.ads.preloadRewardedManagement.isLoaded(adKey)
        val isLoading = AppMonetization.ads.preloadRewardedManagement.isLoading(adKey)
        
        binding.btnWatchAd.apply {
            isEnabled = isLoaded
            text = when {
                isLoading -> "Loading..."
                isLoaded -> "Watch Ad for Reward"
                else -> "Ad Not Available"
            }
            alpha = if (isLoaded) 1f else 0.5f
        }
    }
    
    override fun initListener() {
        binding.btnWatchAd.setOnClickListener {
            showDailyRewardAd()
        }
    }
    
    private fun showDailyRewardAd() {
        AppMonetization.ads.preloadRewardedManagement.show(
            activity = requireActivity(),
            adKey = adKey,
            reload = true,
            onRewarded = {
                viewModel.claimDailyReward()
                Toast.makeText(requireContext(), "Daily reward claimed!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ============================================================================
// STRINGS.XML EXAMPLE
// ============================================================================
/*
<resources>
    <!-- Rewarded Ad IDs -->
    <string name="admob_rewarded_coins">ca-app-pub-xxxxx/xxxxx</string>
    <string name="admob_rewarded_premium">ca-app-pub-xxxxx/xxxxx</string>
    <string name="admob_rewarded_unlock">ca-app-pub-xxxxx/xxxxx</string>
    <string name="admob_rewarded_backup">ca-app-pub-xxxxx/xxxxx</string>
    
    <!-- Test ID for development -->
    <string name="admob_rewarded_test">ca-app-pub-3940256099942544/5224354917</string>
</resources>
*/
