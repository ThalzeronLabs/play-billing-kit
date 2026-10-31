package com.thalzeronlabs.billing

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the UI needs to render a paywall and gate features. */
data class BillingState(
    val connection: Connection = Connection.DISCONNECTED,
    val products: Map<String, SubscriptionProduct> = emptyMap(),
    /** Products the user has paid for right now. */
    val activeProductIds: Set<String> = emptySet(),
    /** Products waiting for payment (e.g. a boleto or cash payment not settled yet). Do not unlock these. */
    val pendingProductIds: Set<String> = emptySet(),
    val lastError: BillingStatus? = null,
) {
    enum class Connection { DISCONNECTED, CONNECTING, READY, UNAVAILABLE }

    fun isActive(productId: String) = productId in activeProductIds
    val hasAnySubscription: Boolean get() = activeProductIds.isNotEmpty()
}

/**
 * Subscriptions on Google Play without the usual bugs:
 * - **reconnects** with backoff when the Play service drops;
 * - **acknowledges every paid purchase** wherever it shows up (purchase sheet *and* restore), so Play never refunds
 *   a subscription after 3 days because the acknowledgement only lived in one code path;
 * - **picks the right offer** (free trial when the user is eligible) instead of `offerDetails.first()`;
 * - **changes plans** by replacing the current subscription instead of stacking a second one;
 * - exposes one `StateFlow<BillingState>` for the UI.
 *
 * ```kotlin
 * val billing = BillingKit(PlayBillingGateway(context), listOf("pro_monthly", "pro_yearly"), appScope)
 * billing.start()
 * billing.state.collect { premium = it.hasAnySubscription }
 * billing.buy(activity, "pro_yearly")
 * ```
 */
