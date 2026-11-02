package com.thalzeronlabs.billing.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus
import com.thalzeronlabs.billing.compose.PaywallLinks
import com.thalzeronlabs.billing.compose.PaywallPlan
import com.thalzeronlabs.billing.compose.PlanCard
import com.thalzeronlabs.billing.compose.RestorePurchasesButton
import com.thalzeronlabs.billing.compose.SubscriptionStatusBanner

class MainActivity : ComponentActivity() {

    private val viewModel: PaywallViewModel by viewModels { factory }

    private val factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            // The only line to change to sell for real (needs the app on a Play Console track and a tester account):
            //   val gateway: BillingGateway = PlayBillingGateway(applicationContext)
            val gateway: BillingGateway = FakeBillingGateway()
            PaywallViewModel(gateway)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val activity = LocalActivity.current
                    PaywallScreen(
                        state = state,
                        onBuy = { productId -> activity?.let { viewModel.buy(it, productId) } },
                        onRestore = viewModel::restore,
                        onSimulate = viewModel::simulate,
                        onReset = viewModel::resetDemo,
                    )
                }
            }
        }
    }
}

/** Placeholders: point these at your own pages. */
private const val TERMS_URL = "https://example.com/terms"
private const val PRIVACY_URL = "https://example.com/privacy"

@Composable
fun PaywallScreen(
    state: PaywallUiState,
    onBuy: (String) -> Unit,
    onRestore: () -> Unit,
    onSimulate: (SubscriptionStatus?) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Go Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (state.premium) "Pro is unlocked. Thanks for subscribing!" else "Unlock every feature. Cancel anytime.",
            style = MaterialTheme.typography.bodyLarge,
        )

        state.status?.let { SubscriptionStatusBanner(it) }

        when {
            state.unavailable -> Text("Google Play billing is not available on this device.")
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else -> {
                state.plans.forEach { plan ->
                    val switching = state.premium && !plan.owned
                    PlanCard(
                        plan = if (switching) plan.copy(freeTrialPeriod = null) else plan, // trials are for new subscribers
                        onBuy = onBuy,
                        highlighted = plan.hasFreeTrial && !state.premium,
                        buttonLabel = if (switching) "Switch to this plan" else null,
                    )
                }
                HorizontalDivider()
                Text("Extras", style = MaterialTheme.typography.titleMedium)
                state.extras.forEach { PlanCard(it, onBuy = onBuy) }
                Text(
                    "Ads: ${if (state.adsRemoved) "removed" else "shown"} · Coins: ${state.coins}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RestorePurchasesButton(onClick = onRestore)
            TextButton(onClick = onReset) { Text("Reset demo") }
        }
        PaywallLinks(termsUrl = TERMS_URL, privacyUrl = PRIVACY_URL, modifier = Modifier.fillMaxWidth())

        if (state.premium || state.status != null) {
            Text("Simulate what Play reports", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SIMULATIONS.forEach { (label, status) -> AssistChip(onClick = { onSimulate(status) }, label = { Text(label) }) }
            }
        }
        Text(
            "Demo store: no real payment happens. Purchases live in memory until the app is killed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val SIMULATIONS = listOf(
    "Grace period" to SubscriptionStatus.GRACE_PERIOD,
    "On hold" to SubscriptionStatus.ON_HOLD,
    "Paused" to SubscriptionStatus.PAUSED,
    "Canceled" to SubscriptionStatus.CANCELED,
    "Back to active" to null,
)

// --- Previews: the billing-compose components with Play-like data, no device needed ---

private val previewPlans = listOf(
    PaywallPlan("pro_monthly", "Pro monthly", "$4.99", "P1M", freeTrialPeriod = "P1W"),
    PaywallPlan("pro_yearly", "Pro yearly", "$39.99", "P1Y"),
)
private val previewExtras = listOf(
    PaywallPlan("remove_ads", "Remove ads forever", "$2.99", oneTime = true),
    PaywallPlan("coins_100", "100 coins", "$0.99", oneTime = true),
)

@Preview(name = "Paywall", showBackground = true, heightDp = 1200)
@Composable
private fun PaywallPreview() {
    MaterialTheme {
        PaywallScreen(
            state = PaywallUiState(loading = false, plans = previewPlans, extras = previewExtras),
            onBuy = {}, onRestore = {}, onSimulate = {}, onReset = {},
        )
    }
}

@Preview(name = "Plan cards", showBackground = true)
@Composable
private fun PlanCardsPreview() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlanCard(previewPlans[0], onBuy = {}, highlighted = true)
            PlanCard(PaywallPlan("pro_monthly", "Pro monthly", "$4.99", "P1M", introPrice = "$1.99", introBillingPeriod = "P1M", introCycles = 3), onBuy = {})
            PlanCard(previewPlans[1].copy(owned = true), onBuy = {})
            PlanCard(previewExtras[0].copy(pending = true), onBuy = {})
        }
    }
}

private class StatusProvider : PreviewParameterProvider<SubscriptionStatus> {
    override val values = sequenceOf(
        SubscriptionStatus.GRACE_PERIOD,
        SubscriptionStatus.ON_HOLD,
        SubscriptionStatus.PAUSED,
        SubscriptionStatus.SUSPENDED,
        SubscriptionStatus.CANCELED,
        SubscriptionStatus.PENDING,
    )
}

@Preview(name = "Status banners", showBackground = true)
@Composable
private fun StatusBannerPreview(@PreviewParameter(StatusProvider::class) status: SubscriptionStatus) {
    MaterialTheme {
        Column(Modifier.padding(16.dp)) {
            SubscriptionStatusBanner(
                SubscriptionInfo("pro_monthly", status, expiryTimeMillis = 1_798_761_600_000),
                manageUrl = "https://play.google.com/store/account/subscriptions",
            )
        }
    }
}
