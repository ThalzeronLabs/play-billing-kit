package com.thalzeronlabs.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableSharedFlow

internal fun phase(price: Long, period: String = "P1M", cycles: Int = 0) =
    PricingPhase(formattedPrice = "R$ ${price / 1_000_000}", priceMicros = price, currencyCode = "BRL", billingPeriod = period, cycles = cycles)

internal fun offer(
    basePlan: String,
    offerId: String? = null,
    vararg phases: PricingPhase,
    tags: List<String> = emptyList(),
) = SubscriptionOffer(basePlan, offerId, "token-$basePlan-${offerId ?: "base"}", tags, phases.toList())

/** Monthly R$ 10 with a 7-day trial, and yearly R$ 100. */
internal val PRO_MONTHLY = SubscriptionProduct(
    "pro_monthly", "Pro",
    listOf(
        offer("monthly", null, phase(10_000_000)),
        offer("monthly", "trial7", phase(0, "P1W", 1), phase(10_000_000)),
    ),
)
internal val PRO_YEARLY = SubscriptionProduct("pro_yearly", "Pro yearly", listOf(offer("yearly", null, phase(100_000_000, "P1Y"))))
internal val ELITE_MONTHLY = SubscriptionProduct("elite_monthly", "Elite", listOf(offer("monthly", null, phase(30_000_000))))

internal fun owned(product: String, token: String = "tok-$product", acknowledged: Boolean = true, pending: Boolean = false) =
    OwnedPurchase(
        purchaseToken = token,
        productIds = listOf(product),
        state = if (pending) OwnedPurchase.State.PENDING else OwnedPurchase.State.PURCHASED,
        acknowledged = acknowledged,
        autoRenewing = true,
    )

internal class FakeGateway(
    var products: List<SubscriptionProduct> = listOf(PRO_MONTHLY, PRO_YEARLY, ELITE_MONTHLY),
    var purchases: List<OwnedPurchase> = emptyList(),
    connectResults: List<BillingStatus> = listOf(BillingStatus.OK),
) : BillingGateway {
    private val connectQueue = ArrayDeque(connectResults)
    val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val purchaseUpdates = updates

    var connectCalls = 0
    val acknowledged = mutableListOf<String>()
    val launches = mutableListOf<Launch>()
    var disconnected = false

    data class Launch(val productId: String, val offerToken: String, val oldToken: String?, val mode: PlanChange?)

    override suspend fun connect(): BillingStatus {
        connectCalls++
        return if (connectQueue.size > 1) connectQueue.removeFirst() else connectQueue.first()
    }

    override suspend fun queryProducts(productIds: List<String>) =
        BillingStatus.OK to products.filter { it.productId in productIds }

    override suspend fun queryPurchases() = BillingStatus.OK to purchases

    override suspend fun acknowledge(purchaseToken: String): BillingStatus {
        acknowledged += purchaseToken
        return BillingStatus.OK
    }

    override fun launchPurchase(activity: Activity, productId: String, offerToken: String, oldPurchaseToken: String?, replacement: PlanChange?): BillingStatus {
        launches += Launch(productId, offerToken, oldPurchaseToken, replacement)
        return BillingStatus.OK
    }

    override fun disconnect() {
        disconnected = true
    }
}
