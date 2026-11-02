package com.thalzeronlabs.billing

/** A subscription product as configured in the Play Console, with every base plan and offer the user can buy. */
data class SubscriptionProduct(
    val productId: String,
    val title: String,
    val offers: List<SubscriptionOffer>,
) {
    /** The plain base plan (no promotion) of [basePlanId], or of the first base plan when null. */
    fun basePlan(basePlanId: String? = null): SubscriptionOffer? =
        offers.firstOrNull { it.isBasePlan && (basePlanId == null || it.basePlanId == basePlanId) }
}

/**
 * One purchasable option. Google only returns offers the current user is eligible for,
 * so a free-trial offer showing up here means this user can actually get the trial.
 */
data class SubscriptionOffer(
    val basePlanId: String,
    /** Null for the base plan itself; set for promotional offers (trials, intro prices). */
    val offerId: String?,
    /** Opaque token Play needs to launch the purchase of exactly this offer. */
    val offerToken: String,
    val tags: List<String> = emptyList(),
    val phases: List<PricingPhase>,
) {
    val isBasePlan: Boolean get() = offerId == null
    val hasFreeTrial: Boolean get() = phases.any { it.priceMicros == 0L }

    /** The price the user pays once every promotional phase is over. */
    val recurringPhase: PricingPhase get() = phases.last()
}

data class PricingPhase(
    /** Already localized by Play, e.g. "R$ 9,90". Show this, never format micros yourself. */
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    /** ISO-8601 period, e.g. "P1M", "P1Y", "P7D". */
    val billingPeriod: String,
    /** How many times this phase repeats; 0 means it recurs forever. */
    val cycles: Int,
)

/**
 * A one-time product (`ProductType.INAPP`) as configured in the Play Console: something bought once, either kept
 * forever (a non-consumable such as "remove ads") or used up and bought again (a consumable such as coins).
 * Whether it is consumable is your decision, not Play's: tell [BillingKit] through `consumableProductIds`.
 */
data class OneTimeProduct(
    val productId: String,
    val title: String,
    /**
     * The purchase options and offers this user can buy. Since Billing Library 8 a one-time product can have several
     * (e.g. a regular price and a discount); Play only returns the ones the user is eligible for.
     */
    val offers: List<OneTimeOffer>,
) {
    /** The offer [BillingKit.buy] sells: the cheapest one, skipping offers tagged [excludeTag]. */
    fun bestOffer(excludeTag: String = "manual-only"): OneTimeOffer? =
        offers.filter { excludeTag !in it.tags }.minByOrNull { it.priceMicros }
}

/** One way to buy a [OneTimeProduct]: a purchase option, or a discounted offer on top of one. */
data class OneTimeOffer(
    /** Opaque token Play needs to launch the purchase of exactly this offer. */
    val offerToken: String,
    /** Already localized by Play, e.g. "R$ 9,90". Show this, never format micros yourself. */
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    val purchaseOptionId: String? = null,
    /** Null for the plain purchase option; set for discounted offers. */
    val offerId: String? = null,
    /** The price before the discount, when this offer is a discount. */
    val fullPriceMicros: Long? = null,
    val tags: List<String> = emptyList(),
)

/** A purchase Play reports for this user. */
data class OwnedPurchase(
    val purchaseToken: String,
    val productIds: List<String>,
    val state: State,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    /** How many units were bought at once (multi-quantity one-time products); 1 otherwise. */
    val quantity: Int = 1,
    /**
     * A subscription Play reports as suspended (`Purchase.isSuspended()`, Billing Library 8.1+): the payment failed
     * and the user lost access until they fix it. Only returned when queried with `includeSuspendedSubscriptions`.
     */
    val suspended: Boolean = false,
) {
    enum class State { PURCHASED, PENDING, UNSPECIFIED }

    /** Paid, not pending and not suspended: this purchase unlocks its products. */
    val grantsAccess: Boolean get() = state == State.PURCHASED && !suspended

    /** Paid but never acknowledged: Play refunds it automatically after 3 days if nobody acknowledges it. */
    val needsAcknowledgement: Boolean get() = state == State.PURCHASED && !acknowledged
}

/** Result of a Billing call, mapped from BillingClient.BillingResponseCode. */
enum class BillingStatus {
    OK, USER_CANCELED, ITEM_ALREADY_OWNED, ITEM_NOT_OWNED, ITEM_UNAVAILABLE,
    SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED, BILLING_UNAVAILABLE, NETWORK_ERROR,
    DEVELOPER_ERROR, FEATURE_NOT_SUPPORTED, ERROR;

    /** Worth trying again later without user action. */
    val isTransient: Boolean
        get() = this == SERVICE_UNAVAILABLE || this == SERVICE_DISCONNECTED || this == NETWORK_ERROR || this == ERROR
}
