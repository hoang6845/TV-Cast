package com.example.base.utils

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.util.Locale


class CommonAppSharePref(private val context: Context) {
    companion object {
        private const val PREF_SHOW_LANGUAGE = "pref_show_language"
        private const val PREF_LANGUAGE_CODE = "pref_language_code"
    }

    private val sharePref by lazy {
        context.getSharedPreferences("TrackingSharePref", Context.MODE_PRIVATE)
    }

    var isEnableLanguage: Boolean
        get() = sharePref.getBoolean(PREF_SHOW_LANGUAGE, false)
        set(value) {
            sharePref.edit().putBoolean(PREF_SHOW_LANGUAGE, value).apply()
        }

    var languageCode: String?
        get() = sharePref.getString(PREF_LANGUAGE_CODE, null)
        set(value) {
            sharePref.edit().putString(PREF_LANGUAGE_CODE, value).apply()
        }

    fun applyLanguage(languageCode: String) {

        Log.d("LangDebug", "==============================")
        Log.d("LangDebug", "applyLanguage() called")
        Log.d("LangDebug", "input languageCode = $languageCode")

        val locale = Locale(languageCode)

        Log.d("LangDebug", "created Locale = ${locale.language}_${locale.country}")
        Log.d("LangDebug", "Before setDefault = ${Locale.getDefault()}")

        Locale.setDefault(locale)

        Log.d("LangDebug", "After setDefault = ${Locale.getDefault()}")

        val config = Configuration()

        Log.d("LangDebug", "Before config locale = ${config.locales}")

        config.setLocale(locale)

        Log.d("LangDebug", "After config locale = ${config.locales}")

        context.resources.updateConfiguration(
            config,
            context.resources.displayMetrics
        )

        Log.d("LangDebug", "Resources updated")
        Log.d("LangDebug", "Current resources locale = ${context.resources.configuration.locales}")
        Log.d("LangDebug", "==============================")
    }
}