package com.thalzeronlabs.billing.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thalzeronlabs.billing.PlayLinks
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus

/**
 * One plan of the paywall: title, Play's formatted price per period, the [FreeTrialBadge] when this user is eligible,
 * the intro price when there is one, and a buy button. An owned or pending plan shows that instead of the button.
 *
 * @param onBuy called with [PaywallPlan.productId]; pass it to `BillingKit.buy`.
 * @param buttonLabel defaults to [PaywallStrings.buyButton] ("Start 1 week free trial", "Subscribe", "Buy").
 */
@Composable
fun PlanCard(
    plan: PaywallPlan,
    onBuy: (productId: String) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    buttonLabel: String? = null,
) {
    val strings = LocalPaywallStrings.current
    Card(
        modifier = modifier.fillMaxWidth(),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                if (plan.freeTrialPeriod != null && !plan.owned) {
                    FreeTrialBadge(strings.duration(plan.freeTrialPeriod, plan.freeTrialCycles))
                }
            }
            Text(strings.pricePerPeriod(plan.price, plan.billingPeriod), style = MaterialTheme.typography.bodyLarge)
            if (plan.introPrice != null && plan.introBillingPeriod != null && !plan.owned) {
                Text(
                    strings.introOffer(
                        strings.pricePerPeriod(plan.introPrice, plan.introBillingPeriod),
                        strings.duration(plan.introBillingPeriod, plan.introCycles),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                plan.owned -> Text(strings.currentPlan, fontWeight = FontWeight.Medium)
                plan.pending -> Text(strings.paymentPending)
                else -> Button(onClick = { onBuy(plan.productId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel ?: strings.buyButton(plan))
                }
            }
        }
    }
}

/** The "1 week free" pill. Only show it for offers Play returned for this user: Play only returns eligible offers. */
@Composable
fun FreeTrialBadge(duration: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            LocalPaywallStrings.current.freeTrialBadge(duration),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** "Restore purchases": call `BillingKit.refresh()` from [onClick]. */
@Composable
fun RestorePurchasesButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(LocalPaywallStrings.current.restorePurchases)
    }
}

/**
 * The links under a subscription paywall: your terms of service, your privacy policy (optional) and Google Play's
 * page to manage or cancel the subscription.
 *
 * @param manageSubscriptionUrl defaults to the Play subscriptions center; use
 *   [PlayLinks.manageSubscription] to open one subscription. Pass null to hide it (e.g. a one-time-only paywall).
 */
@Composable
fun PaywallLinks(
    termsUrl: String,
    modifier: Modifier = Modifier,
    privacyUrl: String? = null,
    manageSubscriptionUrl: String? = PlayLinks.SUBSCRIPTIONS_CENTER,
) {
    val strings = LocalPaywallStrings.current
    val uriHandler = LocalUriHandler.current
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        LinkButton(strings.termsOfService) { uriHandler.openUri(termsUrl) }
        if (privacyUrl != null) LinkButton(strings.privacyPolicy) { uriHandler.openUri(privacyUrl) }
        if (manageSubscriptionUrl != null) LinkButton(strings.manageSubscription) { uriHandler.openUri(manageSubscriptionUrl) }
    }
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { role = Role.Button }) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Tells the user about a subscription that needs them: grace period, on hold, paused, suspended, canceled but still
 * valid, or a pending payment; with a button to the right page of Google Play. Shows nothing for active and expired
 * subscriptions.
 *
 * @param manageUrl defaults to Play's page for this subscription in this app.
 */
@Composable
fun SubscriptionStatusBanner(
    info: SubscriptionInfo,
    modifier: Modifier = Modifier,
    manageUrl: String = PlayLinks.manageSubscription(LocalContext.current.packageName, info.productId),
) {
    val strings = LocalPaywallStrings.current
    val message = strings.statusMessage(info) ?: return
    val action = strings.statusAction(info.status)
    val uriHandler = LocalUriHandler.current
    val blocking = !info.status.grantsAccess && info.status != SubscriptionStatus.PENDING
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (blocking) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                TextButton(onClick = { uriHandler.openUri(manageUrl) }, modifier = Modifier.align(Alignment.End)) {
                    Text(action)
                }
            }
        }
    }
}
