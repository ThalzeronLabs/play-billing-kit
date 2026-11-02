package com.thalzeronlabs.billing.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.thalzeronlabs.billing.PlayLinks
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composables rendered on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaywallComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun `a plan card shows Play's price, the trial badge and buys the product`() {
        var bought: String? = null
        compose.setContent { PlanCard(PaywallPlan.from(MONTHLY, MONTHLY_TRIAL), onBuy = { bought = it }) }

        compose.onNodeWithText("R$ 9,90 / month").assertExists()
        compose.onNodeWithText("1 week free").assertExists()
        compose.onNodeWithText("Start 1 week free trial").performClick()
        assertEquals("pro_monthly", bought)
    }

    @Test
    fun `an intro price is spelled out`() {
        compose.setContent { PlanCard(PaywallPlan.from(MONTHLY, MONTHLY_INTRO), onBuy = {}) }
        compose.onNodeWithText("R$ 4,90 / month for 3 months").assertExists()
    }

    @Test
    fun `the owned plan has no buy button and no trial badge`() {
        val plan = PaywallPlan.from(MONTHLY, MONTHLY_TRIAL).copy(owned = true)
        compose.setContent { PlanCard(plan, onBuy = {}) }
        compose.onNodeWithText("Your current plan").assertExists()
        compose.onAllNodesWithText("Start 1 week free trial").assertCountEquals(0)
        compose.onAllNodesWithText("1 week free").assertCountEquals(0)
    }

    @Test
    fun `a pending plan says the payment is pending`() {
        val plan = PaywallPlan.from(REMOVE_ADS, REMOVE_ADS.offers.single()).copy(pending = true)
        compose.setContent { PlanCard(plan, onBuy = {}) }
        compose.onNodeWithText("Payment pending: unlocks once it clears").assertExists()
        compose.onAllNodesWithText("Buy").assertCountEquals(0)
    }

    @Test
    fun `restore purchases calls back`() {
        var restored = false
        compose.setContent { RestorePurchasesButton(onClick = { restored = true }) }
        compose.onNodeWithText("Restore purchases").performClick()
        assertTrue(restored)
    }

    @Test
    fun `the links open the terms, the privacy policy and Play's subscription page`() {
        val manage = PlayLinks.manageSubscription("com.example.app", "pro_monthly")
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PaywallLinks(termsUrl = "https://example.com/terms", privacyUrl = "https://example.com/privacy", manageSubscriptionUrl = manage)
            }
        }
        compose.onNodeWithText("Terms of Service").performClick()
        compose.onNodeWithText("Privacy Policy").performClick()
        compose.onNodeWithText("Manage subscription").performClick()
        assertEquals(listOf("https://example.com/terms", "https://example.com/privacy", manage), opened)
    }

    @Test
    fun `an account hold banner sends the user to fix the payment in Play`() {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                SubscriptionStatusBanner(SubscriptionInfo("pro_monthly", SubscriptionStatus.ON_HOLD))
            }
        }
        compose.onNodeWithText("on hold", substring = true).assertExists()
        compose.onNodeWithText("Fix payment").performClick()
        assertTrue(opened.single(), opened.single().startsWith("${PlayLinks.SUBSCRIPTIONS_CENTER}?sku=pro_monthly&package="))
    }

    @Test
    fun `an active subscription shows no banner`() {
        compose.setContent { SubscriptionStatusBanner(SubscriptionInfo("pro_monthly", SubscriptionStatus.ACTIVE)) }
        compose.onAllNodesWithText("Fix payment").assertCountEquals(0)
        compose.onAllNodesWithText("Resubscribe").assertCountEquals(0)
    }

    @Test
    fun `texts can be translated`() {
        val portuguese = object : PaywallStrings() {
            override val restorePurchases = "Restaurar compras"
        }
        compose.setContent {
            CompositionLocalProvider(LocalPaywallStrings provides portuguese) { RestorePurchasesButton(onClick = {}) }
        }
        compose.onNodeWithText("Restaurar compras").assertExists()
    }
}
