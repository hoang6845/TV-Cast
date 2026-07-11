package com.example.base.ui.language_activity

import android.content.Intent
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.base.databinding.ActivityLanguageBinding
import com.example.base.utils.CommonAppSharePref
import com.example.base.utils.gone
import com.example.base.utils.visible
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.BaseActivity
import hoang.dqm.codebase.utils.RecyclerUtils
import hoang.dqm.codebase.utils.singleClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class LanguageActivity : BaseActivity<ActivityLanguageBinding, LanguageViewModel>() {
    private val appSharePref: CommonAppSharePref by lazy {
        CommonAppSharePref(this)
    }

    private var isFirstSelect = true
    private var hasUserSelectedLanguage = false
    private var isFromSplash = false
    private val languageAdapter by lazy { LanguageAdapter() }

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.main)

        isFromSplash = intent.extras?.getBoolean("isFromSplash") ?: false

        binding.imvBack.isVisible = isFromSplash.not()

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

            if (isFirstSelect) {
                isFirstSelect = false
                binding.loading.visible()

                loadSingleNative(
                    binding.viewNativeAd,
                    R.string.ads_native_language_click,
                    updateTimeout = false,
                    onAdsLoaded = {
                        if (!isFinishing && !isDestroyed) {
                            binding.loading.gone()
                        }
                    },
                    onLoadFailed = {
                        if (!isFinishing && !isDestroyed) {
                            binding.loading.gone()
                        }
                    }
                )
            }
        }

        loadSingleNative(
            binding.viewNativeAd,
            R.string.ads_native_language_id,
            updateTimeout = false,
            onAdsLoaded = {
                if (!isFinishing && !isDestroyed) {
                    binding.loading.gone()
                }
            },
            onLoadFailed = {
                if (!isFinishing && !isDestroyed) {
                    binding.loading.gone()
                }
            }
        )

        lifecycleScope.launch {
            delay(3000L)
            binding.loading.gone()
        }
    }

    override fun initListener() {
        binding.imvBack.singleClick {
            finish()
        }

        binding.btnDone.singleClick {
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
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    finish()
                }

                return@singleClick
            }

            Log.d("LangDebug", "Different language → updateNewLang()")
            updateNewLang()
        }
    }

    private fun updateNewLang() {
        viewModel.saveLang { languageCode ->
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageCode)
            )

            if (isFromSplash) {
                val resultIntent = Intent().apply {
                    putExtra("go_to_intro", true)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                finish()
            }
        }
    }

    override fun initData() {
        viewModel.languageLiveData.observe { list ->
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
}