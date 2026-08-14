package com.tvchromecast.screenmirroringplus.ui.iap

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.FragmentIapBottomSheetBinding
import com.tvchromecast.screenmirroringplus.utils.Common
import hoang.dqm.codebase.base.activity.BaseBottomSheetFragment
import hoang.dqm.codebase.base.application.appInfo
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.billing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tpt.dev.monetization.subs.listener.BillingClientListener
import tpt.dev.monetization.subs.model.IAPProduct

class IAPBottomSheetFragment : BaseBottomSheetFragment<FragmentIapBottomSheetBinding>(),
    BillingClientListener {

    override fun getTheme(): Int = R.style.IAPBottomSheet

    var onDismissToHome: (() -> Unit)? = null

    private val billingManager by lazy { AppMonetization.billing }
    private val isBillingClientConnectedFlow by lazy {
        MutableStateFlow(billingManager.isConnected())
    }
    private val pricedProductsFlow by lazy {
        MutableStateFlow(billingManager.getPricedProducts())
    }
    private val selectedProductIdFlow = MutableStateFlow<String?>(null)
    private val productAdapter by lazy { IAPBottomSheetProductAdapter() }

    private var selectedProduct: IAPProduct? = null
    private var displayedProducts: List<IAPProduct> = emptyList()
    private var navigateHomeOnDismiss = true

    override fun getVB(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentIapBottomSheetBinding {
        _binding = FragmentIapBottomSheetBinding.inflate(inflater, container, false)
        return binding
    }

    override fun initView() {
        productAdapter.setOnClickItem { product, _ ->
            selectedProductIdFlow.tryEmit(product.productId)
        }

        binding.rvProducts.apply {
            adapter = productAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.btnSave.setOnClickListener {
            launchSelectedPurchase()
        }
        binding.textTerm.setOnClickListener {
            Common.openWebView(requireContext(), appInfo().term)
        }
        binding.textPolicy.setOnClickListener {
            Common.openWebView(requireContext(), appInfo().policy)
        }

        updateActionUi()
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

    override fun onLaunchPurchaseComplete(isSuccess: Boolean) = Unit

    override fun onStart() {
        super.onStart()
        configureSheetWindow()
        addDelayedCloseButton()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (navigateHomeOnDismiss) {
            onDismissToHome?.invoke()
        }
    }

    override fun onDestroyView() {
        billingManager.removeBillingClientListener(this)
        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        setStyle(STYLE_NORMAL, R.style.IAPBottomSheet)
    }

    fun dismissWithoutNavigation() {
        navigateHomeOnDismiss = false
        dismissAllowingStateLoss()
    }

    private fun listenBillingManager() {
        billingManager.addBillingClientListener(this)

        val displayProductIds = listOf(
            getString(hoang.dqm.codebase.R.string.billing_sub_week),
            getString(hoang.dqm.codebase.R.string.billing_sub_year),
            getString(hoang.dqm.codebase.R.string.billing_lifetime)
        )

        val iapProductsFlow = pricedProductsFlow.map { products ->
            products
                .filter { displayProductIds.contains(it.productId) }
                .sortedBy { displayProductIds.indexOf(it.productId) }
        }

        combine(selectedProductIdFlow, iapProductsFlow) { selectedId, items ->
            val resolvedId = selectedId
                ?.takeIf { id -> items.any { it.productId == id } }
                ?: preferredTrialProduct(items)?.productId
                ?: items.firstOrNull()?.productId

            items.map { it to (it.productId == resolvedId) }
        }
            .asLiveData()
            .observe(viewLifecycleOwner) { products ->
                displayedProducts = products.map { it.first }
                selectedProduct = products.firstOrNull { it.second }?.first
                productAdapter.setList(products)
                binding.rvProducts.isVisible = products.isNotEmpty()
                updateActionUi()
            }

        isBillingClientConnectedFlow
            .asLiveData()
            .observe(viewLifecycleOwner) {
                updateActionUi()
            }
    }

    private fun preferredTrialProduct(products: List<IAPProduct>): IAPProduct? {
        val weeklyProductId = getString(hoang.dqm.codebase.R.string.billing_sub_week)
        return products.firstOrNull { it.productId == weeklyProductId && it.freeTrialDays > 0 }
            ?: products.firstOrNull { it.freeTrialDays > 0 }
    }

    private fun launchSelectedPurchase() {
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

    private fun updateActionUi() {
        val product = selectedProduct
        val hasProducts = displayedProducts.isNotEmpty()
        val isEnabled = isBillingClientConnectedFlow.value && product != null

        binding.layoutLoading.isVisible = !hasProducts
        binding.btnSave.text = getString(
            if ((product?.freeTrialDays ?: 0) > 0) {
                R.string.text_iap_start_free_now
            } else {
                R.string.text_iap_get_now
            }
        )
        binding.btnSave.isEnabled = isEnabled
        binding.btnSave.alpha = if (isEnabled) 1f else 0.75f
    }

    private fun configureSheetWindow() {
        val dialog = dialog as? BottomSheetDialog ?: return

        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0.55f)
        }

        val bottomSheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return

        (bottomSheet.parent as? View)?.apply {
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.setMargins(0, 0, 0, 0)
                layoutParams = params
            }
        }

        dialog.findViewById<ViewGroup>(android.R.id.content)?.setPadding(0, 0, 0, 0)

        val layoutParams = bottomSheet.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
        bottomSheet.layoutParams = layoutParams

        bottomSheet.background = ColorDrawable(Color.TRANSPARENT)

        BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = false
            isHideable = false
            skipCollapsed = true
            peekHeight = layoutParams.height
        }

        bottomSheet.requestLayout()
    }

    private fun addDelayedCloseButton() {
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        val parent = bottomSheet.parent as? ViewGroup ?: return

        if (parent.findViewWithTag<View>(CLOSE_BUTTON_TAG) != null) return

        val closeButton = ImageView(requireContext()).apply {
            tag = CLOSE_BUTTON_TAG
            setImageResource(R.drawable.ic_close_iap)
            background = requireContext().getDrawable(R.drawable.bg_iap_close)
            setPadding(16, 16, 16, 16)
            visibility = View.INVISIBLE
            setOnClickListener { dismiss() }
        }

        val sizePx = (36 * resources.displayMetrics.density).toInt()
        val params = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            sizePx,
            sizePx
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._12sdp)
            leftMargin = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._12sdp)
        }

        parent.addView(closeButton, params)
        closeButton.postDelayed({
            if (isAdded && !isDetached && view != null) {
                closeButton.visibility = View.VISIBLE
            }
        }, CLOSE_BUTTON_DELAY_MS)
    }

    companion object {
        private const val CLOSE_BUTTON_TAG = "iap_bottom_sheet_close"
        private const val CLOSE_BUTTON_DELAY_MS = 3000L
        private const val SHEET_HEIGHT_RATIO = 0.58f
    }
}
