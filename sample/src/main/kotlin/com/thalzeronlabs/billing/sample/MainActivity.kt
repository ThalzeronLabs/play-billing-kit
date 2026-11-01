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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thalzeronlabs.billing.BillingGateway

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
                        onReset = viewModel::resetDemo,
                    )
                }
            }
        }
    }
}

@Composable
fun PaywallScreen(state: PaywallUiState, onBuy: (String) -> Unit, onRestore: () -> Unit, onReset: () -> Unit) {
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

        when {
            state.unavailable -> Text("Google Play billing is not available on this device.")
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else -> state.plans.forEach { plan -> PlanCard(plan, anotherPlanOwned = state.premium && !plan.owned, onBuy) }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestore) { Text("Restore purchases") }
            TextButton(onClick = onReset) { Text("Reset demo") }
        }
        Text(
            "Demo store: no real payment happens. Purchases live in memory until the app is killed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PlanCard(plan: PlanUi, anotherPlanOwned: Boolean, onBuy: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.title, style = MaterialTheme.typography.titleLarge)
                if (plan.trial != null && !anotherPlanOwned) AssistChip(onClick = {}, label = { Text("${plan.trial} free") })
            }
            Text(plan.price, style = MaterialTheme.typography.bodyLarge)
            when {
                plan.owned -> Text("Your current plan", fontWeight = FontWeight.Medium)
                plan.pending -> Text("Payment pending: unlocks once it clears")
                else -> Button(onClick = { onBuy(plan.productId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            anotherPlanOwned -> "Switch to this plan"
                            plan.trial != null -> "Start ${plan.trial} free trial"
                            else -> "Subscribe"
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PaywallPreview() {
    MaterialTheme {
        PaywallScreen(
            state = PaywallUiState(
                loading = false,
                plans = listOf(
                    PlanUi("pro_monthly", "Pro monthly", "$4.99 / month", "1 week", owned = false, pending = false),
                    PlanUi("pro_yearly", "Pro yearly", "$39.99 / year", null, owned = false, pending = false),
                ),
            ),
            onBuy = {}, onRestore = {}, onReset = {},
        )
    }
}
