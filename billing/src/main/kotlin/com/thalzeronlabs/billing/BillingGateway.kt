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
    suspend fun queryProducts(productIds: List<String>): Pair<BillingStatus, List<SubscriptionProduct>>
    suspend fun queryPurchases(): Pair<BillingStatus, List<OwnedPurchase>>
    suspend fun acknowledge(purchaseToken: String): BillingStatus

    /** Opens the Play purchase sheet. [oldPurchaseToken] turns it into an upgrade/downgrade of an existing subscription. */
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
