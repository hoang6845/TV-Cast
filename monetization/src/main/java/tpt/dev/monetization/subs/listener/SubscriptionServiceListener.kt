package tpt.dev.monetization.subs.listener

import tpt.dev.monetization.subs.model.PurchaseInfo

interface SubscriptionServiceListener {
    fun onSubscriptionRestored(purchaseInfo: PurchaseInfo)

    fun onSubscriptionPurchased(purchaseInfo: PurchaseInfo)

    fun onSubscriptionPurchasePending(purchaseInfo: PurchaseInfo)
}
