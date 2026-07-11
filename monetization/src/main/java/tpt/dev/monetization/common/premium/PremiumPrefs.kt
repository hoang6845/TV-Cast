package tpt.dev.monetization.common.premium

import com.chibatching.kotpref.KotprefModel

internal object PremiumPrefs : KotprefModel() {
    var isSubscribed by booleanPref()
    var isUnlockByCode by booleanPref()
    var isActiveSubscription by booleanPref()
}