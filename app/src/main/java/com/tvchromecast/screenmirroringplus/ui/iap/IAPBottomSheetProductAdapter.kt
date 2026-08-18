package com.tvchromecast.screenmirroringplus.ui.iap

import android.view.View
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.ItemIapBottomSheetProductBinding
import hoang.dqm.codebase.base.adapter.BaseRecyclerViewAdapter
import tpt.dev.monetization.subs.model.IAPProduct
import tpt.dev.monetization.subs.model.IAPProductPeriods
import tpt.dev.monetization.subs.model.periods
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class IAPBottomSheetProductAdapter :
    BaseRecyclerViewAdapter<ProductWithSelection, ItemIapBottomSheetProductBinding>() {

    private var clickListener: ((item: IAPProduct, position: Int) -> Unit)? = null

    override fun bindData(
        binding: ItemIapBottomSheetProductBinding,
        item: ProductWithSelection,
        position: Int
    ) {
        val (product, isSelected) = item
        val period = product.periods()

        binding.tvNameProduct.text = getNamePeriod(product)
        binding.tvDescription.text = getDescription(product)
        binding.textPrice.text = getDisplayPrice(getRegularPrice(product), period)
        binding.textPriceDes.text = getWeeklyPrice(product)
        binding.textPriceDes.visibility =
            if (period == IAPProductPeriods.Yearly) View.VISIBLE else View.GONE
        binding.bestDeal.visibility = if (product.isOneTime) View.VISIBLE else View.GONE
        binding.iconSelected.setImageResource(
            if (isSelected) R.drawable.ic_iap_check else R.drawable.ic_iap_unchecked
        )
        binding.cardProduct.setBackgroundResource(
            if (isSelected) R.drawable.bg_iap_plan_selected else R.drawable.bg_iap_plan_unselected
        )
        binding.textPrice.setTextColor(
            context.getColor(if (isSelected) R.color.colorPrimaryLight else R.color.white)
        )

        binding.root.setOnClickListener {
            clickListener?.invoke(product, position)
        }
        binding.cardProduct.setOnClickListener {
            clickListener?.invoke(product, position)
        }
    }

    fun setOnClickItem(listener: (item: IAPProduct, position: Int) -> Unit) {
        clickListener = listener
    }

    private fun getRegularPrice(product: IAPProduct): String {
        if (product.productDetails?.productType == BillingClient.ProductType.INAPP) {
            return product.productDetails
                ?.oneTimePurchaseOfferDetails
                ?.formattedPrice
                .orEmpty()
        }

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

    private fun getNamePeriod(product: IAPProduct): String = when {
        product.isOneTime -> context.getString(R.string.text_iap_lifetime)
        product.periods() == IAPProductPeriods.Weekly -> context.getString(R.string.text_iap_weekly)
        product.periods() == IAPProductPeriods.Monthly -> context.getString(R.string.text_iap_monthly)
        product.periods() == IAPProductPeriods.Yearly -> context.getString(R.string.text_iap_yearly)
        else -> product.productDetails?.name ?: product.productId
    }

    private fun getDescription(product: IAPProduct): String {
        if (product.freeTrialDays > 0) return getTrialText(product)
        if (product.isOneTime) return context.getString(R.string.text_iap_one_time)

        return when (product.periods()) {
            IAPProductPeriods.Weekly -> context.getString(R.string.text_iap_cancel_anytime)
            IAPProductPeriods.Yearly -> context.getString(R.string.text_iap_save_up_to)
            else -> context.getString(R.string.text_iap_save_up_to)
        }
    }

    private fun getTrialText(product: IAPProduct): String {
        return if (product.freeTrialDays == 3) {
            context.getString(R.string.text_iap_three_day_free_trial)
        } else {
            context.getString(R.string.text_iap_free_trial_name, product.freeTrialDays.toString())
        }
    }

    private fun getWeeklyPrice(product: IAPProduct): String {
        val phase = product.productDetails
            ?.subscriptionOfferDetails
            ?.flatMap { it.pricingPhases.pricingPhaseList }
            ?.firstOrNull {
                it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
            } ?: return ""

        val weeklyMicros = when {
            phase.billingPeriod.contains("W") -> phase.priceAmountMicros.toDouble()
            phase.billingPeriod.contains("M") -> phase.priceAmountMicros / 4.0
            phase.billingPeriod.contains("Y") -> phase.priceAmountMicros / 50.0
            else -> phase.priceAmountMicros.toDouble()
        }

        val formatted = formatCurrency(weeklyMicros, phase.priceCurrencyCode) ?: return ""
        return context.getString(R.string.text_iap_weekly_price, formatted)
    }

    private fun formatCurrency(priceMicros: Double?, currencyCode: String?): String? {
        if (priceMicros == null) return null

        return runCatching {
            val amount = priceMicros / 1_000_000.0
            val isWholeNumber = abs(amount - amount.roundToLong().toDouble()) < 0.000001
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                if (!currencyCode.isNullOrBlank()) {
                    currency = Currency.getInstance(currencyCode)
                }
                minimumFractionDigits = 0
                maximumFractionDigits = if (isWholeNumber) 0 else 2
            }.format(amount)
        }.getOrNull()
    }
}
