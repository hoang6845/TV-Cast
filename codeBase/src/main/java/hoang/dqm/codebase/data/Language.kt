package hoang.dqm.codebase.data

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import hoang.dqm.codebase.R

@Keep
enum class Language(
    @DrawableRes val imageRes: Int,
    val countryCode: String,
    val countryName: String,
) {
    ENGLISH(R.drawable.flag_en, "en", "English"),
    SPANISH(R.drawable.flag_es, "es", "Español"),
    VIETNAMESE(R.drawable.flag_vi, "vi", "Vietnamese"),
    FRANCAIS(R.drawable.flag_fr, "fr", "Français"),
    INDONESIA(R.drawable.flag_id, "in", "Indonesia"),
    RUSSIA(R.drawable.flag_ru, "ru", "Russian"),
    PORTUGAL(R.drawable.flag_pt, "pt", "Portuguese"),
    ROMANIA(R.drawable.flag_ro, "ro", "România"),
    DEUTSCH(R.drawable.flag_de, "de", "Deutsch"),
    ITALIA(R.drawable.flag_it, "it", "Italiano"),
    NETHERLANDS(R.drawable.flag_ne, "nl", "Nederland");
//    OTHER(R.drawable.ic_other_common, "other", "Other");
}

