package com.example.base.ui.main

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
import com.example.base.utils.CommonAppSharePref
import com.example.base.utils.ContextUtils
import com.example.base.utils.gone
import com.example.base.utils.visible
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
        get() = com.example.base.R.navigation.app_nav


    @RequiresApi(Build.VERSION_CODES.O)
    override fun initView() {
        super.initView()
        showSystemNavigationBar()
        updateStatusBarAppearance()

        navController?.addOnDestinationChangedListener { _, _, _ ->
            AppMusicPlayer.checkAndPlay()
        }
    }



    override fun initData() {
        super.initData()
        subscribeEventNetwork { online ->
            runOnUiThread {
                binding.layoutNoInternet.root.isVisible = online.not()
            }
        }
        binding.layoutNoInternet.buttonSetting.singleClick { openSettingNetWork() }

        viewModel.isLoading.observe {
            binding.loading.loadingView.isVisible = it
        }
    }

    override fun initListener() {
        super.initListener()

    }


    override fun onResume() {
        super.onResume()
        updateStatusBarAppearance()

        try {
            if (navController?.currentDestination == null) return
//            AppMusicPlayer.checkAndPlay()

        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        AppMusicPlayer.stop()
        AppMusicPlayer.stopFxMusicPlayer()
    }

    override fun onDestroy() {
        AppMusicPlayer.releaseBackgroundMusic()
        AppMusicPlayer.releaseFxMusic()
        super.onDestroy()
    }

    override fun handleBackExit() {
        super.handleBackExit()
    }

    override fun attachBaseContext(context: Context) {
        val appPref = CommonAppSharePref(context)
        val locale = appPref.languageCode ?: Locale.getDefault().language
        Log.d("LangDebug", "Saved languageCode = ${appPref.languageCode}")
        Log.d("LangDebug", "Device default = ${Locale.getDefault().language}")
        Log.d("LangDebug", "Final locale used = $locale ${Locale(locale)}")

        val localeUpdatedContext: ContextWrapper =
            ContextUtils.updateLocale(context, Locale(locale))

        super.attachBaseContext(localeUpdatedContext)
    }


    private fun showSystemNavigationBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
}
