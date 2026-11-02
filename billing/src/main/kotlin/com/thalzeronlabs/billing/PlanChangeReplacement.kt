package com.thalzeronlabs.billing

import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-product replacement mode for [change], for `SubscriptionProductReplacementParams`
 * ([preferred since Billing Library 8.1](https://developer.android.com/google/play/billing/subscriptions#use_subscriptionproductreplacementparams_for_replacement_preferred)).
 * Its constants differ from the deprecated `SubscriptionUpdateParams.ReplacementMode` ones, so never mix the two.
 */
internal fun productReplacementModeOf(change: PlanChange): Int = when (change) {
    PlanChange.WITH_TIME_PRORATION -> ReplacementMode.WITH_TIME_PRORATION
    PlanChange.CHARGE_PRORATED_PRICE -> ReplacementMode.CHARGE_PRORATED_PRICE
    PlanChange.WITHOUT_PRORATION -> ReplacementMode.WITHOUT_PRORATION
    PlanChange.CHARGE_FULL_PRICE -> ReplacementMode.CHARGE_FULL_PRICE
    PlanChange.DEFERRED -> ReplacementMode.DEFERRED
}

/**
 * Which product each purchase token Play reported belongs to. A plan change names the purchase to replace by its
 * token, while the per-product replacement params need the old product id; this bridges the two without changing
 * [BillingGateway.launchPurchase].
 */
internal class OwnedProductIndex {
    private val productByToken = ConcurrentHashMap<String, String>()

    fun record(purchases: List<OwnedPurchase>) {
        purchases.forEach { purchase -> purchase.productIds.firstOrNull()?.let { productByToken[purchase.purchaseToken] = it } }
    }

    fun productOf(purchaseToken: String): String? = productByToken[purchaseToken]
}
