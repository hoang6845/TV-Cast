package tpt.dev.monetization.subs.model

import android.os.Build
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import tpt.dev.monetization.subs.extensions.biggestPrice
import tpt.dev.monetization.subs.extensions.biggestSubscriptionOfferDetailsToken
import java.time.Period
import java.time.format.DateTimeParseException

enum class IAPProductType {
    InApp,
    Subscription
}

enum class IAPProductPeriods {
    Weekly,
    Monthly,
    Yearly
}

data class IAPProduct(
    val productType: IAPProductType,
    val productId: String,
    val consumable: Boolean = false,
    var productDetails: ProductDetails? = null
) {
    init {
        if (productType == IAPProductType.Subscription && consumable) {
            throw IllegalArgumentException("IAPProduct with type Subscription cannot be consumable")
        }
    }

    val isConsumable: Boolean
        get() = consumable && productType == IAPProductType.InApp

    val isOneTime: Boolean
        get() = !consumable && productType == IAPProductType.InApp

    val hasFreeTrial: Boolean
        get() {
            return if (productDetails?.productType == BillingClient.ProductType.SUBS) {
                productDetails
                    ?.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull { it.priceAmountMicros == 0L } != null
            } else {
                false
            }
        }

    val freeTrialDays: Int
        @RequiresApi(Build.VERSION_CODES.O)
        get() {
            return if (hasFreeTrial) {
                productDetails?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.billingPeriod?.let {
                    parseIso8601ToDays(it)
                } ?: 0
            } else {
                0
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parseIso8601ToDays(period: String): Int {
        return try {
            val parsedPeriod = Period.parse(period)
            parsedPeriod.days
        } catch (e: DateTimeParseException) {
            0
        }
    }

    @IAPProductViewType
    val viewType: Int
        get() = if (hasFreeTrial) PRODUCT_TRIAL_VIEW_TYPE else PRODUCT_NORMAL_VIEW_TYPE

    companion object {
        const val PRODUCT_NORMAL_VIEW_TYPE = 1
        const val PRODUCT_TRIAL_VIEW_TYPE = 2

        @IntDef(
            value = [
                PRODUCT_NORMAL_VIEW_TYPE,
                PRODUCT_TRIAL_VIEW_TYPE
            ]
        )
        @Retention(AnnotationRetention.SOURCE)
        annotation class IAPProductViewType
    }
}

fun IAPProductType.contentType(): String =
    if (this == IAPProductType.Subscription) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

fun IAPProduct.priceAmountMicros(): Long? = if (productType == IAPProductType.InApp) {
    productDetails?.oneTimePurchaseOfferDetails?.priceAmountMicros
} else {
    productDetails?.biggestSubscriptionOfferDetailsToken()?.biggestPrice()?.priceAmountMicros
}

fun IAPProduct.priceCurrencyCode(): String? = if (productType == IAPProductType.InApp) {
    productDetails?.oneTimePurchaseOfferDetails?.priceCurrencyCode
} else {
    productDetails?.biggestSubscriptionOfferDetailsToken()?.biggestPrice()?.priceCurrencyCode
}

fun IAPProduct.periods(): IAPProductPeriods? = if (productType == IAPProductType.Subscription) {
    productDetails
        ?.biggestSubscriptionOfferDetailsToken()
        ?.biggestPrice()
        ?.billingPeriod
        ?.let {
            if (it.endsWith("W")) {
                IAPProductPeriods.Weekly
            } else if (it.endsWith("M")) {
                IAPProductPeriods.Monthly
            } else if (it.endsWith("Y")) {
                IAPProductPeriods.Yearly
            } else {
                null
            }
        }
} else {
    null
}
