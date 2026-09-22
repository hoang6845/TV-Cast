package com.tvchromecast.screenmirroringplus.ui.iap

import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private val isBillingClientConnectedFlow by lazy {
        MutableStateFlow(billingManager.isConnected())
    }

    private var displayedProducts: List<IAPProduct> = emptyList()
    private var autoBottomSheetJob: Job? = null
    private var iapBottomSheet: IAPBottomSheetFragment? = null
    private var hasShownBottomSheet = false
    private var isLaunchingIntroTrialPurchase = false

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentIapIntroBinding {
        return FragmentIapIntroBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        Log.d(TAG, "initView: hide intro close button")
        adjustInsetsForBottomNavigation(binding.btnClose)
        adjustInsetsForHiddenNavigationBottomMargin(binding.bottom)
        binding.btnClose.visibility = View.GONE
        startIntroButtonEffects()
        updateIntroActionUi()
    }

    override fun initListener() {
        binding.btnClose.setOnClickListener { goHome() }
        binding.btnSave.setOnClickListener { launchWeeklyTrialPurchase() }
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

    override fun onResume() {
        super.onResume()
        hideSystemNavigationBar()
    }

    override fun onPause() {
        showSystemNavigationBar()
        super.onPause()
    }

    override fun onConnected(isConnected: Boolean, responseCode: Int) {
        Log.d(
            TAG,
            "onConnected: isConnected=$isConnected, responseCode=$responseCode, products=${displayedProducts.size}"
        )
        isBillingClientConnectedFlow.tryEmit(isConnected)
    }

    override fun onQueryProductDetailComplete(products: List<IAPProduct>) {
        Log.d(TAG, "onQueryProductDetailComplete: products=${products.toDebugString()}")
        pricedProductsFlow.tryEmit(products)
    }

    override fun onLaunchPurchaseComplete(isSuccess: Boolean) {
        Log.d(
            TAG,
            "onLaunchPurchaseComplete: isSuccess=$isSuccess, isLaunchingIntroTrialPurchase=$isLaunchingIntroTrialPurchase"
        )
        if (isSuccess) return

        if (isLaunchingIntroTrialPurchase) {
            isLaunchingIntroTrialPurchase = false
            showPlanBottomSheet("launch_purchase_failed")
        } else if (isAdded) {
            Toast.makeText(requireContext(), R.string.text_iap_billing_error, Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onPurchaseCanceled() {
        Log.d(TAG, "onPurchaseCanceled: isLaunchingIntroTrialPurchase=$isLaunchingIntroTrialPurchase")
        if (isLaunchingIntroTrialPurchase) {
            isLaunchingIntroTrialPurchase = false
            showPlanBottomSheet("purchase_canceled")
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
                Log.d(TAG, "displayedProducts updated: ${products.toDebugString()}")
                binding.introTrialSubtitle.text = getIntroTrialSubtitle()
                updateIntroActionUi()
            }

        isBillingClientConnectedFlow
            .asLiveData()
            .observe(viewLifecycleOwner) {
                updateIntroActionUi()
            }
    }

    private fun schedulePlanBottomSheet() {
        Log.d(TAG, "schedulePlanBottomSheet: delay=$AUTO_BOTTOM_SHEET_DELAY_MS")
        autoBottomSheetJob?.cancel()
        autoBottomSheetJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTO_BOTTOM_SHEET_DELAY_MS)
            showPlanBottomSheet("auto_delay")
        }
    }

    private fun launchWeeklyTrialPurchase() {
        val product = preferredTrialProduct()
        Log.d(
            TAG,
            "launchWeeklyTrialPurchase: connected=${isBillingClientConnectedFlow.value}, " +
                    "selected=${product.toDebugString()}, displayed=${displayedProducts.toDebugString()}"
        )

        when {
            !isAdded || view == null -> {
                Log.d(TAG, "launchWeeklyTrialPurchase: abort, fragment not attached")
                return
            }

            !isBillingClientConnectedFlow.value -> {
                Log.d(TAG, "launchWeeklyTrialPurchase: abort, billing not connected")
                Toast.makeText(
                    requireContext(),
                    R.string.text_iap_waiting_billing,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            product == null -> {
                Log.d(TAG, "launchWeeklyTrialPurchase: abort, weekly trial product is null")
                Toast.makeText(
                    requireContext(),
                    R.string.text_iap_no_product_selected,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        isLaunchingIntroTrialPurchase = true
        updateIntroActionUi()
        Log.d(TAG, "launchWeeklyTrialPurchase: buyBasePlan productId=${product.productId}")
        billingManager.buyBasePlan(requireActivity(), product)
    }

    private fun updateIntroActionUi() {
        val isEnabled = isBillingClientConnectedFlow.value &&
                preferredTrialProduct() != null &&
                !isLaunchingIntroTrialPurchase

        Log.d(
            TAG,
            "updateIntroActionUi: isEnabled=$isEnabled, connected=${isBillingClientConnectedFlow.value}, " +
                    "selected=${preferredTrialProduct().toDebugString()}, isLaunching=$isLaunchingIntroTrialPurchase"
        )
        binding.btnSave.isEnabled = isEnabled
        binding.btnSave.alpha = if (isEnabled) 1f else 0.75f
    }

    private fun showPlanBottomSheet(reason: String) {
        Log.d(
            TAG,
            "showPlanBottomSheet: reason=$reason, hasShown=$hasShownBottomSheet, " +
                    "isAdded=$isAdded, hasView=${view != null}, stateSaved=${childFragmentManager.isStateSaved}"
        )
        if (hasShownBottomSheet || !isAdded || view == null) return
        if (childFragmentManager.isStateSaved) return

        val existingSheet =
            childFragmentManager.findFragmentByTag(IAP_BOTTOM_SHEET_TAG) as? IAPBottomSheetFragment
        if (existingSheet != null) {
            Log.d(TAG, "showPlanBottomSheet: reuse existing sheet")
            iapBottomSheet = existingSheet
            bindBottomSheetCallbacks(existingSheet)
            hasShownBottomSheet = true
            return
        }

        autoBottomSheetJob?.cancel()
        hasShownBottomSheet = true
        Log.d(TAG, "showPlanBottomSheet: create and show new sheet")
        iapBottomSheet = IAPBottomSheetFragment().also(::bindBottomSheetCallbacks)

        runCatching {
            iapBottomSheet?.show(childFragmentManager, IAP_BOTTOM_SHEET_TAG)
        }.onFailure {
            Log.d(TAG, "showPlanBottomSheet: failed to show sheet", it)
            hasShownBottomSheet = false
            iapBottomSheet = null
        }
    }

    private fun bindBottomSheetCallbacks(sheet: IAPBottomSheetFragment) {
        sheet.onSheetDismissed = {
            Log.d(TAG, "bottomSheet onSheetDismissed")
            if (iapBottomSheet === sheet) {
                iapBottomSheet = null
            }
            hasShownBottomSheet = false
        }
        sheet.onDismissToHome = {
            Log.d(TAG, "bottomSheet onDismissToHome")
            goHome()
        }
    }

    private fun handlePurchaseCompleted() {
        Log.d(TAG, "handlePurchaseCompleted")
        isLaunchingIntroTrialPurchase = false
        iapBottomSheet?.dismissWithoutNavigation()
        iapBottomSheet = null
        goHome()
    }

    private fun goHome() {
        Log.d(TAG, "goHome")
        autoBottomSheetJob?.cancel()
        if (!isAdded || view == null) return

        popBackStack()
    }

    private fun hideSystemNavigationBar() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        ViewCompat.requestApplyInsets(binding.bottom)
    }

    private fun showSystemNavigationBar() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.navigationBars())
    }

    private fun startIntroButtonEffects() {
        binding.btnSave.clearAnimation()
        binding.btnSave.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.intro_button_pulse))
        binding.btnSaveSparkle.clearAnimation()
        binding.btnSaveSparkle.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.intro_button_pulse))
        binding.btnSaveSparkle.bringToFront()
    }

    private fun adjustInsetsForHiddenNavigationBottomMargin(viewBottom: View) {
        val initialBottomMargin = (viewBottom.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.bottomMargin
            ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(viewBottom) { view, insets ->
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                ?: return@setOnApplyWindowInsetsListener insets
            val navigationBars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars()
            )
            params.bottomMargin = initialBottomMargin + navigationBars.bottom
            view.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(viewBottom)
    }

    private fun preferredTrialProduct(): IAPProduct? {
        val weeklyProductId = getString(hoang.dqm.codebase.R.string.billing_sub_week)
        return displayedProducts.firstOrNull { it.productId == weeklyProductId }
            ?: displayedProducts.firstOrNull { it.freeTrialDays > 0 }
    }

    private fun IAPProduct?.toDebugString(): String {
        if (this == null) return "null"
        val details = productDetails
        val offerCount = details?.subscriptionOfferDetails?.size ?: 0
        return "id=$productId,type=$productType,hasDetails=${details != null}," +
                "offers=$offerCount,hasTrial=$hasFreeTrial,trialDays=$freeTrialDays"
    }

    private fun List<IAPProduct>.toDebugString(): String {
        return joinToString(prefix = "[", postfix = "]") { it.toDebugString() }
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
        private const val TAG = "IAP_INTRO_DEBUG"
        private const val AUTO_BOTTOM_SHEET_DELAY_MS = 30000L
        private const val IAP_BOTTOM_SHEET_TAG = "IAPBottomSheetFragment"
    }
}
