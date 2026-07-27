package com.tvchromecast.screenmirroringplus.ui.language_activity

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.tvchromecast.screenmirroringplus.utils.CommonAppSharePref
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import hoang.dqm.codebase.data.LanguageApp
import hoang.dqm.codebase.data.LanguageSelector
import java.util.Locale

class LanguageViewModel : BaseViewModel() {

    private val appSharePref: CommonAppSharePref by lazy {
        CommonAppSharePref(context.applicationContext)
    }

    val languageLiveData by lazy { MutableLiveData<List<LanguageSelector>>() }
    var mLanguageSelector: LanguageSelector? = null

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        getLang()
    }

    private fun getLang() {
        launchHandler {
            flowOnIO {
                val languageCode = appSharePref.languageCode ?: Locale.getDefault().language
                LanguageApp.entries.flatMapIndexed { _: Int, language: LanguageApp ->
                    arrayListOf<LanguageSelector>().also { list ->
                        val isCheck = languageCode == language.languageCode
                        val item = LanguageSelector(language, isCheck = isCheck)
                        list.add(item)
                        if (item.isCheck) {
                            mLanguageSelector = item
                        }
                    }
                }
            }.subscribe {
                languageLiveData.value = it
            }
        }
    }

    fun saveLang(onDone: (languageCode: String) -> Unit) {
        launchHandler {
            flowOnIO {
                val languageCode =
                    mLanguageSelector?.language?.languageCode ?: Locale.getDefault().language
                appSharePref.languageCode = languageCode
                appSharePref.applyLanguage(languageCode)
                Log.d("LangDebug", "Before locale = ${context.resources.configuration.locales}")
                languageCode
            }.subscribe { languageCode ->
                onDone.invoke(languageCode)
            }
        }
    }
}
