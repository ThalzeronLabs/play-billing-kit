package com.thalzeronlabs.billing.sample

import android.app.Activity
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.BillingStatus
import com.thalzeronlabs.billing.OwnedPurchase
import com.thalzeronlabs.billing.PlanChange
import com.thalzeronlabs.billing.PricingPhase
import com.thalzeronlabs.billing.PurchaseUpdate
import com.thalzeronlabs.billing.SubscriptionOffer
import com.thalzeronlabs.billing.SubscriptionProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An in-memory stand-in for Google Play, so the sample runs on any emulator without a Play Console account.
 *
 * It sells two plans: `pro_monthly` (with a 7-day free trial) and `pro_yearly`. Every purchase succeeds at once
 * (there is no purchase sheet), and what the user owns lives only in memory, like a store that forgets everything
 * when the process dies. Plan changes follow what Play documents: an immediate replacement retires the old
 * purchase; a [PlanChange.DEFERRED] one hands out a new token that stays on the old product until the renewal
 * date, which this fake never reaches.
 *
 * To go live, replace it with `PlayBillingGateway(context)`; see [MainActivity].
 */
class FakeBillingGateway(private val latencyMillis: Long = 400) : BillingGateway {

    private val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val purchaseUpdates: Flow<PurchaseUpdate> = updates

    /** What "Google's servers" know this user owns. */
    private val owned = mutableListOf<OwnedPurchase>()
    private var nextToken = 1

    override suspend fun connect(): BillingStatus {
        delay(latencyMillis)
        return BillingStatus.OK
    }

    override suspend fun queryProducts(productIds: List<String>): Pair<BillingStatus, List<SubscriptionProduct>> {
        delay(latencyMillis)
        return BillingStatus.OK to CATALOG.filter { it.productId in productIds }
    }

    override suspend fun queryPurchases(): Pair<BillingStatus, List<OwnedPurchase>> {
        delay(latencyMillis)
        return BillingStatus.OK to synchronized(owned) { owned.toList() }
    }

    override suspend fun acknowledge(purchaseToken: String): BillingStatus {
        delay(latencyMillis / 4)
        synchronized(owned) {
            val index = owned.indexOfFirst { it.purchaseToken == purchaseToken }
            if (index < 0) return BillingStatus.ITEM_NOT_OWNED
            owned[index] = owned[index].copy(acknowledged = true)
        }
        return BillingStatus.OK
    }

    override fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String,
        oldPurchaseToken: String?,
        replacement: PlanChange?,
    ): BillingStatus {
        val purchase = synchronized(owned) {
            val old = owned.firstOrNull { it.purchaseToken == oldPurchaseToken }
            if (oldPurchaseToken != null && old == null) return BillingStatus.ITEM_NOT_OWNED
            val products = if (old != null && replacement == PlanChange.DEFERRED) old.productIds else listOf(productId)
            if (old != null) owned.remove(old)
            OwnedPurchase(
                purchaseToken = "fake-token-${nextToken++}",
                productIds = products,
                state = OwnedPurchase.State.PURCHASED,
                acknowledged = false, // like Play: the app must acknowledge it, and BillingKit does
                autoRenewing = true,
            ).also { owned += it }
        }
        updates.tryEmit(PurchaseUpdate(BillingStatus.OK, listOf(purchase)))
        return BillingStatus.OK
    }

    override fun disconnect() = Unit

    /** Forgets every purchase, to replay the paywall from scratch. Call [com.thalzeronlabs.billing.BillingKit.refresh] after. */
    fun clearPurchases() = synchronized(owned) { owned.clear() }

    companion object {
        const val MONTHLY = "pro_monthly"
        const val YEARLY = "pro_yearly"

        private fun phase(formatted: String, micros: Long, period: String, cycles: Int = 0) =
            PricingPhase(formatted, micros, "USD", period, cycles)

        val CATALOG = listOf(
            SubscriptionProduct(
                productId = MONTHLY,
                title = "Pro monthly",
                offers = listOf(
                    SubscriptionOffer("monthly", null, "offer-monthly-base", phases = listOf(phase("$4.99", 4_990_000, "P1M"))),
                    SubscriptionOffer(
                        "monthly", "trial-7d", "offer-monthly-trial",
                        phases = listOf(phase("Free", 0, "P1W", cycles = 1), phase("$4.99", 4_990_000, "P1M")),
                    ),
                ),
            ),
            SubscriptionProduct(
                productId = YEARLY,
                title = "Pro yearly",
                offers = listOf(
                    SubscriptionOffer("yearly", null, "offer-yearly-base", phases = listOf(phase("$39.99", 39_990_000, "P1Y"))),
                ),
            ),
        )
    }
}
