package com.thalzeronlabs.billing.compose

import android.app.Activity
import com.thalzeronlabs.billing.BillingGateway
import com.thalzeronlabs.billing.BillingKit
import com.thalzeronlabs.billing.BillingState
import com.thalzeronlabs.billing.BillingStatus
import com.thalzeronlabs.billing.OneTimeProduct
import com.thalzeronlabs.billing.OwnedPurchase
import com.thalzeronlabs.billing.PlanChange
import com.thalzeronlabs.billing.PurchaseUpdate
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionProduct
import com.thalzeronlabs.billing.SubscriptionStatus
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallPlanTest {

    @Test
    fun `a trial offer keeps Play's recurring price and the trial length`() {
        val plan = PaywallPlan.from(MONTHLY, MONTHLY_TRIAL)
        assertEquals("R$ 9,90", plan.price)
        assertEquals("P1M", plan.billingPeriod)
        assertEquals("P1W", plan.freeTrialPeriod)
        assertTrue(plan.hasFreeTrial)
        assertNull(plan.introPrice)
    }

    @Test
    fun `an intro price is shown with its length`() {
        val plan = PaywallPlan.from(MONTHLY, MONTHLY_INTRO)
        assertEquals("R$ 4,90", plan.introPrice)
        assertEquals("P1M", plan.introBillingPeriod)
        assertEquals(3, plan.introCycles)
        assertFalse(plan.hasFreeTrial)
    }

    @Test
    fun `the base plan has no badge`() {
        val plan = PaywallPlan.from(MONTHLY, MONTHLY_BASE)
        assertNull(plan.freeTrialPeriod)
        assertNull(plan.introPrice)
    }

    @Test
    fun `owned, pending and status come from the billing state`() {
        val state = BillingState(
            activeProductIds = setOf("pro_monthly"),
            subscriptions = mapOf("pro_monthly" to SubscriptionInfo("pro_monthly", SubscriptionStatus.CANCELED)),
            pendingProductIds = setOf("remove_ads"),
        )
        val sub = PaywallPlan.from(MONTHLY, MONTHLY_BASE, state)
        assertTrue(sub.owned)
        assertEquals(SubscriptionStatus.CANCELED, sub.status)
        val oneTime = PaywallPlan.from(REMOVE_ADS, REMOVE_ADS.offers.single(), state)
        assertTrue(oneTime.pending)
        assertTrue(oneTime.oneTime)
        assertNull(oneTime.billingPeriod)
        assertEquals("R$ 19,90", oneTime.price)
    }

    @Test
    fun `paywallPlans follows the requested order and the offer buy() would sell`() = runTest {
        val kit = BillingKit(StaticGateway(listOf(MONTHLY, YEARLY), listOf(REMOVE_ADS)), listOf("pro_monthly", "pro_yearly"), backgroundScope, lifetimeProductIds = listOf("remove_ads"))
        kit.refresh()
        val plans = kit.paywallPlans(listOf("pro_yearly", "remove_ads", "pro_monthly", "missing"))
        assertEquals(listOf("pro_yearly", "remove_ads", "pro_monthly"), plans.map { it.productId })
        assertEquals("P1W", plans.last().freeTrialPeriod) // the trial wins, like BillingKit.buy
    }

    private class StaticGateway(val subs: List<SubscriptionProduct>, val oneTime: List<OneTimeProduct>) : BillingGateway {
        override val purchaseUpdates = emptyFlow<PurchaseUpdate>()
        override suspend fun connect() = BillingStatus.OK
        override suspend fun queryProducts(productIds: List<String>) = BillingStatus.OK to subs.filter { it.productId in productIds }
        override suspend fun queryOneTimeProducts(productIds: List<String>) = BillingStatus.OK to oneTime.filter { it.productId in productIds }
        override suspend fun queryPurchases() = BillingStatus.OK to emptyList<OwnedPurchase>()
        override suspend fun acknowledge(purchaseToken: String) = BillingStatus.OK
        override fun launchPurchase(activity: Activity, productId: String, offerToken: String, oldPurchaseToken: String?, replacement: PlanChange?) = BillingStatus.OK
        override fun disconnect() = Unit
    }
}
