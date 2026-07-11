package tpt.dev.monetization.subs.listener

import tpt.dev.monetization.subs.model.PurchaseInfo

interface PurchaseServiceListener {
    fun onProductPurchased(purchaseInfo: PurchaseInfo)

    fun onProductRestored(purchaseInfo: PurchaseInfo)

    fun onProductPurchasePending(purchaseInfo: PurchaseInfo)
}
