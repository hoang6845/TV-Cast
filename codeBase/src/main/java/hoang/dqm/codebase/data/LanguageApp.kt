package hoang.dqm.codebase.data

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import hoang.dqm.codebase.R

@Keep
enum class LanguageApp(
    val language: String,
    @DrawableRes val flag: Int,
    val languageCode: String,
    var countryCode: String = ""
) {
    ENGLISH(
        language = "English",
        flag = R.drawable.flag_en,
        languageCode = "en"
    ),
    SPANISH(
        language = "Spanish",
        flag = R.drawable.flag_es,
        languageCode = "es"
    ),
    VIETNAMESE(
        flag = R.drawable.flag_vi, languageCode = "vi", language = "Vietnamese"
    ),
    FRENCH(
        language = "French",
        flag = R.drawable.flag_fr,
        languageCode = "fr"
    ),
    INDONESIA(
        language = "Indonesia",
        flag = R.drawable.flag_id,
        languageCode = "in"
    ),
    PORTUGUESE(
        language = "Portuguese", flag = R.drawable.flag_pt, languageCode = "pt"
    ),
    ROMANIA(
        flag = R.drawable.flag_ro,
        languageCode = "ro",
        language = "România"
    ),
    DEUTSCH(
        flag = R.drawable.flag_de,
        languageCode = "de",
        language = "Deutsch"
    ),
    ITALIA(
        flag = R.drawable.flag_it,
        languageCode = "it",
        language = "Italiano"
    ),
    NETHERLANDS(
        flag = R.drawable.flag_ne, languageCode = "nl", language = "Nederland"
    );


    companion object {
        fun getLanguagePosition(languageCode: String): Int {
            val language = entries.find { it.languageCode == languageCode } ?: "en"
            return entries.indexOf(language)
        }
    }
}

@Keep
data class LanguageSelector(
    val language: LanguageApp,
    var isCheck: Boolean = false)
