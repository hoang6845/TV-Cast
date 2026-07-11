package tpt.dev.monetization.common.premium

import kotlinx.coroutines.flow.StateFlow

interface IPremiumManager {
    fun updateUnlockByCodeState(isUnlocked: Boolean)
    fun updateSubscribedState(isSubscribed: Boolean)
    fun updateActiveSubscriptionState(isActiveSubscription: Boolean)
    fun isSubscribed(): Boolean
    fun getSubscribedStateFlow(): StateFlow<Boolean>
    fun isActiveSubscription(): Boolean
}