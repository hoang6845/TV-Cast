package tpt.dev.monetization.subs.initializer

import android.content.Context
import androidx.startup.Initializer
import tpt.dev.monetization.common.premium.PremiumManager
import tpt.dev.monetization.common.premium.PremiumInitializer
import tpt.dev.monetization.subs.manager.BillingManager

/**
 * BillingInitializer is a class that initializes the BillingManager instance as part of the application startup process.
 */
class BillingInitializer : Initializer<BillingManager> {
    override fun create(context: Context): BillingManager = BillingManager
        .initialize(
            applicationContext = context,
            premiumManager = PremiumManager.INSTANCE
        ).apply {
        }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf(
        PremiumInitializer::class.java,
    )
}
