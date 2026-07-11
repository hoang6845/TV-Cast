package tpt.dev.monetization.ads.base

import com.google.android.gms.ads.AdRequest
import tpt.dev.monetization.common.premium.IPremiumManager

open class BaseAdUtils internal constructor(
    private val premiumManager: IPremiumManager,
) {
    internal val defaultAdRequest: AdRequest
        get() = AdRequest.Builder().build()

    /**
     * Check if app allows showing ads
     * Returns true if user is NOT premium (free user can see ads)
     * Returns false if user is premium (premium user should not see ads)
     */
    internal val appAllowShowAd: Boolean
        get() = !premiumManager.isSubscribed()

}
