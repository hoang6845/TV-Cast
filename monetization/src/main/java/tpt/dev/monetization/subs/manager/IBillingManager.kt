package tpt.dev.monetization.subs.manager

import android.app.Activity
import android.os.Handler
import android.os.Looper
import tpt.dev.monetization.subs.model.IAPProduct
import tpt.dev.monetization.subs.model.PurchaseInfo
import tpt.dev.monetization.subs.listener.BillingClientListener
import tpt.dev.monetization.subs.listener.PurchaseServiceListener
import tpt.dev.monetization.subs.listener.SubscriptionServiceListener

abstract class IBillingManager internal constructor() {
    private val billingClientListeners = mutableListOf<BillingClientListener>()
    private val purchaseServiceListeners = mutableListOf<PurchaseServiceListener>()
    private val subscriptionServiceListeners = mutableListOf<SubscriptionServiceListener>()

    abstract fun configure(iapProducts: List<IAPProduct>)

    abstract fun buyBasePlan(activity: Activity, product: IAPProduct)

    abstract fun isConnected(): Boolean

    abstract fun getPricedProducts(): List<IAPProduct>

    fun addBillingClientListener(billingClientListener: BillingClientListener) {
        android.util.Log.d("IAP_DEBUG", "=== addBillingClientListener ===")
        android.util.Log.d("IAP_DEBUG", "Adding listener: ${billingClientListener.javaClass.simpleName}")
        android.util.Log.d("IAP_DEBUG", "Total listeners before add: ${billingClientListeners.size}")
        billingClientListeners.add(billingClientListener)
        android.util.Log.d("IAP_DEBUG", "Total listeners after add: ${billingClientListeners.size}")
        
        // Nếu đã có products, gọi callback ngay lập tức cho listener mới
        val existingProducts = getPricedProducts()
        if (existingProducts.isNotEmpty()) {
            android.util.Log.d("IAP_DEBUG", "Products already available (${existingProducts.size}), notifying new listener immediately")
            findUiHandler().post {
                billingClientListener.onQueryProductDetailComplete(existingProducts)
            }
        }
        
        // Cũng thông báo connection state hiện tại
        findUiHandler().post {
            billingClientListener.onConnected(isConnected(), 0)
        }
    }

    fun removeBillingClientListener(billingClientListener: BillingClientListener) {
        billingClientListeners.remove(billingClientListener)
    }

    fun addPurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        purchaseServiceListeners.add(purchaseServiceListener)
    }

    fun removePurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        purchaseServiceListeners.remove(purchaseServiceListener)
    }

    fun addSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        subscriptionServiceListeners.add(subscriptionServiceListener)
    }

    fun removeSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        subscriptionServiceListeners.remove(subscriptionServiceListener)
    }

    internal fun isBillingClientConnected(isConnected: Boolean, responseCode: Int) {
        findUiHandler().post {
            billingClientListeners.forEach { billingClientConnectionListener ->
                billingClientConnectionListener.onConnected(isConnected, responseCode)
            }
        }
    }

    internal fun productDetailsUpdated(products: List<IAPProduct>) {
        android.util.Log.d("IAP_DEBUG", "=== productDetailsUpdated ===")
        android.util.Log.d("IAP_DEBUG", "products size=${products.size}")
        android.util.Log.d("IAP_DEBUG", "listeners count=${billingClientListeners.size}")
        
        findUiHandler().post {
            billingClientListeners.forEach { billingClientConnectionListener ->
                android.util.Log.d("IAP_DEBUG", "Calling onQueryProductDetailComplete on listener: ${billingClientConnectionListener.javaClass.simpleName}")
                billingClientConnectionListener.onQueryProductDetailComplete(products)
            }
        }
    }

    internal fun productOwned(purchaseInfo: PurchaseInfo, isRestore: Boolean) {
        findUiHandler().post {
            purchaseServiceListeners.forEach { purchaseServiceListener ->
                if (isRestore) {
                    purchaseServiceListener.onProductRestored(purchaseInfo)
                } else {
                    purchaseServiceListener.onProductPurchased(purchaseInfo)
                }
            }
        }
    }

    internal fun productPurchasePending(purchaseInfo: PurchaseInfo) {
        findUiHandler().post {
            purchaseServiceListeners.forEach { purchaseServiceListener ->
                purchaseServiceListener.onProductPurchasePending(purchaseInfo)
            }
        }
    }

    internal fun subscriptionOwned(purchaseInfo: PurchaseInfo, isRestore: Boolean) {
        findUiHandler().post {
            subscriptionServiceListeners.forEach { subscriptionServiceListener ->
                if (isRestore) {
                    subscriptionServiceListener.onSubscriptionRestored(purchaseInfo)
                } else {
                    subscriptionServiceListener.onSubscriptionPurchased(purchaseInfo)
                }
            }
        }
    }

    internal fun subscriptionPurchasePending(purchaseInfo: PurchaseInfo) {
        findUiHandler().post {
            subscriptionServiceListeners.forEach { subscriptionServiceListener ->
                subscriptionServiceListener.onSubscriptionPurchasePending(purchaseInfo)
            }
        }
    }

    internal fun launchBillingFlowComplete(isSuccess: Boolean) {
        findUiHandler().post {
            billingClientListeners.forEach { billingClientListener ->
                billingClientListener.onLaunchPurchaseComplete(isSuccess)
            }
        }
    }

    internal fun close() {
        billingClientListeners.clear()
        purchaseServiceListeners.clear()
        subscriptionServiceListeners.clear()
    }
}

fun findUiHandler(): Handler = Handler(Looper.getMainLooper())
