package tpt.dev.monetization.subs.listener

import tpt.dev.monetization.subs.model.IAPProduct

interface BillingClientListener {
    fun onConnected(isConnected: Boolean, responseCode: Int)

    fun onQueryProductDetailComplete(products: List<IAPProduct>)

    fun onLaunchPurchaseComplete(isSuccess: Boolean)
}
