package com.tvchromecast.screenmirroringplus.ui.iap

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.FragmentIAPBinding
import com.tvchromecast.screenmirroringplus.utils.Common
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.billing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tpt.dev.monetization.subs.listener.BillingClientListener
import tpt.dev.monetization.subs.listener.SubscriptionServiceListener
import tpt.dev.monetization.subs.model.IAPProduct
import tpt.dev.monetization.subs.model.PurchaseInfo

typealias ProductWithSelection = Pair<IAPProduct, Boolean>

private data class ProductRenderState(
    val products: List<ProductWithSelection>,
    val isTrialEnabled: Boolean,
    val hasFreeTrialProduct: Boolean
)

class IAPFragment : BaseFragment<FragmentIAPBinding, IAPViewModel>(),
    BillingClientListener,
    SubscriptionServiceListener {

    override val viewModelClass: Class<IAPViewModel>
        get() = IAPViewModel::class.java

    private val billingManager by lazy { AppMonetization.billing }
    private val isBillingClientConnectedFlow by lazy {
        MutableStateFlow(billingManager.isConnected())
    }
    private val pricedProductsFlow by lazy {
        MutableStateFlow(billingManager.getPricedProducts())
    }
    private val selectedProductIdFlow = MutableStateFlow<String?>(null)
    private val trialEnabledFlow = MutableStateFlow(true)
    private val productAdapter by lazy { IAPProductAdapter() }

    private var selectedProduct: IAPProduct? = null
    private var displayedProducts: List<IAPProduct> = emptyList()

    private val isFromSplash by lazy {
        arguments?.getBoolean(ARG_FROM_SPLASH) ?: false
    }

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentIAPBinding {
        return FragmentIAPBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.bg)

        binding.titleAccess.text = buildSpannedString {
            color("#F4D188".toColorInt()) {
                append(getString(R.string.text_iap_premium_access).substringBefore(" "))
            }
            append(" ")
            color(Color.WHITE) {
                append(getString(R.string.text_iap_premium_access).substringAfter(" "))
            }
        }

        binding.btnClose.visibility = View.INVISIBLE
        binding.btnClose.postDelayed({
            if (isAdded && !isDetached && view != null) {
                binding.btnClose.visibility = View.VISIBLE
            }
        }, CLOSE_BUTTON_DELAY_MS)

        productAdapter.setOnClickItem { product, _ ->
            if (trialEnabledFlow.value && product.freeTrialDays == 0) {
                trialEnabledFlow.tryEmit(false)
            }
            selectedProductIdFlow.tryEmit(product.productId)
        }

        binding.rvProducts.apply {
            adapter = productAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        updateSelectedProductUi()
    }

    override fun initListener() {
        binding.btnClose.setOnClickListener { handleClose() }
        onBackPressed { handleClose() }

        binding.btnSave.setOnClickListener {
            val product = selectedProduct

            when {
                !isBillingClientConnectedFlow.value -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.text_iap_waiting_billing,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                product == null -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.text_iap_no_product_selected,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                else -> billingManager.buyBasePlan(requireActivity(), product)
            }
        }

        binding.cardTrialToggle.setOnClickListener {
            toggleTrialSelection()
        }
        binding.ivTrialSwitch.setOnClickListener {
            toggleTrialSelection()
        }
        binding.textTerm.setOnClickListener {
            context?.let { Common.openWebView(it, appInfo().term) }
        }
        binding.textPolicy.setOnClickListener {
            context?.let { Common.openWebView(it, appInfo().policy) }
        }
    }

    override fun initData() {
        listenBillingManager()
    }

    override fun onConnected(isConnected: Boolean, responseCode: Int) {
        isBillingClientConnectedFlow.tryEmit(isConnected)
    }

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
        showUpgradeSuccessDialog()
    }

    override fun onSubscriptionPurchased(purchaseInfo: PurchaseInfo) {
        showUpgradeSuccessDialog()
    }

    override fun onSubscriptionPurchasePending(purchaseInfo: PurchaseInfo) = Unit

    override fun onDestroyView() {
        billingManager.removeBillingClientListener(this)
        billingManager.removeSubscriptionListener(this)
        super.onDestroyView()
    }

    private fun listenBillingManager() {
        billingManager.addBillingClientListener(this)
        billingManager.addSubscriptionListener(this)

        val displayProductIds = listOf(
            getString(hoang.dqm.codebase.R.string.billing_sub_week),
            getString(hoang.dqm.codebase.R.string.billing_sub_year)
        )

        val iapProductsFlow = pricedProductsFlow.map { products ->
            products
                .filter { displayProductIds.contains(it.productId) }
                .sortedBy { displayProductIds.indexOf(it.productId) }
        }

        combine(selectedProductIdFlow, iapProductsFlow, trialEnabledFlow) { selectedId, items, isTrialEnabled ->
            val selectedProduct = items.firstOrNull { it.productId == selectedId }
            val hasFreeTrialProduct = items.any { it.freeTrialDays > 0 }
            val effectiveTrialEnabled = isTrialEnabled && hasFreeTrialProduct

            val resolvedId = if (
                effectiveTrialEnabled &&
                (selectedProduct == null || selectedProduct.freeTrialDays == 0)
            ) {
                preferredTrialProduct(items)?.productId
            } else {
                selectedProduct?.productId
                    ?: if (effectiveTrialEnabled) {
                        preferredTrialProduct(items)?.productId
                    } else {
                        items.firstOrNull()?.productId
                    }
            }

            ProductRenderState(
                products = items.map { it to (it.productId == resolvedId) },
                isTrialEnabled = effectiveTrialEnabled,
                hasFreeTrialProduct = hasFreeTrialProduct
            )
            }
            .asLiveData()
            .observe(viewLifecycleOwner) { state ->
                if (!state.hasFreeTrialProduct && trialEnabledFlow.value) {
                    trialEnabledFlow.tryEmit(false)
                }

                val products = state.products
                displayedProducts = products.map { it.first }
                selectedProduct = products.firstOrNull { it.second }?.first
                productAdapter.setTrialEnabled(state.isTrialEnabled)
                productAdapter.setList(products)
                binding.rvProducts.isVisible = products.isNotEmpty()
                binding.layoutLoading.isVisible =
                    products.isEmpty() || !isBillingClientConnectedFlow.value
                updateSelectedProductUi()
            }

        isBillingClientConnectedFlow
            .asLiveData()
            .observe(viewLifecycleOwner) { isConnected ->
                binding.layoutLoading.isVisible = !isConnected || displayedProducts.isEmpty()
                updateSelectedProductUi()
            }
    }

    private fun toggleTrialSelection() {
        val hasFreeTrialProduct = displayedProducts.any { it.freeTrialDays > 0 }
        if (!hasFreeTrialProduct) {
            trialEnabledFlow.tryEmit(false)
            return
        }

        val shouldEnableTrial = !trialEnabledFlow.value
        trialEnabledFlow.tryEmit(shouldEnableTrial)

        if (shouldEnableTrial && selectedProduct?.freeTrialDays == 0) {
            preferredTrialProduct(displayedProducts)?.let {
                selectedProductIdFlow.tryEmit(it.productId)
            }
        }
    }

    private fun updateSelectedProductUi() {
        val hasFreeTrialProduct = displayedProducts.any { it.freeTrialDays > 0 }
        val showFreeTrial = trialEnabledFlow.value &&
            hasFreeTrialProduct &&
            (selectedProduct?.freeTrialDays ?: 0) > 0

        binding.cardTrialToggle.isVisible = hasFreeTrialProduct
        binding.ivTrialSwitch.setImageResource(
            if (trialEnabledFlow.value && hasFreeTrialProduct) {
                R.drawable.ic_switch_on
            } else {
                R.drawable.ic_switch_off
            }
        )
        binding.titleFreeT.isVisible = showFreeTrial
        binding.noPaymentRow.isVisible = showFreeTrial

        binding.btnSave.text = getString(
            if (showFreeTrial) {
                R.string.text_iap_start_free_trial
            } else {
                R.string.text_iap_start_watching_now
            }
        ).uppercase()

        val isEnabled = isBillingClientConnectedFlow.value && selectedProduct != null
        binding.btnSave.isEnabled = isEnabled
        binding.btnSave.alpha = if (isEnabled) 1f else 0.75f
    }

    private fun preferredTrialProduct(products: List<IAPProduct>): IAPProduct? {
        val yearlyProductId = getString(hoang.dqm.codebase.R.string.billing_sub_year)
        return products.firstOrNull { it.productId == yearlyProductId && it.freeTrialDays > 0 }
            ?: products.firstOrNull { it.freeTrialDays > 0 }
    }

    private fun handleClose() {
        if (isFromSplash) {
            popBackStack()
        } else {
            popBackStack()
        }
    }

    private fun showUpgradeSuccessDialog() {
        if (!isAdded) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_iap_congratulations)
            .setMessage(R.string.text_iap_purchase_success)
            .setPositiveButton(R.string.text_ok) { _, _ -> popBackStack() }
            .show()
    }

    companion object {
        const val ARG_FROM_SPLASH = "isFromSplash"
        private const val CLOSE_BUTTON_DELAY_MS = 3000L
    }
}
