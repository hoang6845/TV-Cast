package com.tvchromecast.screenmirroringplus.ui.language_activity

import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.tvchromecast.screenmirroringplus.databinding.ActivityLanguageBinding
import com.tvchromecast.screenmirroringplus.utils.CommonAppSharePref
import com.tvchromecast.screenmirroringplus.utils.gone
import com.tvchromecast.screenmirroringplus.utils.visible
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.BaseActivity
import hoang.dqm.codebase.utils.RecyclerUtils
import hoang.dqm.codebase.utils.singleClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class LanguageActivity : BaseActivity<ActivityLanguageBinding, LanguageViewModel>() {
    override val viewModelClass: Class<LanguageViewModel>
        get() = LanguageViewModel::class.java

    override fun inflateBinding(layoutInflater: LayoutInflater): ActivityLanguageBinding {
        return ActivityLanguageBinding.inflate(layoutInflater)
    }

    private val appSharePref: CommonAppSharePref by lazy {
        CommonAppSharePref(this)
    }

    private var isFirstSelect = true
    private var hasUserSelectedLanguage = false
    private var isFromSplash = false
    private val languageAdapter by lazy { LanguageAdapter() }

    override fun initView() {
        releaseLog("LanguageActivity.initView: start")
        adjustInsetsForBottomNavigation(binding.main)
        adjustInsetsForBottomMargin(binding.viewNativeAd)

        isFromSplash = intent.extras?.getBoolean("isFromSplash") ?: false
        releaseLog("LanguageActivity.initView: isFromSplash=$isFromSplash")

        binding.imvBack.isVisible = isFromSplash.not()
        binding.viewNativeAd.gone()
        binding.loading.gone()

        // Mới vào không chọn gì, không hiện Done
        hideDoneButton()

        RecyclerUtils.setLinearLayoutManager(
            this,
            binding.rcvLanguage,
            languageAdapter
        )

        languageAdapter.setOnClickItemRecyclerView { language, _ ->
            hasUserSelectedLanguage = true

            languageAdapter.setSelectLang(language)
            viewModel.mLanguageSelector = language

            // Chỉ khi user chọn mới hiện Done
            showDoneButton()

//            if (isFirstSelect) {
//                isFirstSelect = false
//                binding.loading.visible()
//
//                loadSingleNative(
//                    binding.viewNativeAd,
//                    R.string.ads_native_language_click,
//                    updateTimeout = false,
//                    onAdsLoaded = {
//                        if (!isFinishing && !isDestroyed) {
//                            binding.loading.gone()
//                        }
//                    },
//                    onLoadFailed = {
//                        if (!isFinishing && !isDestroyed) {
//                            binding.loading.gone()
//                        }
//                    }
//                )
//            }
        }

//        loadSingleNative(
//            binding.viewNativeAd,
//            R.string.ads_native_language_id,
//            updateTimeout = false,
//            onAdsLoaded = {
//                if (!isFinishing && !isDestroyed) {
//                    binding.loading.gone()
//                }
//            },
//            onLoadFailed = {
//                if (!isFinishing && !isDestroyed) {
//                    binding.loading.gone()
//                }
//            }
//        )

        lifecycleScope.launch {
            delay(3000L)
            binding.loading.gone()
        }
        releaseLog("LanguageActivity.initView: done")
    }

    override fun initListener() {
        releaseLog("LanguageActivity.initListener")
        binding.imvBack.singleClick {
            releaseLog("LanguageActivity.backClick")
            finish()
        }

        binding.btnDone.singleClick {
            releaseLog("LanguageActivity.doneClick: hasUserSelectedLanguage=$hasUserSelectedLanguage")
            if (!hasUserSelectedLanguage) {
                return@singleClick
            }

            val current = appSharePref.languageCode ?: Locale.getDefault().language
            val checked =
                languageAdapter.dataList.find { it.isCheck }?.language?.languageCode ?: return@singleClick

            Log.d("LangDebug", "current = $current")
            Log.d("LangDebug", "checked = $checked")

            if (current == checked) {
                Log.d("LangDebug", "Same language → finish()")

                if (isFromSplash) {
                    val resultIntent = Intent().apply {
                        putExtra("go_to_intro", true)
                    }
                    releaseLog("LanguageActivity.doneClick: same language, RESULT_OK")
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    releaseLog("LanguageActivity.doneClick: same language, finish")
                    finish()
                }

                return@singleClick
            }

            Log.d("LangDebug", "Different language → updateNewLang()")
            releaseLog("LanguageActivity.doneClick: different language, updateNewLang")
            updateNewLang()
        }
    }

    private fun updateNewLang() {
        releaseLog("LanguageActivity.updateNewLang")
        viewModel.saveLang { languageCode ->
            releaseLog("LanguageActivity.updateNewLang: saved=$languageCode")
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageCode)
            )

            if (isFromSplash) {
                val resultIntent = Intent().apply {
                    putExtra("go_to_intro", true)
                }
                releaseLog("LanguageActivity.updateNewLang: RESULT_OK")
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                releaseLog("LanguageActivity.updateNewLang: finish")
                finish()
            }
        }
    }

    override fun initData() {
        releaseLog("LanguageActivity.initData")
        viewModel.languageLiveData.observe { list ->
            releaseLog("LanguageActivity.languageLiveData: size=${list.size}")
            // Quan trọng: bỏ trạng thái tự chọn mặc định
            list.forEach {
                it.isCheck = false
            }

            hasUserSelectedLanguage = false
            viewModel.mLanguageSelector = null
            hideDoneButton()

            languageAdapter.addData(list)
        }
    }

    private fun showDoneButton() {
        binding.btnDone.visible()
        binding.btnDone.isEnabled = true
    }

    private fun hideDoneButton() {
        binding.btnDone.visibility = View.INVISIBLE
        binding.btnDone.isEnabled = false
    }

    private fun releaseLog(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "TVCastReleaseLog"
    }
}
