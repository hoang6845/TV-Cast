package tpt.dev.monetization.subs.model

import androidx.annotation.Keep
import com.android.billingclient.api.AccountIdentifiers
@Keep
data class PurchaseInfo(
    val purchaseState: Int,
    val developerPayload: String,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean,
    val orderId: String?,
    val originalJson: String,
    val packageName: String,
    val purchaseTime: Long,
    val purchaseToken: String,
    val signature: String,
    val sku: String,
    val accountIdentifiers: AccountIdentifiers?
)
