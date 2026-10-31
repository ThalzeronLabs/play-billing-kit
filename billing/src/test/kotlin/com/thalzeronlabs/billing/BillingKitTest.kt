package com.thalzeronlabs.billing

import android.app.Activity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingKitTest {

    private val ids = listOf("pro_monthly", "pro_yearly", "elite_monthly")
    private val activity = Activity()

    /** Background work (the kit lives in backgroundScope) only runs when virtual time is pushed explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.kit(gateway: FakeGateway) = BillingKit(gateway, ids, backgroundScope).also {
        it.start()
        settle()
    }

    @Test
    fun `start connects and loads products and purchases`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly")))
        val state = kit(gateway).state.value
        assertEquals(BillingState.Connection.READY, state.connection)
        assertEquals(3, state.products.size)
        assertTrue(state.isActive("pro_monthly"))
        assertTrue(state.hasAnySubscription)
    }

    @Test
    fun `restore acknowledges paid purchases that nobody acknowledged`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "lost", acknowledged = false)))
        kit(gateway)
        assertEquals(listOf("lost"), gateway.acknowledged)
    }

    @Test
    fun `pending payments do not unlock anything`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", pending = true)))
        val state = kit(gateway).state.value
        assertFalse(state.isActive("pro_monthly"))
        assertEquals(setOf("pro_monthly"), state.pendingProductIds)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `transient connection failures are retried with backoff`() = runTest {
        val gateway = FakeGateway(
            connectResults = listOf(BillingStatus.SERVICE_UNAVAILABLE, BillingStatus.NETWORK_ERROR, BillingStatus.OK)
        )
        val state = kit(gateway).state.value
        assertEquals(3, gateway.connectCalls)
        assertEquals(BillingState.Connection.READY, state.connection)
    }

    @Test
    fun `no Play billing on the device is reported once, without retrying`() = runTest {
        val gateway = FakeGateway(connectResults = listOf(BillingStatus.BILLING_UNAVAILABLE))
        val state = kit(gateway).state.value
        assertEquals(1, gateway.connectCalls)
        assertEquals(BillingState.Connection.UNAVAILABLE, state.connection)
        assertEquals(BillingStatus.BILLING_UNAVAILABLE, state.lastError)
    }

    @Test
    fun `buy sells the free trial when the user is eligible`() = runTest {
        val gateway = FakeGateway()
        kit(gateway).buy(activity, "pro_monthly")
        assertEquals(FakeGateway.Launch("pro_monthly", "token-monthly-trial7", null, null), gateway.launches.single())
    }

    @Test
    fun `buying a second plan replaces the first instead of stacking`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "old")))
        kit(gateway).buy(activity, "elite_monthly")
        val launch = gateway.launches.single()
        assertEquals("old", launch.oldToken)
        assertEquals(PlanChange.WITH_TIME_PRORATION, launch.mode) // R$ 30 > R$ 10: upgrade, switch now
    }

    @Test
    fun `a downgrade waits for the next renewal`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("elite_monthly", token = "old")))
        kit(gateway).changePlan(activity, "pro_yearly")
        assertEquals(PlanChange.DEFERRED, gateway.launches.single().mode) // R$ 100/yr ≈ R$ 8.33/mo < R$ 30
    }

    @Test
    fun `an unknown product is not launched`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        assertEquals(BillingStatus.ITEM_UNAVAILABLE, k.buy(activity, "nope"))
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun `a purchase from the sheet is acknowledged and unlocks at once`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(owned("pro_yearly", token = "new", acknowledged = false))))
        settle()
        assertEquals(listOf("new"), gateway.acknowledged)
        assertTrue(k.state.value.isActive("pro_yearly"))
    }

    @Test
    fun `item already owned triggers a restore`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.purchases = listOf(owned("pro_monthly"))
        gateway.updates.emit(PurchaseUpdate(BillingStatus.ITEM_ALREADY_OWNED, emptyList()))
        settle()
        assertTrue(k.state.value.isActive("pro_monthly"))
    }

    @Test
    fun `user cancelling the sheet is not an error`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.updates.emit(PurchaseUpdate(BillingStatus.USER_CANCELED, emptyList()))
        settle()
        assertNull(k.state.value.lastError)
    }

    @Test
    fun `stop disconnects`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        k.stop()
        assertTrue(gateway.disconnected)
        assertEquals(BillingState.Connection.DISCONNECTED, k.state.value.connection)
    }

    @Test
    fun `prices are compared per month`() {
        assertEquals(10_000_000, BillingKit.monthlyMicros(phase(120_000_000, "P1Y")))
        assertEquals(10_000_000, BillingKit.monthlyMicros(phase(30_000_000, "P3M")))
        assertEquals(10_000_000, BillingKit.monthlyMicros(phase(10_000_000, "P1M")))
    }
}
