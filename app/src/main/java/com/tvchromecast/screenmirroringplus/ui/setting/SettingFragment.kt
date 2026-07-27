package com.tvchromecast.screenmirroringplus.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.tvchromecast.screenmirroringplus.databinding.FragmentSettingBinding
import com.tvchromecast.screenmirroringplus.ui.language_activity.LanguageActivity
import com.tvchromecast.screenmirroringplus.utils.Common
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.navigate
import hoang.dqm.codebase.base.activity.navigateFade
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.service.session.isMusic
import hoang.dqm.codebase.service.session.isSound
import hoang.dqm.codebase.service.session.isVibrate
import hoang.dqm.codebase.service.session.saveMusic
import hoang.dqm.codebase.service.session.saveSound
import hoang.dqm.codebase.service.session.saveVibrate
import hoang.dqm.codebase.service.sound.AppMusicPlayer


class SettingFragment : BaseFragment<FragmentSettingBinding, SettingViewModel>() {
    override fun initView() {
        adjustInsetsForBottomNavigation(binding.clToolbar)

    }

    override fun initListener() {
        binding.imvBack.setOnClickListener {
            popBackStack()
        }
        onBackPressed { popBackStack() }
        binding.llMusic.setOnClickListener {
            saveMusic(!isMusic())
            checkPlaySound()
            updateView()
        }
        binding.llSoundFx.setOnClickListener {
            saveSound(!isSound())
            updateView()
        }
        binding.llVibrate.setOnClickListener {
            saveVibrate(!isVibrate())
            updateView()
        }
        binding.llLanguage.setOnClickListener {
//            navigate(R.id.languageAppFragment)
            startActivity(Intent(requireContext(), LanguageActivity::class.java))
        }
        binding.llShare.setOnClickListener {
            activity?.let {
                Common.shareApp(it, appInfo().appName)
            }
        }
        binding.llRate.setOnClickListener {

        }
        binding.llManageSubscription.setOnClickListener {
//            navigateFade(R.id.IAPFragment)
        }
        binding.llImport.setOnClickListener {
            val bundle = Bundle().apply {
                putInt("position", 0)
            }
//            navigateFade(R.id.howToYouFragment, bundle)
        }
        binding.llFeedback.setOnClickListener {
            activity?.let {
                Common.shareApp(it, appInfo().policy)
            }
        }
        binding.llPolicy.setOnClickListener {
            activity?.let {
                Common.openWebView(it, appInfo().policy)
            }
        }

        binding.llTerm.setOnClickListener {
            activity?.let {
                Common.openWebView(it, appInfo().term)
            }
        }

        binding.guideHelp.setOnClickListener {
//            navigate(R.id.howToYouFragment)
        }

        binding.btnPremium.setOnClickListener {
//            navigateFade(R.id.IAPFragment)
        }



    }

    override fun initData() {

    }


    private fun checkPlaySound() {
        if (isMusic()) {
            AppMusicPlayer.playBackgroundMusic()
        } else {
            AppMusicPlayer.stop()
            AppMusicPlayer.releaseBackgroundMusic()
        }
    }

    private fun updateView() {
    }
}