class BillingKit(
    private val gateway: BillingGateway,
    private val productIds: List<String>,
    private val scope: CoroutineScope,
    private val offerSelector: OfferSelector = OfferSelector.bestForUser(),
    private val retryDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
) {
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val refreshLock = Mutex()
    private var jobs = emptyList<Job>()

    /** Connects (retrying transient failures), loads products and purchases, and starts listening for new purchases. */
    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            scope.launch { gateway.purchaseUpdates.collect { onPurchaseUpdate(it) } },
            scope.launch { if (connectWithRetry()) refresh() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        gateway.disconnect()
        _state.update { it.copy(connection = BillingState.Connection.DISCONNECTED) }
    }

    /** Reloads products and purchases ("restore purchases"). Safe to call on every app resume. */
    suspend fun refresh() = refreshLock.withLock {
        if (_state.value.connection != BillingState.Connection.READY && !connectWithRetry()) return@withLock

        val (productStatus, products) = gateway.queryProducts(productIds)
        if (productStatus == BillingStatus.OK) {
            _state.update { s -> s.copy(products = products.associateBy { it.productId }) }
        } else {
            reportError(productStatus)
        }

        val (purchaseStatus, purchases) = gateway.queryPurchases()
        if (purchaseStatus == BillingStatus.OK) {
            applyPurchases(purchases, replaceAll = true)
        } else {
            reportError(purchaseStatus)
        }
    }

    /**
     * Opens the Play purchase sheet for [productId]. When the user already has another subscription from
     * [productIds], this becomes a plan change (see [changePlan]) so they never pay for two plans at once.
     */
    fun buy(activity: Activity, productId: String, basePlanId: String? = null): BillingStatus {
        val current = _state.value.activeProductIds.firstOrNull { it != productId && it in productIds }
        if (current != null) return changePlan(activity, productId, basePlanId)
        return launch(activity, productId, basePlanId, oldToken = null, mode = null)
    }

    /**
     * Replaces the user's current subscription with [toProductId].
     * Upgrades (a pricier plan) switch now with time proration; downgrades wait for the next renewal.
     */
    fun changePlan(
        activity: Activity,
        toProductId: String,
        basePlanId: String? = null,
        mode: PlanChange? = null,
    ): BillingStatus {
        val owned = currentPurchases.firstOrNull { it.grantsAccess && toProductId !in it.productIds }
            ?: return launch(activity, toProductId, basePlanId, oldToken = null, mode = null)
        val chosenMode = mode ?: if (isUpgrade(owned.productIds.first(), toProductId, basePlanId)) {
            PlanChange.WITH_TIME_PRORATION
        } else {
            PlanChange.DEFERRED
        }
        return launch(activity, toProductId, basePlanId, owned.purchaseToken, chosenMode)
    }

    /** The offer that [buy] would sell for this product, e.g. to show "7 days free" on the paywall. */
    fun offerFor(productId: String, basePlanId: String? = null): SubscriptionOffer? =
        _state.value.products[productId]?.let { offerSelector.select(it, basePlanId) }

    // --- internals ---

    private var currentPurchases: List<OwnedPurchase> = emptyList()

    private fun launch(activity: Activity, productId: String, basePlanId: String?, oldToken: String?, mode: PlanChange?): BillingStatus {
        val offer = offerFor(productId, basePlanId) ?: return BillingStatus.ITEM_UNAVAILABLE.also(::reportError)
        val status = gateway.launchPurchase(activity, productId, offer.offerToken, oldToken, mode)
        if (status != BillingStatus.OK) reportError(status)
        return status
    }

    private fun isUpgrade(fromProductId: String, toProductId: String, basePlanId: String?): Boolean {
        val products = _state.value.products
        val from = products[fromProductId]?.basePlan()?.recurringPhase ?: return true
        val to = products[toProductId]?.basePlan(basePlanId)?.recurringPhase ?: return true
        return monthlyMicros(to) >= monthlyMicros(from)
    }

    private suspend fun connectWithRetry(): Boolean {
        _state.update { it.copy(connection = BillingState.Connection.CONNECTING) }
        for (attempt in 0..retryDelaysMillis.size) {
            when (val status = gateway.connect()) {
                BillingStatus.OK -> {
                    _state.update { it.copy(connection = BillingState.Connection.READY, lastError = null) }
                    return true
                }
                else -> {
                    if (!status.isTransient || attempt == retryDelaysMillis.size) {
                        val connection = if (status == BillingStatus.BILLING_UNAVAILABLE) {
                            BillingState.Connection.UNAVAILABLE
                        } else {
                            BillingState.Connection.DISCONNECTED
                        }
                        _state.update { it.copy(connection = connection, lastError = status) }
                        return false
                    }
                    delay(retryDelaysMillis[attempt])
                }
            }
        }
        return false
    }

    private suspend fun onPurchaseUpdate(update: PurchaseUpdate) {
        when (update.status) {
            BillingStatus.OK -> applyPurchases(update.purchases, replaceAll = false)
            BillingStatus.ITEM_ALREADY_OWNED -> refresh() // the user owns it on another device: restore
            BillingStatus.USER_CANCELED -> Unit
            else -> reportError(update.status)
        }
    }

    private suspend fun applyPurchases(purchases: List<OwnedPurchase>, replaceAll: Boolean) {
        purchases.filter { it.needsAcknowledgement }.forEach { purchase ->
            val status = gateway.acknowledge(purchase.purchaseToken)
            if (status != BillingStatus.OK) reportError(status)
        }
        val merged = if (replaceAll) {
            purchases
        } else {
            currentPurchases.filter { old -> purchases.none { it.purchaseToken == old.purchaseToken } } + purchases
        }
        currentPurchases = merged.map { if (it.needsAcknowledgement) it.copy(acknowledged = true) else it }
        _state.update { s ->
            s.copy(
                activeProductIds = currentPurchases.filter { it.grantsAccess }.flatMap { it.productIds }.toSet(),
                pendingProductIds = currentPurchases.filter { it.state == OwnedPurchase.State.PENDING }
                    .flatMap { it.productIds }.toSet(),
            )
        }
    }

    private fun reportError(status: BillingStatus) = _state.update { it.copy(lastError = status) }

    internal companion object {
        /** Normalizes a recurring price to a monthly figure so a yearly and a monthly plan can be compared. */
        fun monthlyMicros(phase: PricingPhase): Long = when (phase.billingPeriod) {
            "P1W" -> phase.priceMicros * 52 / 12
            "P4W" -> phase.priceMicros * 13 / 12
            "P1M" -> phase.priceMicros
            "P2M" -> phase.priceMicros / 2
            "P3M" -> phase.priceMicros / 3
            "P6M" -> phase.priceMicros / 6
            "P1Y" -> phase.priceMicros / 12
            else -> phase.priceMicros
        }
    }
}
