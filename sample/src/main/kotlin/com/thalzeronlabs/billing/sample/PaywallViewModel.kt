package com.thalzeronlabs.billing.sample

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.BillingKit
import com.thalzeronlabs.billing.BillingState
import com.thalzeronlabs.billing.BillingStatus
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus
import com.thalzeronlabs.billing.SubscriptionStatusSource
import com.thalzeronlabs.billing.compose.PaywallPlan
import com.thalzeronlabs.billing.compose.paywallPlans
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the paywall screen renders. */
data class PaywallUiState(
    val loading: Boolean = true,
    val unavailable: Boolean = false,
    /** The subscription plans. */
    val plans: List<PaywallPlan> = emptyList(),
    /** One-time products: the lifetime "remove ads" and the consumable coins. */
    val extras: List<PaywallPlan> = emptyList(),
    val premium: Boolean = false,
    val adsRemoved: Boolean = false,
    val coins: Int = 0,
    /** A subscription that needs the user's attention (grace period, on hold, paused, canceled…). */
    val status: SubscriptionInfo? = null,
    val message: String? = null,
)

class PaywallViewModel(
    private val gateway: BillingGateway,
    statusSource: SubscriptionStatusSource? = gateway as? SubscriptionStatusSource,
) : ViewModel() {

    private val billing = BillingKit(
        gateway = gateway,
        productIds = SUBSCRIPTIONS,
        scope = viewModelScope,
        lifetimeProductIds = listOf(FakeBillingGateway.REMOVE_ADS),
        consumableProductIds = listOf(FakeBillingGateway.COINS),
        statusSource = statusSource,
    )
    private val message = MutableStateFlow<String?>(null)

    /** In a real app the balance and the granted tokens live in your database (or better, on your server). */
    private val coins = MutableStateFlow(0)
    private val grantedTokens = mutableSetOf<String>()

    val state: StateFlow<PaywallUiState> = combine(billing.state, message, coins) { billingState, msg, balance ->
        billingState.toUi(msg, balance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    init {
        billing.start()
        // Consumables: grant first, then consume, so a crash in between never loses what the user paid for.
        viewModelScope.launch {
            billing.state.collect { s ->
                s.unconsumedPurchases.filter { grantedTokens.add(it.purchaseToken) }.forEach { purchase ->
                    coins.value += FakeBillingGateway.COINS_PER_PACK * purchase.quantity
                    billing.consume(purchase.purchaseToken)
                }
            }
        }
    }

    fun buy(activity: Activity, productId: String) {
        val isPlanChange = productId in SUBSCRIPTIONS && billing.state.value.hasAnySubscription
        val status = billing.buy(activity, productId)
        message.value = when {
            status == BillingStatus.ITEM_ALREADY_OWNED -> "You already own this."
            status != BillingStatus.OK -> "Could not start the purchase: $status"
            isPlanChange -> "Plan change sent. Upgrades apply now; downgrades wait for the renewal date."
            else -> null
        }
    }

    fun restore() {
        viewModelScope.launch {
            message.value = "Restoring purchases…"
            billing.refresh()
            val s = billing.state.value
            message.value = if (s.hasAnySubscription || s.activeProductIds.isNotEmpty()) "Purchases restored." else "Nothing to restore."
        }
    }

    /** Demo only: pretends Play moved the subscription to [status] (null = back to normal). */
    fun simulate(status: SubscriptionStatus?) {
        val fake = gateway as? FakeBillingGateway ?: return
        fake.simulate(status)
        viewModelScope.launch {
            billing.refresh()
            message.value = null
        }
    }

    /** Demo only: wipes the fake store so you can buy again. */
    fun resetDemo() {
        (gateway as? FakeBillingGateway)?.clearPurchases() ?: return
        coins.value = 0
        restore()
    }

    override fun onCleared() = billing.stop()

    private fun BillingState.toUi(msg: String?, balance: Int) = PaywallUiState(
        loading = connection == BillingState.Connection.CONNECTING || (connection == BillingState.Connection.READY && products.isEmpty()),
        unavailable = connection == BillingState.Connection.UNAVAILABLE,
        plans = billing.paywallPlans(SUBSCRIPTIONS, this),
        extras = billing.paywallPlans(listOf(FakeBillingGateway.REMOVE_ADS, FakeBillingGateway.COINS), this),
        premium = hasAnySubscription,
        adsRemoved = isActive(FakeBillingGateway.REMOVE_ADS),
        coins = balance,
        status = subscriptions.values.firstOrNull { it.status != SubscriptionStatus.ACTIVE && it.status != SubscriptionStatus.EXPIRED },
        message = msg ?: lastError?.let { "Billing error: $it" },
    )

    private companion object {
        val SUBSCRIPTIONS = listOf(FakeBillingGateway.MONTHLY, FakeBillingGateway.YEARLY)
    }
}
