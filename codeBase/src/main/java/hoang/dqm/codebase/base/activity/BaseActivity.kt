package hoang.dqm.codebase.base.activity

import android.content.Context
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import com.google.android.gms.ads.nativead.NativeAd
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import hoang.dqm.codebase.service.network.NetworkStatusReceiver
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.BindingReflex
import hoang.dqm.codebase.utils.ads
import tpt.dev.monetization.ads.nativeAd.view.ViewNativeAd
import java.lang.reflect.ParameterizedType
import kotlin.time.Duration.Companion.seconds

abstract class BaseActivity<VB : ViewBinding, VM : BaseViewModel> : AppCompatActivity(),
    View.OnClickListener {
    val binding: VB by lazy(mode = LazyThreadSafetyMode.NONE) {
        BindingReflex.reflexViewBinding(javaClass, layoutInflater)
    }
    private var lastTimeLoadBannerNativeAd = 0L
    private var nativeAd: NativeAd? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentFocusedView = currentFocus

            if (currentFocusedView is EditText) {
                val outRect = Rect()
                currentFocusedView.getGlobalVisibleRect(outRect)

                val x = event.rawX.toInt()
                val y = event.rawY.toInt()

                if (!outRect.contains(x, y)) {
                    val newFocusView = findViewAt(binding.root, x, y)

                    // ✅ Chỉ hide keyboard khi KHÔNG click vào EditText khác
                    if (newFocusView !is EditText) {
                        currentFocusedView.clearFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(currentFocusedView.windowToken, 0)
                    }
                    // ✅ Nếu click EditText khác → không làm gì,
                    //    hệ thống tự chuyển focus + giữ keyboard
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun findViewAt(view: View, x: Int, y: Int): View? {
        if (view !is ViewGroup) {
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            return if (rect.contains(x, y)) view else null
        }

        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val result = findViewAt(child, x, y)
            if (result != null) return result
        }

        return null
    }

    open var onPermissionsGranted: (() -> Unit)? = null

    protected open val viewModelFactory: ViewModelProvider.Factory? = null
    protected val viewModel: VM by lazy {
        val clazz = getGenericSuperclass<VM>(1)
        if (viewModelFactory != null) {
            ViewModelProvider(this, viewModelFactory!!)[clazz]
        } else {
            ViewModelProvider(this)[clazz]
        }
    }

    private fun <KClass> Context.getGenericSuperclass(position: Int): Class<KClass> {
        val baseType = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments
        return baseType[position] as Class<KClass>
    }

    private var networkStatusReceiver: NetworkStatusReceiver? = null

    private fun registerNetwork() {
        try {
            if (null == networkStatusReceiver) {
                networkStatusReceiver = NetworkStatusReceiver()
            }
            networkStatusReceiver?.let { networkStatusReceiver ->
                if (networkStatusReceiver.isOrderedBroadcast.not()) {
                    val filter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
                    registerReceiver(networkStatusReceiver, filter)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            networkStatusReceiver?.let {
                unregisterReceiver(networkStatusReceiver)
                networkStatusReceiver = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetwork()
    }

    abstract fun initView()
    abstract fun initListener()
    abstract fun initData()

    override fun onClick(v: View?) {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ActivityManager.addActivity(this)
            lifecycle.addObserver(viewModel)
            setContentView(binding.root)
            if (isHideNavBar()) {
                hideNavBar()
            }
            initView()
            initListener()
            initData()
            val className = this@BaseActivity::class.java.name
//            if (appInfo().isDebug) {
//                logApp("SCREEN_APP $className")
//            }
            onBackPressedDispatcher()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            lifecycle.removeObserver(viewModel)
            ActivityManager.removeActivity(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleBackExit()
        }
    }

    open fun handleBackExit() {
        finish()
    }

    open fun onBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    open fun handleOnBackPressed() {
        onBackPressedCallback.handleOnBackPressed()
    }

    protected fun <T> MutableLiveData<T>.observe(function: (T) -> Unit) {
        this.observe(this@BaseActivity) {
            function.invoke(it)
        }
    }

    protected fun <T> MutableLiveData<T>.notifyObserver() {
        this.value = this.value
    }

    protected fun hideNavBar() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).also { controller ->
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun adjustInsetsForBottomNavigation(viewBottom: View) {
        ViewCompat.setOnApplyWindowInsetsListener(viewBottom) { view, insets ->
            try {
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                val displayCutout =
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                params.topMargin = (displayCutout.top + viewBottom.top / 5f).toInt()
                view.layoutParams = params
            } catch (e: Exception) {
                e.printStackTrace()
            }
            insets
        }
    }

    protected fun adjustInsetsFullBottomNavigation(viewBottom: View) {
        ViewCompat.setOnApplyWindowInsetsListener(viewBottom) { view, insets ->
            try {
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                val displayCutout =
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                params.topMargin = displayCutout.top
                params.leftMargin = displayCutout.left
                params.rightMargin = displayCutout.right
                params.bottomMargin = displayCutout.bottom
                view.layoutParams = params
            } catch (e: Exception) {
                e.printStackTrace()
            }
            insets
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isHideNavBar()) {
            hideNavBar()
        }
    }

    protected fun showNavBar() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    protected fun isHideNavBar(): Boolean {
        return true
    }

    protected fun currentLanguage(): String {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags()
    }

    protected fun updateStatusBarAppearance() {
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Set màu chữ/icon
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
        }

        // Set nền status bar bằng cách thêm scrim view
        val decorView = window.decorView as ViewGroup
        var statusBarView = decorView.findViewWithTag<View>("status_bar_scrim")

        if (statusBarView == null) {
            statusBarView = View(this).apply {
                tag = "status_bar_scrim"
                decorView.addView(
                    this, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0
                    )
                )
            }
        }

        val color = if (isDarkMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        statusBarView.setBackgroundColor(color)

        // Set đúng height = status bar height
        ViewCompat.setOnApplyWindowInsetsListener(statusBarView) { view, insets ->
            view.layoutParams.height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(statusBarView)
    }

    fun showInterstitialAd(
        onAdsClosed: () -> Unit
    ) {
        AppMonetization.ads.interstitialAdUtils.showAd(
            activity = this,
            onAdsClosed = {
                onAdsClosed()
            },
            onAdsShowed = {}
        )
    }

    fun showRewardedAd(
        onAdShowed: () -> Unit = {},
        onAdDismissed: () -> Unit = {},
        onLoadFailed: () -> Unit = {},
        onRewarded: (com.google.android.gms.ads.rewarded.RewardItem) -> Unit
    ) {
        AppMonetization.ads.rewardedAdUtils.showRewardedAd(
            activity = this,
            onAdShowed = onAdShowed,
            onAdDismissed = onAdDismissed,
            onLoadFailed = onLoadFailed,
            onRewarded = onRewarded
        )
    }

    private fun updateLastTimeLoadBannerNativeAd(interval: Long) {
        lastTimeLoadBannerNativeAd = interval
    }

    fun loadSingleNative(
        viewNativeAd: ViewNativeAd,
        adId: Int,
        updateTimeout: Boolean = true,
        onAdsLoaded: () -> Unit = {},
        onLoadFailed: () -> Unit = {},
    ) {
        if (viewModel.isSubscribed || System.currentTimeMillis() - lastTimeLoadBannerNativeAd < 10.seconds.inWholeMilliseconds) {
            viewNativeAd.visibility = View.GONE
            return
        }
        if (updateTimeout) {
            updateLastTimeLoadBannerNativeAd(System.currentTimeMillis())
        }
        AppMonetization.ads.singleNativeAdUtils.loadAd(
            activity = this,
            adId = getString(adId),
            numberOfAdsToLoad = 1,
            onLoadFailed = { stringFail ->
                viewNativeAd.visibility = View.GONE
                if (!isFinishing && !isDestroyed) onLoadFailed.invoke()
//                Toast.makeText(this, stringFail, Toast.LENGTH_SHORT).show()
//                copyToClipboard(this, stringFail?: "")
            },
            onAdLoaded = {
                if (!isFinishing && !isDestroyed) {
                    nativeAd?.destroy()
                    nativeAd = it
                    viewNativeAd.populate(it)
                    onAdsLoaded.invoke()
                } else {
                    it.destroy() // tránh memory leak
                }
            }
        )
    }
}