package com.tvchromecast.screenmirroringplus.ui.main

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.toColorInt
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.tvchromecast.screenmirroringplus.utils.CommonAppSharePref
import com.tvchromecast.screenmirroringplus.utils.ContextUtils
import com.tvchromecast.screenmirroringplus.utils.gone
import com.tvchromecast.screenmirroringplus.utils.visible
import dagger.hilt.android.AndroidEntryPoint
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.navigate
import hoang.dqm.codebase.base.activity.navigateLeft
import hoang.dqm.codebase.base.activity.navigateRight
import hoang.dqm.codebase.databinding.ActivityMainBinding
import hoang.dqm.codebase.event.subscribeEventNetwork
import hoang.dqm.codebase.service.sound.AppMusicPlayer
import hoang.dqm.codebase.ui.features.main.BaseMainActivity
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.ads
import hoang.dqm.codebase.utils.openSettingNetWork
import hoang.dqm.codebase.utils.singleClick
import java.util.Locale
import kotlin.math.abs
@AndroidEntryPoint
class MainActivity : BaseMainActivity<ActivityMainBinding, MainViewModel>() {
    override val graphResId: Int
        get() = com.tvchromecast.screenmirroringplus.R.navigation.app_nav

    override fun onCreate(savedInstanceState: Bundle?) {
        releaseLog("MainActivity.onCreate: before super, savedState=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
        releaseLog(
            "MainActivity.onCreate: after super, nav=${destinationName(navController?.currentDestination?.id)}"
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun initView() {
        releaseLog("MainActivity.initView: before super, graphResId=${destinationName(graphResId)}")
        super.initView()
        releaseLog(
            "MainActivity.initView: after super, navControllerReady=${navController != null}, current=${destinationName(navController?.currentDestination?.id)}"
        )
        showSystemNavigationBar()
        updateStatusBarAppearance()

        navController?.addOnDestinationChangedListener { _, destination, arguments ->
            releaseLog(
                "MainActivity.destinationChanged: id=${destinationName(destination.id)}, label=${destination.label}, args=${arguments?.keySet()?.joinToString().orEmpty()}"
            )
            AppMusicPlayer.checkAndPlay()
        }
    }



    override fun initData() {
        releaseLog("MainActivity.initData")
        super.initData()
        subscribeEventNetwork { online ->
            releaseLog("MainActivity.networkEvent: online=$online")
            runOnUiThread {
                binding.layoutNoInternet.root.isVisible = online.not()
            }
        }
        binding.layoutNoInternet.buttonSetting.singleClick { openSettingNetWork() }

        viewModel.isLoading.observe {
            releaseLog("MainActivity.loadingChanged: isLoading=$it")
            binding.loading.loadingView.isVisible = it
        }
    }

    override fun initListener() {
        releaseLog("MainActivity.initListener")
        super.initListener()

    }

    override fun onStart() {
        releaseLog("MainActivity.onStart: before super")
        super.onStart()
        releaseLog("MainActivity.onStart: after super, nav=${destinationName(navController?.currentDestination?.id)}")
    }

    override fun onResume() {
        releaseLog("MainActivity.onResume: before super")
        super.onResume()
        updateStatusBarAppearance()
        releaseLog("MainActivity.onResume: nav=${destinationName(navController?.currentDestination?.id)}")

        try {
            if (navController?.currentDestination == null) return
//            AppMusicPlayer.checkAndPlay()

        } catch (ex: Exception) {
            releaseLog("MainActivity.onResume: exception=${ex.message}")
            ex.printStackTrace()
        }
    }

    override fun onPause() {
        releaseLog("MainActivity.onPause")
        super.onPause()
        AppMusicPlayer.stop()
        AppMusicPlayer.stopFxMusicPlayer()
    }

    override fun onStop() {
        releaseLog("MainActivity.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        releaseLog("MainActivity.onDestroy")
        AppMusicPlayer.releaseBackgroundMusic()
        AppMusicPlayer.releaseFxMusic()
        super.onDestroy()
    }

    override fun handleBackExit() {
        releaseLog("MainActivity.handleBackExit: nav=${destinationName(navController?.currentDestination?.id)}")
        super.handleBackExit()
    }

    override fun attachBaseContext(context: Context) {
        val appPref = CommonAppSharePref(context)
        val locale = appPref.languageCode ?: Locale.getDefault().language
        Log.d("LangDebug", "Saved languageCode = ${appPref.languageCode}")
        Log.d("LangDebug", "Device default = ${Locale.getDefault().language}")
        Log.d("LangDebug", "Final locale used = $locale ${Locale(locale)}")
        releaseLog("MainActivity.attachBaseContext: saved=${appPref.languageCode}, final=$locale")

        val localeUpdatedContext: ContextWrapper =
            ContextUtils.updateLocale(context, Locale(locale))

        super.attachBaseContext(localeUpdatedContext)
    }


    private fun showSystemNavigationBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.navigationBars())
        releaseLog("MainActivity.showSystemNavigationBar")
    }

    private fun destinationName(id: Int?): String {
        if (id == null) return "null"
        return runCatching { resources.getResourceEntryName(id) }.getOrElse { id.toString() }
    }

    private fun releaseLog(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "TVCastReleaseLog"
    }
}
