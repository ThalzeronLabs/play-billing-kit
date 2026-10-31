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

/** A purchase Play reports for this user. */
data class OwnedPurchase(
    val purchaseToken: String,
    val productIds: List<String>,
    val state: State,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
) {
    enum class State { PURCHASED, PENDING, UNSPECIFIED }

    /** Paid and not pending: this purchase unlocks its products. */
    val grantsAccess: Boolean get() = state == State.PURCHASED

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
