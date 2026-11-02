package com.thalzeronlabs.billing

import android.app.Activity
import kotlinx.coroutines.CancellationException
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
    /** Subscription products, by product id. */
    val products: Map<String, SubscriptionProduct> = emptyMap(),
    /**
     * Products that unlock features right now: subscriptions whose [SubscriptionStatus.grantsAccess] is true
     * (active, canceled but not expired, in grace period) and lifetime one-time products.
     */
    val activeProductIds: Set<String> = emptySet(),
    /** Products waiting for payment (e.g. a boleto or cash payment not settled yet). Do not unlock these. */
    val pendingProductIds: Set<String> = emptySet(),
    val lastError: BillingStatus? = null,
    /** One-time products (lifetime and consumable), by product id. */
    val oneTimeProducts: Map<String, OneTimeProduct> = emptyMap(),
    /**
     * The status of every subscription the device or your [SubscriptionStatusSource] reports, by product id,
     * including the ones that do not grant access (on hold, paused, expired), so the UI can say why.
     */
    val subscriptions: Map<String, SubscriptionInfo> = emptyMap(),
    /**
     * Paid consumables that were not consumed yet. Grant each one (e.g. add coins), then call [BillingKit.consume]
     * so the user can buy it again. Until then Play answers `ITEM_ALREADY_OWNED` to a new purchase.
     */
    val unconsumedPurchases: List<OwnedPurchase> = emptyList(),
) {
    enum class Connection { DISCONNECTED, CONNECTING, READY, UNAVAILABLE }

    fun isActive(productId: String) = productId in activeProductIds

    /** True when at least one subscription grants access right now. Lifetime products do not count. */
    val hasAnySubscription: Boolean get() = subscriptions.values.any { it.status.grantsAccess }

    /** The status of [productId], or null when the user never had that subscription (or it is long gone). */
    fun subscription(productId: String): SubscriptionInfo? = subscriptions[productId]
}

/**
 * Subscriptions on Google Play without the usual bugs:
 * - **reconnects** with backoff when the Play service drops;
 * - **acknowledges every paid purchase** wherever it shows up (purchase sheet *and* restore), so Play never refunds
 *   a subscription after 3 days because the acknowledgement only lived in one code path;
 * - **picks the right offer** (free trial when the user is eligible) instead of `offerDetails.first()`;
 * - **changes plans** by replacing the current subscription instead of stacking a second one;
 * - sells **one-time products**: lifetime ones (acknowledged, unlocked forever) and consumables (granted by you, then
 *   [consumed][consume] so they can be bought again);
 * - reports **subscription statuses** the apps forget (grace period, account hold, paused, canceled but still valid),
 *   from the device and, optionally, from your backend ([SubscriptionStatusSource]);
 * - exposes one `StateFlow<BillingState>` for the UI.
 *
 * ```kotlin
 * val billing = BillingKit(
 *     gateway = PlayBillingGateway(context),
 *     productIds = listOf("pro_monthly", "pro_yearly"),     // subscriptions
 *     scope = appScope,
 *     lifetimeProductIds = listOf("remove_ads"),
 *     consumableProductIds = listOf("coins_100"),
 * )
 * billing.start()
 * billing.state.collect { premium = it.hasAnySubscription }
 * billing.buy(activity, "pro_yearly")
 * ```
 *
 * @param productIds the subscription product ids.
 * @param lifetimeProductIds one-time products bought once and kept forever (non-consumables).
 * @param consumableProductIds one-time products that are used up and bought again.
 * @param statusSource your backend's view of the subscriptions; without it, only what the device reports is used.
 */
