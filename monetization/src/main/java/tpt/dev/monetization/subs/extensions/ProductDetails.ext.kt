package tpt.dev.monetization.subs.extensions

import com.android.billingclient.api.ProductDetails

fun ProductDetails.biggestSubscriptionOfferDetailsToken(): ProductDetails.SubscriptionOfferDetails? {
    var biggestPricedOffer: ProductDetails.SubscriptionOfferDetails? = null
    var biggestPrice = 0L

    subscriptionOfferDetails?.forEach { offerDetails ->
        offerDetails.pricingPhases.pricingPhaseList.forEach { pricingPhase ->
            if (pricingPhase.priceAmountMicros > biggestPrice) {
                biggestPrice = pricingPhase.priceAmountMicros
                biggestPricedOffer = offerDetails
            }
        }
    }

    return biggestPricedOffer
}

fun ProductDetails.SubscriptionOfferDetails.biggestPrice(): ProductDetails.PricingPhase? {
    var biggestPrice = 0L
    var biggestPricingPhase: ProductDetails.PricingPhase? = null
    for (price in pricingPhases.pricingPhaseList) {
        if (price.priceAmountMicros > biggestPrice) {
            biggestPrice = price.priceAmountMicros
            biggestPricingPhase = price
        }
    }
    return biggestPricingPhase
}
