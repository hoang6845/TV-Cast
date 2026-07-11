package hoang.dqm.codebase.base.activity

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import com.google.android.gms.ads.nativead.NativeAd
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.BindingReflex
import hoang.dqm.codebase.utils.ads
import tpt.dev.monetization.ads.nativeAd.view.ViewNativeAd
import java.lang.reflect.ParameterizedType
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
abstract class BaseFragment<VB : ViewBinding, VM : BaseViewModel> : Fragment(),
    View.OnClickListener, DefaultLifecycleObserver {

    private var isLoaded = false
    private var lastClickTime = 0L
    private var lastTimeLoadBannerNativeAd = 0L
    private var nativeAd: NativeAd? = null

    private val interval by lazy { 500L }
    protected var _binding: VB? = null
    protected val binding
        get() = _binding ?: BindingReflex.reflexViewBinding(
            javaClass, layoutInflater
        )

    @Suppress("UNCHECKED_CAST")
    private fun <KClass> genericViewModel(): Class<KClass> {
        return (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[1] as Class<KClass>
    }

    protected open val viewModelFactory: ViewModelProvider.Factory
        get() = defaultViewModelProviderFactory

//    protected val viewModel: VM by lazy {
//        val clazz = genericViewModel<VM>()
//        if (viewModelFactory != null) {
//            ViewModelProvider(this, viewModelFactory!!)[clazz]
//        } else {
//            ViewModelProvider(this)[clazz]
//        }
//    }

    protected val viewModel: VM by lazy {
        val clazz = genericViewModel<VM>()
        ViewModelProvider(this, viewModelFactory)[clazz]
    }

    protected var activity: Activity? = null

    abstract fun initView()
    abstract fun initListener()
    abstract fun initData()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity = requireActivity()
        FragmentManager.addFragment(this)
    }

    override fun onPause() {
        super<Fragment>.onPause()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        try {
            lifecycle.addObserver(viewModel)
            _binding = BindingReflex.reflexViewBinding(javaClass, inflater)
            return _binding?.root
        } catch (e: Exception) {
            e.printStackTrace()
            return View(context)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("SCREEN_APP", this::class.java.name)
        try {
//            trackingScreen()
            initView()
            initListener()
            viewModel.isLoading.observe { showLoading(it) }
            if (!isLoaded) {
                initData()
                isLoaded = true
            }
//            logApp("onViewCreated $this")
//            if (appInfo().isHomeClass(this::class.java.name)) {
//                saveFirst(false)
//            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super<Fragment>.onResume()
    }

    open fun idFragmentContainer(): Int = 0

    override fun onDestroy() {
        isLoaded = false
        super<Fragment>.onDestroy()
        FragmentManager.removeFragment(this)
    }

    override fun onDetach() {
        _binding = null
        lifecycle.removeObserver(this)
        super.onDetach()
    }

    override fun onClick(v: View) {
        val nowTime = System.currentTimeMillis()
        if (nowTime - lastClickTime > interval) {
            onSingleClickFrag(v)
            lastClickTime = nowTime
        }
    }

    open fun onSingleClickFrag(v: View) {

    }

    protected fun <T> MutableLiveData<T>.observe(function: (T) -> Unit) {
        this.observe(viewLifecycleOwner) {
            function.invoke(it)
        }
    }

    protected fun <T> MutableLiveData<T>.notifyObserver() {
        this.value = this.value
    }

    fun showLoading(show: Boolean) {
    }

    fun showForceLoading(show: Boolean) {
    }

    override fun onDestroyView() {
        showLoading(false)
        super.onDestroyView()
    }

    fun showInterstitialAd(onAdsClosed: () -> Unit) {
        (activity as? AppCompatActivity)?.let { activity ->
            AppMonetization.ads.interstitialAdUtils.showAd(
                activity,
                onAdsClosed = {
                    onAdsClosed()
                },
                onAdsShowed = {}
            )
        }
    }

    fun showRewardedAd(
        onAdShowed: () -> Unit = {},
        onAdDismissed: () -> Unit = {},
        onLoadFailed: () -> Unit = {},
        onRewarded: (com.google.android.gms.ads.rewarded.RewardItem) -> Unit
    ) {
        (activity as? AppCompatActivity)?.let { activity ->
            AppMonetization.ads.rewardedAdUtils.showRewardedAd(
                activity = activity,
                onAdShowed = onAdShowed,
                onAdDismissed = onAdDismissed,
                onLoadFailed = onLoadFailed,
                onRewarded = onRewarded
            )
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
    private fun updateLastTimeLoadBannerNativeAd(interval: Long) {
        lastTimeLoadBannerNativeAd = interval
    }
    
    /**
     * Load Native Ad mới (fallback method)
     * Dùng khi không có preloaded ad
     */
    fun loadSingleNative(
        viewNativeAd: ViewNativeAd,
        adId: Int ,
        updateTimeout: Boolean = true
    ) {
        if (viewModel.isSubscribed || System.currentTimeMillis() - lastTimeLoadBannerNativeAd < 10.seconds.inWholeMilliseconds) {
            return
        }
        if (updateTimeout) {
            updateLastTimeLoadBannerNativeAd(System.currentTimeMillis())
        }
        AppMonetization.ads.singleNativeAdUtils.loadAd(
            activity = requireActivity(),
            adId = getString(adId),
            numberOfAdsToLoad = 1,
            onLoadFailed = { stringFail ->
                updateLastTimeLoadBannerNativeAd(0L)

            },
            onAdLoaded = {
                nativeAd?.destroy()
                nativeAd = it
                if (isAdded && view != null) {
                    viewNativeAd.populate(it)
                }
            }
        )
    }
    
    /**
     * Show preloaded Native Ad hoặc load mới nếu chưa có
     * Method này sẽ:
     * 1. Check xem ad đã được preload chưa
     * 2. Nếu có -> Lấy từ pool và show ngay lập tức
     * 3. Nếu không -> Fallback về loadSingleNative()
     * 4. Reload lại ad cho lần sau
     * 
     * @param viewNativeAd View để hiển thị native ad
     * @param adId Resource ID của ad key
     * @param updateTimeout Có update timeout hay không
     */
    fun showPreloadedNativeOrLoad(
        viewNativeAd: ViewNativeAd,
        adId: Int,
        updateTimeout: Boolean = false
    ) {
        // Check premium user
        if (viewModel.isSubscribed) {
            return
        }
        
        // Check timeout
        if (System.currentTimeMillis() - lastTimeLoadBannerNativeAd < 10.seconds.inWholeMilliseconds) {
            return
        }
        
        val adKey = getString(adId)
        
        // Kiểm tra xem ad đã được preload chưa
        if (AppMonetization.ads.preloadNativeManagement.isLoaded(adKey)) {
            // Lấy ad từ pool (với backup)
            val preloadedAd = AppMonetization.ads.preloadNativeManagement.getNativeAdOrBackup(
                adKey = adKey,
                removeAfterGet = true // Remove để tránh show duplicate
            )
            
            if (preloadedAd != null && isAdded && view != null) {
                // Show ad ngay lập tức
                nativeAd?.destroy()
                nativeAd = preloadedAd
                viewNativeAd.populate(preloadedAd)
                
                // Update timeout nếu cần
                if (updateTimeout) {
                    updateLastTimeLoadBannerNativeAd(System.currentTimeMillis())
                }
                
                // Reload lại ad cho lần sau
                AppMonetization.ads.preloadNativeManagement.load(
                    activity = requireActivity(),
                    adKey = adKey,
                    numberOfAds = 1
                )
                
                Log.d("BaseFragment", "Showed preloaded native ad: $adKey")
                return
            }
        }
        
        // Fallback: Load mới nếu pool rỗng
        Log.d("BaseFragment", "Preloaded ad not available, loading new ad: $adKey")
        loadSingleNative(viewNativeAd, adId, updateTimeout)
    }
}

fun Fragment.onBackPressed(runnable: Runnable? = null) {
    activity?.onBackPressedDispatcher?.addCallback(
        viewLifecycleOwner,
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                runnable?.run()
            }
        })
}