class BillingKit(
    private val gateway: BillingGateway,
    private val productIds: List<String>,
    private val scope: CoroutineScope,
    private val offerSelector: OfferSelector = OfferSelector.bestForUser(),
    private val retryDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
    private val lifetimeProductIds: List<String> = emptyList(),
    private val consumableProductIds: List<String> = emptyList(),
    private val statusSource: SubscriptionStatusSource? = null,
) {
    private val oneTimeProductIds = lifetimeProductIds + consumableProductIds

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
        if (oneTimeProductIds.isNotEmpty()) {
            val (oneTimeStatus, oneTime) = gateway.queryOneTimeProducts(oneTimeProductIds)
            if (oneTimeStatus == BillingStatus.OK) {
                _state.update { s -> s.copy(oneTimeProducts = oneTime.associateBy { it.productId }) }
            } else {
                reportError(oneTimeStatus)
            }
        }

        val (subscriptionStatus, subscriptions) = gateway.queryPurchases()
        val (oneTimeStatus, oneTime) = if (oneTimeProductIds.isEmpty()) {
            BillingStatus.OK to emptyList()
        } else {
            // One-time purchases of products this kit was not told about are left alone: it cannot know whether
            // they are consumable.
            gateway.queryOneTimePurchases().let { (status, list) -> status to list.filter { isOneTime(it) } }
        }
        if (subscriptionStatus != BillingStatus.OK) reportError(subscriptionStatus)
        if (oneTimeStatus != BillingStatus.OK) reportError(oneTimeStatus)
        if (subscriptionStatus == BillingStatus.OK || oneTimeStatus == BillingStatus.OK) {
            // A failed query keeps what was known for that kind of product instead of wiping it.
            val keptSubscriptions = if (subscriptionStatus == BillingStatus.OK) subscriptions else currentPurchases.filterNot { isOneTime(it) }
            val keptOneTime = if (oneTimeStatus == BillingStatus.OK) oneTime else currentPurchases.filter { isOneTime(it) }
            applyPurchases(keptSubscriptions + keptOneTime, replaceAll = true)
        }
        syncServerStatuses()
    }

    /**
     * Opens the Play purchase sheet for [productId]. When the user already has another subscription from
     * [productIds], this becomes a plan change (see [changePlan]) so they never pay for two plans at once.
     */
    fun buy(activity: Activity, productId: String, basePlanId: String? = null): BillingStatus {
        if (productId in oneTimeProductIds) return buyOneTime(activity, productId)
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
        val owned = currentPurchases.firstOrNull { it.grantsAccess && toProductId !in it.productIds && !isOneTime(it) }
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

    /** The one-time offer that [buy] would sell for this product (the cheapest one the user is eligible for). */
    fun oneTimeOfferFor(productId: String): OneTimeOffer? = _state.value.oneTimeProducts[productId]?.bestOffer()

    /**
     * Consumes a paid consumable once you have granted it (e.g. added the coins), so the user can buy it again.
     * Consuming also acknowledges the purchase. Do it within 3 days, or Play refunds it.
     *
     * Returns [BillingStatus.DEVELOPER_ERROR] without calling Play when [purchaseToken] belongs to a subscription or a
     * lifetime product: consuming those would take away something the user paid to keep.
     */
    suspend fun consume(purchaseToken: String): BillingStatus {
        val purchase = currentPurchases.firstOrNull { it.purchaseToken == purchaseToken }
        if (purchase != null && !isConsumable(purchase)) return BillingStatus.DEVELOPER_ERROR.also(::reportError)
        val status = gateway.consume(purchaseToken)
        if (status == BillingStatus.OK) {
            currentPurchases = currentPurchases.filter { it.purchaseToken != purchaseToken }
            publish()
        } else {
            reportError(status)
        }
        return status
    }

    /**
     * Shows Google Play's own message for payment problems (grace period and account hold) when this user has one,
     * so they can fix their payment method without leaving the app. Call it when the app opens. When the user fixes
     * it, the kit refreshes the state.
     * See [in-app messaging](https://developer.android.com/google/play/billing/subscriptions#in-app-messaging).
     */
    fun showInAppMessages(activity: Activity): BillingStatus =
        gateway.showInAppMessages(activity) { scope.launch { refresh() } }

    // --- internals ---

    private var currentPurchases: List<OwnedPurchase> = emptyList()

    /** The last statuses [statusSource] returned. */
    private var serverStatuses: List<SubscriptionInfo> = emptyList()

    private fun isOneTime(purchase: OwnedPurchase) = purchase.productIds.any { it in oneTimeProductIds }
    private fun isConsumable(purchase: OwnedPurchase) = purchase.productIds.any { it in consumableProductIds }

    private fun buyOneTime(activity: Activity, productId: String): BillingStatus {
        val state = _state.value
        val alreadyOwned = (productId in lifetimeProductIds && state.isActive(productId)) ||
            state.unconsumedPurchases.any { productId in it.productIds }
        if (alreadyOwned) return BillingStatus.ITEM_ALREADY_OWNED
        val offer = oneTimeOfferFor(productId) ?: return BillingStatus.ITEM_UNAVAILABLE.also(::reportError)
        val status = gateway.launchPurchase(activity, productId, offer.offerToken, oldPurchaseToken = null, replacement = null)
        if (status != BillingStatus.OK) reportError(status)
        return status
    }

    /** Token of the subscription a plan change in flight is replacing; it is retired when the new purchase lands. */
    @Volatile
    private var replacedToken: String? = null

    private fun launch(activity: Activity, productId: String, basePlanId: String?, oldToken: String?, mode: PlanChange?): BillingStatus {
        val offer = offerFor(productId, basePlanId) ?: return BillingStatus.ITEM_UNAVAILABLE.also(::reportError)
        val status = gateway.launchPurchase(activity, productId, offer.offerToken, oldToken, mode)
        if (status == BillingStatus.OK && oldToken != null) replacedToken = oldToken
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
            BillingStatus.OK -> {
                val replaced = replacedToken
                if (replaced != null && update.purchases.isNotEmpty()) {
                    // A plan change is a new purchase: retire the one it replaces, then let queryPurchasesAsync
                    // (the source of truth, which also knows about deferred replacements) settle the final state.
                    replacedToken = null
                    currentPurchases = currentPurchases.filter { it.purchaseToken != replaced }
                    applyPurchases(update.purchases, replaceAll = false)
                    refresh()
                } else {
                    applyPurchases(update.purchases, replaceAll = false)
                    syncServerStatuses()
                }
            }
            BillingStatus.ITEM_ALREADY_OWNED -> refresh() // owned on another device, or a consumable not consumed: restore
            BillingStatus.USER_CANCELED -> Unit
            else -> reportError(update.status)
        }
    }

    private suspend fun applyPurchases(purchases: List<OwnedPurchase>, replaceAll: Boolean) {
        // Consumables are acknowledged by consume(), after the app has granted them.
        val toAcknowledge = purchases.filter { it.needsAcknowledgement && !isConsumable(it) }
        toAcknowledge.forEach { purchase ->
            val status = gateway.acknowledge(purchase.purchaseToken)
            if (status != BillingStatus.OK) reportError(status)
        }
        val merged = if (replaceAll) {
            purchases
        } else {
            currentPurchases.filter { old -> purchases.none { it.purchaseToken == old.purchaseToken } } + purchases
        }
        currentPurchases = merged.map { if (it in toAcknowledge) it.copy(acknowledged = true) else it }
        publish()
    }

    /** Asks [statusSource] for the backend's view; on failure keeps the last answer. */
    private suspend fun syncServerStatuses() {
        val source = statusSource ?: return
        serverStatuses = try {
            source.fetch(currentPurchases)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        publish()
    }

    /** Recomputes the derived state from [currentPurchases] and [serverStatuses]. */
    private fun publish() {
        val purchases = currentPurchases
        val subscriptions = subscriptionStatuses(purchases.filterNot { isOneTime(it) }, serverStatuses)
        val lifetime = purchases.filter { it.grantsAccess && !isConsumable(it) && isOneTime(it) }.flatMap { it.productIds }
        _state.update { s ->
            s.copy(
                subscriptions = subscriptions,
                activeProductIds = subscriptions.values.filter { it.status.grantsAccess }.map { it.productId }.toSet() + lifetime,
                pendingProductIds = purchases.filter { it.state == OwnedPurchase.State.PENDING }.flatMap { it.productIds }.toSet(),
                unconsumedPurchases = purchases.filter { isConsumable(it) && it.state == OwnedPurchase.State.PURCHASED },
            )
        }
    }

    private fun reportError(status: BillingStatus) = _state.update { it.copy(lastError = status) }

    internal companion object {
        /** When one product has several statuses (an old expired token and a new active one), the best one wins. */
        private val PRIORITY = listOf(
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.GRACE_PERIOD,
            SubscriptionStatus.CANCELED,
            SubscriptionStatus.PENDING,
            SubscriptionStatus.ON_HOLD,
            SubscriptionStatus.SUSPENDED,
            SubscriptionStatus.PAUSED,
            SubscriptionStatus.EXPIRED,
        )

        /** What the device alone can tell about a subscription purchase. */
        fun deviceStatus(purchase: OwnedPurchase): SubscriptionStatus? = when {
            purchase.state == OwnedPurchase.State.PENDING -> SubscriptionStatus.PENDING
            purchase.state != OwnedPurchase.State.PURCHASED -> null
            purchase.suspended -> SubscriptionStatus.SUSPENDED
            purchase.autoRenewing -> SubscriptionStatus.ACTIVE
            else -> SubscriptionStatus.CANCELED
        }

        /** Merges device and backend statuses: the backend wins for tokens it knows and adds the ones the device lost. */
        fun subscriptionStatuses(devicePurchases: List<OwnedPurchase>, server: List<SubscriptionInfo>): Map<String, SubscriptionInfo> {
            val serverTokens = server.mapNotNull { it.purchaseToken }.toSet()
            val device = devicePurchases
                .filter { it.purchaseToken !in serverTokens }
                .flatMap { purchase ->
                    val status = deviceStatus(purchase) ?: return@flatMap emptyList()
                    purchase.productIds.map { SubscriptionInfo(it, status, purchase.purchaseToken) }
                }
            return (device + server)
                .groupBy { it.productId }
                .mapValues { (_, infos) -> infos.minBy { PRIORITY.indexOf(it.status) } }
        }

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
