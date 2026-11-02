package com.thalzeronlabs.billing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A purchase that is not acknowledged within 3 days is refunded by Play
 * (https://developer.android.com/google/play/billing/integrate#notifying-google), so a failed acknowledgement must
 * never be recorded as done, and must be tried again without waiting for the app to call refresh().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcknowledgementRetryTest {

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.kit(gateway: FakeGateway) =
        BillingKit(gateway, listOf("pro_monthly", "pro_yearly"), backgroundScope, lifetimeProductIds = listOf("remove_ads")).also {
            it.start()
            settle()
        }

    @Test
    fun `a failed acknowledgement from the purchase sheet is retried until it succeeds`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.acknowledgeResults += listOf(BillingStatus.SERVICE_UNAVAILABLE, BillingStatus.NETWORK_ERROR)

        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(owned("pro_yearly", token = "new", acknowledged = false))))
        settle()

        assertEquals(listOf("new", "new", "new"), gateway.acknowledgeAttempts)
        assertEquals(listOf("new"), gateway.acknowledged)
        assertNull(k.state.value.lastError) // recovered
    }

    @Test
    fun `a failed acknowledgement on restore is retried without another refresh`() = runTest {
        val gateway = FakeGateway(oneTimePurchases = listOf(owned("remove_ads", token = "ads", acknowledged = false)))
        gateway.acknowledgeResults += BillingStatus.SERVICE_DISCONNECTED
        kit(gateway)
        assertEquals(listOf("ads", "ads"), gateway.acknowledgeAttempts)
        assertEquals(listOf("ads"), gateway.acknowledged)
    }

    @Test
    fun `a purchase whose acknowledgement failed is acknowledged by the next restore`() = runTest {
        val gateway = FakeGateway()
        val k = BillingKit(gateway, listOf("pro_yearly"), backgroundScope, retryDelaysMillis = emptyList())
        k.start()
        settle()
        gateway.acknowledgeResults += BillingStatus.ERROR
        gateway.purchases = listOf(owned("pro_yearly", token = "new", acknowledged = false))
        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, gateway.purchases))
        settle()
        assertEquals(BillingStatus.ERROR, k.state.value.lastError)

        k.refresh()
        assertEquals(listOf("new", "new"), gateway.acknowledgeAttempts)
        assertEquals(listOf("new"), gateway.acknowledged)
    }

    @Test
    fun `a permanent failure is not retried in a loop`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.acknowledgeResults += BillingStatus.ITEM_NOT_OWNED // e.g. already refunded: retrying cannot help
        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(owned("pro_yearly", token = "gone", acknowledged = false))))
        settle()
        assertEquals(listOf("gone"), gateway.acknowledgeAttempts)
        assertEquals(BillingStatus.ITEM_NOT_OWNED, k.state.value.lastError)
    }

    @Test
    fun `retries give up after the configured backoff and leave the rest to the next restore`() = runTest {
        val gateway = FakeGateway()
        val k = BillingKit(gateway, listOf("pro_yearly"), backgroundScope, retryDelaysMillis = listOf(1_000, 2_000))
        k.start()
        settle()
        repeat(10) { gateway.acknowledgeResults += BillingStatus.SERVICE_UNAVAILABLE }
        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(owned("pro_yearly", token = "new", acknowledged = false))))
        settle()
        assertEquals(3, gateway.acknowledgeAttempts.size) // the first try + one per backoff step
        assertEquals(BillingStatus.SERVICE_UNAVAILABLE, k.state.value.lastError)
    }
}
