package com.thalzeronlabs.billing.sample

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.BillingKit
import com.thalzeronlabs.billing.BillingState
import com.thalzeronlabs.billing.BillingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One plan card on the paywall. */
data class PlanUi(
    val productId: String,
    val title: String,
    /** e.g. "$4.99 / month". */
    val price: String,
    /** Length of the free trial, e.g. "1 week"; only set when this user is eligible for it. */
    val trial: String?,
    val owned: Boolean,
    val pending: Boolean,
)

/** Everything the paywall screen renders. */
data class PaywallUiState(
    val loading: Boolean = true,
    val unavailable: Boolean = false,
    val plans: List<PlanUi> = emptyList(),
    val premium: Boolean = false,
    val message: String? = null,
)

class PaywallViewModel(private val gateway: BillingGateway) : ViewModel() {

    private val billing = BillingKit(gateway, listOf(FakeBillingGateway.MONTHLY, FakeBillingGateway.YEARLY), viewModelScope)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<PaywallUiState> = combine(billing.state, message) { billingState, msg -> billingState.toUi(msg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    init {
        billing.start()
    }

    fun buy(activity: Activity, productId: String) {
        val isPlanChange = billing.state.value.hasAnySubscription
        val status = billing.buy(activity, productId)
        message.value = when {
            status != BillingStatus.OK -> "Could not start the purchase: $status"
            isPlanChange -> "Plan change sent. Upgrades apply now; downgrades wait for the renewal date."
            else -> null
        }
    }

    fun restore() {
        viewModelScope.launch {
            message.value = "Restoring purchases…"
            billing.refresh()
            message.value = if (billing.state.value.hasAnySubscription) "Purchases restored." else "Nothing to restore."
        }
    }

    /** Demo only: wipes the fake store so you can buy again. */
    fun resetDemo() {
        (gateway as? FakeBillingGateway)?.clearPurchases() ?: return
        restore()
    }

    override fun onCleared() = billing.stop()

    private fun BillingState.toUi(msg: String?) = PaywallUiState(
        loading = connection == BillingState.Connection.CONNECTING || (connection == BillingState.Connection.READY && products.isEmpty()),
        unavailable = connection == BillingState.Connection.UNAVAILABLE,
        premium = hasAnySubscription,
        message = msg ?: lastError?.let { "Billing error: $it" },
        plans = listOf(FakeBillingGateway.MONTHLY, FakeBillingGateway.YEARLY).mapNotNull { id ->
            val product = products[id] ?: return@mapNotNull null
            val offer = billing.offerFor(id) ?: return@mapNotNull null
            val recurring = offer.recurringPhase
            PlanUi(
                productId = id,
                title = product.title,
                price = "${recurring.formattedPrice} / ${periodLabel(recurring.billingPeriod, plural = false)}",
                trial = offer.phases.firstOrNull { it.priceMicros == 0L }?.let { periodLabel(it.billingPeriod) },
                owned = isActive(id),
                pending = id in pendingProductIds,
            )
        },
    )
}

/** "P1M" -> "month", "P1W" -> "1 week", "P3M" -> "3 months". Covers the ISO-8601 periods Play uses. */
internal fun periodLabel(iso: String, plural: Boolean = true): String {
    val match = Regex("""P(\d+)([DWMY])""").matchEntire(iso) ?: return iso
    val count = match.groupValues[1].toInt()
    val unit = when (match.groupValues[2]) {
        "D" -> "day"
        "W" -> "week"
        "M" -> "month"
        else -> "year"
    }
    return when {
        count == 1 && !plural -> unit
        count == 1 -> "1 $unit"
        else -> "$count ${unit}s"
    }
}
