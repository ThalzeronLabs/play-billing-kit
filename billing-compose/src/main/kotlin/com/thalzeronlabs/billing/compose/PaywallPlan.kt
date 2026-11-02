package com.thalzeronlabs.billing.compose

import androidx.compose.runtime.Immutable
import com.thalzeronlabs.billing.BillingKit
import com.thalzeronlabs.billing.BillingState
import com.thalzeronlabs.billing.OneTimeOffer
import com.thalzeronlabs.billing.OneTimeProduct
import com.thalzeronlabs.billing.SubscriptionOffer
import com.thalzeronlabs.billing.SubscriptionProduct
import com.thalzeronlabs.billing.SubscriptionStatus

/**
 * What one plan card shows, built from what Google Play returned. Every price is Play's own formatted string
 * (already localized, with the right currency), never a number formatted by the app.
 */
@Immutable
data class PaywallPlan(
    val productId: String,
    val title: String,
    /** What the user pays once any promotion is over, formatted by Play, e.g. "R$ 9,90". */
    val price: String,
    /** ISO-8601 billing period of [price] (e.g. "P1M"); null for a one-time product. */
    val billingPeriod: String? = null,
    /** ISO-8601 period of the free trial (e.g. "P1W"), set only when Play offers this user a trial. */
    val freeTrialPeriod: String? = null,
    /** How many [freeTrialPeriod]s the trial lasts. */
    val freeTrialCycles: Int = 1,
    /** Discounted first price formatted by Play (e.g. "R$ 4,90"), when the offer starts with one. */
    val introPrice: String? = null,
    val introBillingPeriod: String? = null,
    val introCycles: Int = 0,
    val basePlanId: String? = null,
    /** True for one-time products (lifetime or consumable). */
    val oneTime: Boolean = false,
    /** The user has this plan right now. */
    val owned: Boolean = false,
    /** A payment for this plan is waiting to clear. */
    val pending: Boolean = false,
    /** The subscription status, when the user has or had this subscription. */
    val status: SubscriptionStatus? = null,
) {
    val hasFreeTrial: Boolean get() = freeTrialPeriod != null

    companion object {
        /** A subscription [offer] (usually [BillingKit.offerFor]) as a plan card. */
        fun from(product: SubscriptionProduct, offer: SubscriptionOffer, state: BillingState = BillingState()): PaywallPlan {
            val recurring = offer.recurringPhase
            val trial = offer.phases.firstOrNull { it.priceMicros == 0L }
            val intro = offer.phases.dropLast(1).firstOrNull { it.priceMicros in 1 until recurring.priceMicros }
            return PaywallPlan(
                productId = product.productId,
                title = product.title,
                price = recurring.formattedPrice,
                billingPeriod = recurring.billingPeriod,
                freeTrialPeriod = trial?.billingPeriod,
                freeTrialCycles = trial?.cycles?.coerceAtLeast(1) ?: 1,
                introPrice = intro?.formattedPrice,
                introBillingPeriod = intro?.billingPeriod,
                introCycles = intro?.cycles ?: 0,
                basePlanId = offer.basePlanId,
                owned = state.isActive(product.productId),
                pending = product.productId in state.pendingProductIds,
                status = state.subscription(product.productId)?.status,
            )
        }

        /** A one-time [offer] (usually [BillingKit.oneTimeOfferFor]) as a plan card. */
        fun from(product: OneTimeProduct, offer: OneTimeOffer, state: BillingState = BillingState()): PaywallPlan =
            PaywallPlan(
                productId = product.productId,
                title = product.title,
                price = offer.formattedPrice,
                oneTime = true,
                owned = state.isActive(product.productId),
                pending = product.productId in state.pendingProductIds,
            )
    }
}

/**
 * The plan cards for [productIds], in that order, each with the offer [BillingKit.buy] would sell.
 * Subscriptions and one-time products can be mixed; products Play did not return are skipped.
 */
fun BillingKit.paywallPlans(productIds: List<String>, state: BillingState = this.state.value): List<PaywallPlan> =
    productIds.mapNotNull { id ->
        state.products[id]?.let { product -> offerFor(id)?.let { PaywallPlan.from(product, it, state) } }
            ?: state.oneTimeProducts[id]?.let { product -> oneTimeOfferFor(id)?.let { PaywallPlan.from(product, it, state) } }
    }
