package tpt.dev.monetization.common.premium

import android.content.Context
import androidx.startup.Initializer
import com.chibatching.kotpref.initializer.KotprefInitializer

class PremiumInitializer : Initializer<IPremiumManager> {
    override fun create(context: Context): IPremiumManager {
        return PremiumManager.Companion.INSTANCE.apply {

        }
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf(
        KotprefInitializer::class.java
    )
}