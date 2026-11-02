package com.thalzeronlabs.billing.sample

import android.app.Activity
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.BillingStatus
import com.thalzeronlabs.billing.OneTimeOffer
import com.thalzeronlabs.billing.OneTimeProduct
import com.thalzeronlabs.billing.OwnedPurchase
import com.thalzeronlabs.billing.PlanChange
import com.thalzeronlabs.billing.PricingPhase
import com.thalzeronlabs.billing.PurchaseUpdate
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionOffer
import com.thalzeronlabs.billing.SubscriptionProduct
import com.thalzeronlabs.billing.SubscriptionStatus
import com.thalzeronlabs.billing.SubscriptionStatusSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An in-memory stand-in for Google Play, so the sample runs on any emulator without a Play Console account.
 *
 * It sells two plans, `pro_monthly` (with a 7-day free trial) and `pro_yearly`, a lifetime `remove_ads` and a
 * consumable `coins_100`. Every purchase succeeds at once (there is no purchase sheet), and what the user owns lives
 * only in memory, like a store that forgets everything when the process dies. Plan changes follow what Play
 * documents: an immediate replacement retires the old purchase; a [PlanChange.DEFERRED] one hands out a new token
 * that stays on the old product until the renewal date, which this fake never reaches.
 *
 * It also plays the part of your backend ([SubscriptionStatusSource]) so the demo can [simulate] the subscription
 * states Play reports: grace period, account hold, paused and canceled.
 *
 * To go live, replace it with `PlayBillingGateway(context)`; see [MainActivity].
 */
class FakeBillingGateway(private val latencyMillis: Long = 400) : BillingGateway, SubscriptionStatusSource {

    private val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val purchaseUpdates: Flow<PurchaseUpdate> = updates

    /** What "Google's servers" know this user owns. */
    private val owned = mutableListOf<OwnedPurchase>()
    private var nextToken = 1

    /** What "your backend" says about the user's subscription, set by [simulate]. Null: whatever the device says. */
    @Volatile
    private var simulated: SubscriptionStatus? = null

    override suspend fun connect(): BillingStatus {
        delay(latencyMillis)
        return BillingStatus.OK
    }

    override suspend fun queryProducts(productIds: List<String>): Pair<BillingStatus, List<SubscriptionProduct>> {
        delay(latencyMillis)
        return BillingStatus.OK to CATALOG.filter { it.productId in productIds }
    }

    override suspend fun queryOneTimeProducts(productIds: List<String>): Pair<BillingStatus, List<OneTimeProduct>> {
        delay(latencyMillis)
        return BillingStatus.OK to ONE_TIME_CATALOG.filter { it.productId in productIds }
    }

    override suspend fun queryPurchases(): Pair<BillingStatus, List<OwnedPurchase>> {
        delay(latencyMillis)
        // Like Play: a subscription on hold or paused is not returned; a canceled one is, without auto-renewal.
        val status = simulated
        val subscriptions = snapshot().filter { it.isSubscription() }
        return BillingStatus.OK to when (status) {
            SubscriptionStatus.ON_HOLD, SubscriptionStatus.PAUSED -> emptyList()
            SubscriptionStatus.CANCELED -> subscriptions.map { it.copy(autoRenewing = false) }
            else -> subscriptions
        }
    }

    override suspend fun queryOneTimePurchases(): Pair<BillingStatus, List<OwnedPurchase>> {
        delay(latencyMillis)
        return BillingStatus.OK to snapshot().filterNot { it.isSubscription() }
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

    override suspend fun consume(purchaseToken: String): BillingStatus {
        delay(latencyMillis / 4)
        return synchronized(owned) {
            if (owned.removeAll { it.purchaseToken == purchaseToken }) BillingStatus.OK else BillingStatus.ITEM_NOT_OWNED
        }
    }

    override fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String,
        oldPurchaseToken: String?,
        replacement: PlanChange?,
    ): BillingStatus {
        val purchase = synchronized(owned) {
            if (productId in ONE_TIME_IDS && owned.any { productId in it.productIds }) {
                // Like Play: a lifetime product, or a consumable not consumed yet, cannot be bought again.
                updates.tryEmit(PurchaseUpdate(BillingStatus.ITEM_ALREADY_OWNED, emptyList()))
                return BillingStatus.OK
            }
            val old = owned.firstOrNull { it.purchaseToken == oldPurchaseToken }
            if (oldPurchaseToken != null && old == null) return BillingStatus.ITEM_NOT_OWNED
            val products = if (old != null && replacement == PlanChange.DEFERRED) old.productIds else listOf(productId)
            if (old != null) owned.remove(old)
            OwnedPurchase(
                purchaseToken = "fake-token-${nextToken++}",
                productIds = products,
                state = OwnedPurchase.State.PURCHASED,
                acknowledged = false, // like Play: the app must acknowledge (or consume) it, and BillingKit does
                autoRenewing = productId !in ONE_TIME_IDS,
            ).also { owned += it }
        }
        if (purchase.isSubscription()) simulated = null
        updates.tryEmit(PurchaseUpdate(BillingStatus.OK, listOf(purchase)))
        return BillingStatus.OK
    }

    override fun disconnect() = Unit

    /** The fake backend: reports [simulate]d statuses for the subscriptions the user has. */
    override suspend fun fetch(devicePurchases: List<OwnedPurchase>): List<SubscriptionInfo> {
        val status = simulated ?: return emptyList()
        val inAMonth = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        return snapshot().filter { it.isSubscription() }.flatMap { purchase ->
            purchase.productIds.map { SubscriptionInfo(it, status, purchase.purchaseToken, expiryTimeMillis = inAMonth) }
        }
    }

    /** Demo only: pretends Play moved the user's subscription to [status] (null: back to normal). Refresh after. */
    fun simulate(status: SubscriptionStatus?) {
        simulated = status
    }

    /** Forgets every purchase, to replay the paywall from scratch. Call [com.thalzeronlabs.billing.BillingKit.refresh] after. */
    fun clearPurchases() {
        synchronized(owned) { owned.clear() }
        simulated = null
    }

    private fun snapshot() = synchronized(owned) { owned.toList() }

    private fun OwnedPurchase.isSubscription() = productIds.none { it in ONE_TIME_IDS }

    companion object {
        const val MONTHLY = "pro_monthly"
        const val YEARLY = "pro_yearly"
        const val REMOVE_ADS = "remove_ads"
        const val COINS = "coins_100"

        /** Coins granted per [COINS] purchase. */
        const val COINS_PER_PACK = 100

        private val ONE_TIME_IDS = setOf(REMOVE_ADS, COINS)

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

        val ONE_TIME_CATALOG = listOf(
            OneTimeProduct(REMOVE_ADS, "Remove ads forever", listOf(OneTimeOffer("offer-remove-ads", "$2.99", 2_990_000, "USD"))),
            OneTimeProduct(COINS, "100 coins", listOf(OneTimeOffer("offer-coins", "$0.99", 990_000, "USD"))),
        )
    }
}
