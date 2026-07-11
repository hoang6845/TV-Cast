package hoang.dqm.codebase.utils

import tpt.dev.monetization.common.premium.PremiumManager
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.subs.manager.BillingManager

object AppMonetization

val AppMonetization.ads: AdsManager
    get() = AdsManager.getInstance()

val AppMonetization.billing: BillingManager
    get() = BillingManager.getInstance()

val AppMonetization.premium: PremiumManager
    get() = PremiumManager.INSTANCE