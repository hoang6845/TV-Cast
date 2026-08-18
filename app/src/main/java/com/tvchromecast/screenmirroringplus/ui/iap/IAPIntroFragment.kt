package com.tvchromecast.screenmirroringplus.ui.iap

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.google.android.datatransport.runtime.scheduling.persistence.EventStoreModule_PackageNameFactory.packageName
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.FragmentIapIntroBinding
import com.tvchromecast.screenmirroringplus.utils.Common
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.billing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tpt.dev.monetization.subs.listener.BillingClientListener
import tpt.dev.monetization.subs.listener.SubscriptionServiceListener
import tpt.dev.monetization.subs.model.IAPProduct
import tpt.dev.monetization.subs.model.IAPProductPeriods
import tpt.dev.monetization.subs.model.PurchaseInfo
import tpt.dev.monetization.subs.model.periods

class IAPIntroFragment : BaseFragment<FragmentIapIntroBinding, IAPViewModel>(),
    BillingClientListener,
    SubscriptionServiceListener {

    override val viewModelClass: Class<IAPViewModel>
        get() = IAPViewModel::class.java

    private val billingManager by lazy { AppMonetization.billing }
    private val pricedProductsFlow by lazy {
        MutableStateFlow(billingManager.getPricedProducts())
    }

    private var displayedProducts: List<IAPProduct> = emptyList()
    private var autoBottomSheetJob: Job? = null
    private var iapBottomSheet: IAPBottomSheetFragment? = null
    private var hasShownBottomSheet = false

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentIapIntroBinding {
        return FragmentIapIntroBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.btnClose)

        binding.btnClose.visibility = View.INVISIBLE
        binding.btnClose.postDelayed({
            if (isAdded && !isDetached && view != null) {
                binding.btnClose.visibility = View.VISIBLE
            }
        }, CLOSE_BUTTON_DELAY_MS)
    }

    override fun initListener() {
        binding.btnClose.setOnClickListener { goHome() }
        binding.btnSave.setOnClickListener { showPlanBottomSheet() }
        binding.textTerm.setOnClickListener {
            Common.openWebView(requireContext(), appInfo().term)
        }
        binding.textPolicy.setOnClickListener {
            Common.openWebView(requireContext(), appInfo().policy)
        }
        binding.textRestore.setOnClickListener {
                val packageName = requireContext().packageName
                val uri =
                    "https://play.google.com/store/account/subscriptions?package=$packageName".toUri()

                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
        }
        onBackPressed { goHome() }
    }

    override fun initData() {
        listenBillingManager()
//        schedulePlanBottomSheet()
    }

    override fun onConnected(isConnected: Boolean, responseCode: Int) = Unit

    override fun onQueryProductDetailComplete(products: List<IAPProduct>) {
        pricedProductsFlow.tryEmit(products)
    }

    override fun onLaunchPurchaseComplete(isSuccess: Boolean) {
        if (!isSuccess && isAdded) {
            Toast.makeText(
                requireContext(),
                R.string.text_iap_billing_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onSubscriptionRestored(purchaseInfo: PurchaseInfo) {
        handlePurchaseCompleted()
    }

    override fun onSubscriptionPurchased(purchaseInfo: PurchaseInfo) {
        handlePurchaseCompleted()
    }

    override fun onSubscriptionPurchasePending(purchaseInfo: PurchaseInfo) = Unit

    override fun onDestroyView() {
        autoBottomSheetJob?.cancel()
        autoBottomSheetJob = null
        iapBottomSheet?.dismissWithoutNavigation()
        iapBottomSheet = null
        billingManager.removeBillingClientListener(this)
        billingManager.removeSubscriptionListener(this)
        super.onDestroyView()
    }

    private fun listenBillingManager() {
        billingManager.addBillingClientListener(this)
        billingManager.addSubscriptionListener(this)

        val displayProductIds = listOf(
            getString(hoang.dqm.codebase.R.string.billing_sub_week),
            getString(hoang.dqm.codebase.R.string.billing_sub_year),
            getString(hoang.dqm.codebase.R.string.billing_lifetime)
        )

        pricedProductsFlow
            .map { products ->
                products
                    .filter { displayProductIds.contains(it.productId) }
                    .sortedBy { displayProductIds.indexOf(it.productId) }
            }
            .asLiveData()
            .observe(viewLifecycleOwner) { products ->
                displayedProducts = products
                binding.introTrialSubtitle.text = getIntroTrialSubtitle()
            }
    }

    private fun schedulePlanBottomSheet() {
        autoBottomSheetJob?.cancel()
        autoBottomSheetJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTO_BOTTOM_SHEET_DELAY_MS)
            showPlanBottomSheet()
        }
    }

    private fun showPlanBottomSheet() {
        if (hasShownBottomSheet || !isAdded || view == null) return
        if (childFragmentManager.isStateSaved) return

        val existingSheet =
            childFragmentManager.findFragmentByTag(IAP_BOTTOM_SHEET_TAG) as? IAPBottomSheetFragment
        if (existingSheet != null) {
            iapBottomSheet = existingSheet
            hasShownBottomSheet = true
            return
        }

        autoBottomSheetJob?.cancel()
        hasShownBottomSheet = true
        iapBottomSheet = IAPBottomSheetFragment().apply {
            onDismissToHome = {
//                goHome()
            }
        }

        runCatching {
            iapBottomSheet?.show(childFragmentManager, IAP_BOTTOM_SHEET_TAG)
        }.onFailure {
            hasShownBottomSheet = false
            iapBottomSheet = null
        }
    }

    private fun handlePurchaseCompleted() {
        iapBottomSheet?.dismissWithoutNavigation()
        iapBottomSheet = null
        goHome()
    }

    private fun goHome() {
        autoBottomSheetJob?.cancel()
        if (!isAdded || view == null) return

        popBackStack()
    }

    private fun preferredTrialProduct(): IAPProduct? {
        val weeklyProductId = getString(hoang.dqm.codebase.R.string.billing_sub_week)
        return displayedProducts.firstOrNull { it.productId == weeklyProductId && it.freeTrialDays > 0 }
            ?: displayedProducts.firstOrNull { it.freeTrialDays > 0 }
    }

    private fun getIntroTrialSubtitle(): String {
        val trialProduct = preferredTrialProduct()
        val displayPrice = trialProduct
            ?.let { getDisplayPrice(getRegularSubscriptionPrice(it), it.periods()) }
            .orEmpty()

        return if (trialProduct == null || displayPrice.isBlank()) {
            getString(R.string.text_iap_trial_auto_renewal)
        } else {
            getString(
                R.string.text_iap_intro_trial_then_price,
                trialProduct.freeTrialDays,
                displayPrice
            )
        }
    }

    private fun getRegularSubscriptionPrice(product: IAPProduct): String {
        return product.productDetails
            ?.subscriptionOfferDetails
            ?.flatMap { it.pricingPhases.pricingPhaseList }
            ?.firstOrNull {
                it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
            }
            ?.formattedPrice
            .orEmpty()
    }

    private fun getDisplayPrice(price: String, period: IAPProductPeriods?): String {
        if (price.isBlank()) return ""

        val suffix = when (period) {
            IAPProductPeriods.Weekly -> "week"
            IAPProductPeriods.Monthly -> "month"
            IAPProductPeriods.Yearly -> "year"
            else -> ""
        }

        return if (suffix.isBlank()) price else "$price/$suffix"
    }

    companion object {
        private const val CLOSE_BUTTON_DELAY_MS = 3000L
        private const val AUTO_BOTTOM_SHEET_DELAY_MS = 30000L
        private const val IAP_BOTTOM_SHEET_TAG = "IAPBottomSheetFragment"
    }
}
