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

/**
 * The subscription states Play reports and apps forget.
 * https://developer.android.com/google/play/billing/lifecycle/subscriptions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionStatusTest {

    private val activity = Activity()

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    /** A backend that answers with fixed statuses and remembers what the device sent it. */
    private class FakeBackend(var statuses: List<SubscriptionInfo> = emptyList(), var fail: Boolean = false) : SubscriptionStatusSource {
        val received = mutableListOf<List<OwnedPurchase>>()
        override suspend fun fetch(devicePurchases: List<OwnedPurchase>): List<SubscriptionInfo> {
            received += devicePurchases
            if (fail) error("backend down")
            return statuses
        }
    }

    private fun TestScope.kit(gateway: FakeGateway, backend: SubscriptionStatusSource? = null) =
        BillingKit(gateway, listOf("pro_monthly", "pro_yearly"), backgroundScope, statusSource = backend).also {
            it.start()
            settle()
        }

    @Test
    fun `a renewing subscription is active`() = runTest {
        val state = kit(FakeGateway(purchases = listOf(owned("pro_monthly")))).state.value
        assertEquals(SubscriptionStatus.ACTIVE, state.subscription("pro_monthly")?.status)
        assertTrue(state.hasAnySubscription)
    }

    @Test
    fun `canceled but not expired keeps access until the end of the period`() = runTest {
        // Play keeps returning it, with isAutoRenewing() == false.
        val state = kit(FakeGateway(purchases = listOf(owned("pro_monthly", autoRenewing = false)))).state.value
        assertEquals(SubscriptionStatus.CANCELED, state.subscription("pro_monthly")?.status)
        assertTrue(state.isActive("pro_monthly"))
        assertTrue(state.hasAnySubscription)
    }

    @Test
    fun `grace period keeps access`() = runTest {
        // The device still returns the purchase as if active; only the backend knows the payment failed.
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "t1")))
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.GRACE_PERIOD, "t1", expiryTimeMillis = 1_000L)))
        val state = kit(gateway, backend).state.value
        assertEquals(SubscriptionStatus.GRACE_PERIOD, state.subscription("pro_monthly")?.status)
        assertEquals(1_000L, state.subscription("pro_monthly")?.expiryTimeMillis)
        assertTrue(state.isActive("pro_monthly"))
    }

    @Test
    fun `account hold blocks access and still says why`() = runTest {
        // During account hold queryPurchasesAsync() does not return the subscription: only the backend knows it.
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.ON_HOLD, "t1")))
        val state = kit(FakeGateway(), backend).state.value
        assertEquals(SubscriptionStatus.ON_HOLD, state.subscription("pro_monthly")?.status)
        assertFalse(state.isActive("pro_monthly"))
        assertFalse(state.hasAnySubscription)
    }

    @Test
    fun `paused blocks access`() = runTest {
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_yearly", SubscriptionStatus.PAUSED, "t1")))
        val state = kit(FakeGateway(), backend).state.value
        assertEquals(SubscriptionStatus.PAUSED, state.subscription("pro_yearly")?.status)
        assertFalse(state.isActive("pro_yearly"))
    }

    @Test
    fun `a subscription the device reports as suspended blocks access`() = runTest {
        // Billing Library 8.1+: paused or payment declined, returned with includeSuspendedSubscriptions.
        val state = kit(FakeGateway(purchases = listOf(owned("pro_monthly", suspended = true)))).state.value
        assertEquals(SubscriptionStatus.SUSPENDED, state.subscription("pro_monthly")?.status)
        assertFalse(state.isActive("pro_monthly"))
        assertFalse(state.hasAnySubscription)
    }

    @Test
    fun `the backend wins over a stale device cache`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "t1")))
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.ON_HOLD, "t1")))
        val state = kit(gateway, backend).state.value
        assertEquals(SubscriptionStatus.ON_HOLD, state.subscription("pro_monthly")?.status)
        assertFalse(state.isActive("pro_monthly"))
    }

    @Test
    fun `the backend receives the device purchases to verify them`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "t1")))
        val backend = FakeBackend()
        kit(gateway, backend)
        assertEquals(listOf("t1"), backend.received.last().map { it.purchaseToken })
    }

    @Test
    fun `a new purchase is sent to the backend too`() = runTest {
        val gateway = FakeGateway()
        val backend = FakeBackend()
        kit(gateway, backend)
        gateway.updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(owned("pro_yearly", token = "new", acknowledged = false))))
        settle()
        assertEquals(listOf("new"), backend.received.last().map { it.purchaseToken })
    }

    @Test
    fun `a failing backend falls back to what the device reports`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly")))
        val state = kit(gateway, FakeBackend(fail = true)).state.value
        assertEquals(SubscriptionStatus.ACTIVE, state.subscription("pro_monthly")?.status)
        assertTrue(state.isActive("pro_monthly"))
    }

    @Test
    fun `an active token wins over an old expired one for the same product`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "new")))
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.EXPIRED, "old")))
        val state = kit(gateway, backend).state.value
        assertEquals(SubscriptionStatus.ACTIVE, state.subscription("pro_monthly")?.status)
    }

    @Test
    fun `fixing the payment from Play's in-app message restores access`() = runTest {
        val gateway = FakeGateway()
        val backend = FakeBackend(listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.ON_HOLD, "t1")))
        val k = kit(gateway, backend)
        assertEquals(BillingStatus.OK, k.showInAppMessages(activity))
        assertFalse(k.state.value.isActive("pro_monthly"))

        // The user updates the card in Play's message: Play recovers the subscription.
        gateway.purchases = listOf(owned("pro_monthly", token = "t1"))
        backend.statuses = listOf(SubscriptionInfo("pro_monthly", SubscriptionStatus.ACTIVE, "t1"))
        gateway.inAppMessageCallback!!.invoke("t1")
        settle()

        assertEquals(SubscriptionStatus.ACTIVE, k.state.value.subscription("pro_monthly")?.status)
        assertTrue(k.state.value.isActive("pro_monthly"))
    }

    @Test
    fun `a user who never subscribed has no status`() = runTest {
        assertNull(kit(FakeGateway()).state.value.subscription("pro_monthly"))
    }

    @Test
    fun `every subscriptionState of the Developer API is mapped`() {
        val expected = mapOf(
            "SUBSCRIPTION_STATE_ACTIVE" to SubscriptionStatus.ACTIVE,
            "SUBSCRIPTION_STATE_CANCELED" to SubscriptionStatus.CANCELED,
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" to SubscriptionStatus.GRACE_PERIOD,
            "SUBSCRIPTION_STATE_ON_HOLD" to SubscriptionStatus.ON_HOLD,
            "SUBSCRIPTION_STATE_PAUSED" to SubscriptionStatus.PAUSED,
            "SUBSCRIPTION_STATE_EXPIRED" to SubscriptionStatus.EXPIRED,
            "SUBSCRIPTION_STATE_PENDING" to SubscriptionStatus.PENDING,
            "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" to SubscriptionStatus.EXPIRED,
            "SUBSCRIPTION_STATE_UNSPECIFIED" to null,
            "SOMETHING_NEW" to null,
        )
        expected.forEach { (api, status) -> assertEquals(api, status, SubscriptionStatus.fromPlayApi(api)) }
    }

    @Test
    fun `only active, canceled and grace period grant access`() {
        val granting = SubscriptionStatus.entries.filter { it.grantsAccess }.toSet()
        assertEquals(setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED, SubscriptionStatus.GRACE_PERIOD), granting)
    }

    @Test
    fun `Play deep links`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=pro_monthly&package=com.example.app",
            PlayLinks.manageSubscription("com.example.app", "pro_monthly"),
        )
        assertEquals(PlayLinks.SUBSCRIPTIONS_CENTER, PlayLinks.manageSubscription("com.example.app", null))
    }
}
