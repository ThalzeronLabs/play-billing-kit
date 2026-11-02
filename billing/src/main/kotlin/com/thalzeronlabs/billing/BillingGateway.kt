package com.thalzeronlabs.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * The thin slice of Google Play Billing that [BillingKit] needs, as suspend functions.
 * [PlayBillingGateway] is the real one; tests use a fake, so all the business rules run on the JVM.
 */
interface BillingGateway {
    /** Purchases finished (or failed) in the Play purchase sheet. */
    val purchaseUpdates: Flow<PurchaseUpdate>

    suspend fun connect(): BillingStatus

    /** Subscription products (`ProductType.SUBS`). */
    suspend fun queryProducts(productIds: List<String>): Pair<BillingStatus, List<SubscriptionProduct>>

    /** Subscription purchases, including suspended ones (paused or payment declined) flagged with [OwnedPurchase.suspended]. */
    suspend fun queryPurchases(): Pair<BillingStatus, List<OwnedPurchase>>

    suspend fun acknowledge(purchaseToken: String): BillingStatus

    /** One-time products (`ProductType.INAPP`). The default returns none, for gateways that only sell subscriptions. */
    suspend fun queryOneTimeProducts(productIds: List<String>): Pair<BillingStatus, List<OneTimeProduct>> =
        BillingStatus.OK to emptyList()

    /** One-time purchases (`ProductType.INAPP`) that are not consumed. */
    suspend fun queryOneTimePurchases(): Pair<BillingStatus, List<OwnedPurchase>> = BillingStatus.OK to emptyList()

    /** Consumes a one-time purchase so it can be bought again. Consuming also acknowledges it. */
    suspend fun consume(purchaseToken: String): BillingStatus = BillingStatus.FEATURE_NOT_SUPPORTED

    /**
     * Shows Play's in-app message for payment problems (grace period, account hold), if there is one for this user.
     * [onSubscriptionUpdated] is called with the purchase token when the user fixed the problem from that message.
     */
    fun showInAppMessages(activity: Activity, onSubscriptionUpdated: (purchaseToken: String) -> Unit): BillingStatus =
        BillingStatus.FEATURE_NOT_SUPPORTED

    /**
     * Opens the Play purchase sheet for a subscription offer or a one-time offer ([offerToken] comes from
     * [SubscriptionOffer.offerToken] or [OneTimeOffer.offerToken]).
     * [oldPurchaseToken] turns it into an upgrade/downgrade of an existing subscription.
     */
    fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String,
        oldPurchaseToken: String?,
        replacement: PlanChange?,
    ): BillingStatus

    fun disconnect()
}

data class PurchaseUpdate(val status: BillingStatus, val purchases: List<OwnedPurchase>)

/** How a plan change is billed. Mirrors BillingFlowParams.SubscriptionUpdateParams.ReplacementMode. */
enum class PlanChange {
    /** Switch now; the unused time of the old plan is credited (the usual choice for upgrades). */
    WITH_TIME_PRORATION,
    /** Switch now and charge the price difference right away. */
    CHARGE_PRORATED_PRICE,
    /** Switch now; the new price starts at the next renewal date. */
    WITHOUT_PRORATION,
    /** Switch now and start a fresh billing cycle, charging the full new price. */
    CHARGE_FULL_PRICE,
    /** Keep the old plan until it renews, then switch (the usual choice for downgrades). */
    DEFERRED,
}
