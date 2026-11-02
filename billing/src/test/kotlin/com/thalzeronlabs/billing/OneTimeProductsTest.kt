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

/** Lifetime (non-consumable) and consumable one-time products, in the same StateFlow as subscriptions. */
@OptIn(ExperimentalCoroutinesApi::class)
class OneTimeProductsTest {

    private val activity = Activity()

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.kit(gateway: FakeGateway) = BillingKit(
        gateway = gateway,
        productIds = listOf("pro_monthly", "pro_yearly"),
        scope = backgroundScope,
        lifetimeProductIds = listOf("remove_ads"),
        consumableProductIds = listOf("coins_100"),
    ).also {
        it.start()
        settle()
    }

    private suspend fun FakeGateway.sheetReturns(purchase: OwnedPurchase) =
        updates.emit(PurchaseUpdate(BillingStatus.OK, listOf(purchase)))

    @Test
    fun `one-time products load next to subscriptions`() = runTest {
        val state = kit(FakeGateway()).state.value
        assertEquals(setOf("remove_ads", "coins_100"), state.oneTimeProducts.keys)
        assertEquals(setOf("pro_monthly", "pro_yearly"), state.products.keys)
    }

    @Test
    fun `a lifetime purchase is acknowledged and unlocks without counting as a subscription`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.sheetReturns(owned("remove_ads", token = "ads", acknowledged = false))
        settle()
        assertEquals(listOf("ads"), gateway.acknowledged)
        assertTrue(k.state.value.isActive("remove_ads"))
        assertFalse(k.state.value.hasAnySubscription)
    }

    @Test
    fun `restore finds a lifetime product bought on another device`() = runTest {
        val gateway = FakeGateway(oneTimePurchases = listOf(owned("remove_ads", token = "ads", acknowledged = false)))
        val state = kit(gateway).state.value
        assertTrue(state.isActive("remove_ads"))
        assertEquals(listOf("ads"), gateway.acknowledged) // the 3-day refund bug, fixed for one-time products too
    }

    @Test
    fun `buy sells the cheapest one-time offer the user is eligible for`() = runTest {
        val gateway = FakeGateway()
        assertEquals(BillingStatus.OK, kit(gateway).buy(activity, "remove_ads"))
        assertEquals(FakeGateway.Launch("remove_ads", "token-remove-ads-launch", null, null), gateway.launches.single())
    }

    @Test
    fun `one-time offers tagged manual-only are not auto-sold`() = runTest {
        val tagged = REMOVE_ADS.copy(offers = REMOVE_ADS.offers.map { if (it.offerId == "launch") it.copy(tags = listOf("manual-only")) else it })
        val gateway = FakeGateway(oneTimeProducts = listOf(tagged, COINS))
        kit(gateway).buy(activity, "remove_ads")
        assertEquals("token-remove-ads", gateway.launches.single().offerToken)
    }

    @Test
    fun `a lifetime product already owned is not sold twice`() = runTest {
        val gateway = FakeGateway(oneTimePurchases = listOf(owned("remove_ads")))
        assertEquals(BillingStatus.ITEM_ALREADY_OWNED, kit(gateway).buy(activity, "remove_ads"))
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun `buying a lifetime product while subscribed is not a plan change`() = runTest {
        val gateway = FakeGateway(purchases = listOf(owned("pro_monthly", token = "sub")))
        kit(gateway).buy(activity, "remove_ads")
        val launch = gateway.launches.single()
        assertNull(launch.oldToken)
        assertNull(launch.mode)
    }

    @Test
    fun `a consumable is left for the app to grant, not acknowledged and not an entitlement`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.sheetReturns(owned("coins_100", token = "coins-1", acknowledged = false, quantity = 3))
        settle()
        val state = k.state.value
        assertTrue(gateway.acknowledged.isEmpty()) // consume() acknowledges it, after the app granted the coins
        assertFalse(state.isActive("coins_100"))
        assertEquals(listOf("coins-1"), state.unconsumedPurchases.map { it.purchaseToken })
        assertEquals(3, state.unconsumedPurchases.single().quantity)
    }

    @Test
    fun `consume then buy again`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)

        k.buy(activity, "coins_100")
        gateway.oneTimePurchases = listOf(owned("coins_100", token = "coins-1", acknowledged = false))
        gateway.sheetReturns(gateway.oneTimePurchases.single())
        settle()

        // Until it is consumed, Play would answer ITEM_ALREADY_OWNED: the kit says so without opening the sheet.
        assertEquals(BillingStatus.ITEM_ALREADY_OWNED, k.buy(activity, "coins_100"))
        assertEquals(1, gateway.launches.size)

        assertEquals(BillingStatus.OK, k.consume("coins-1"))
        assertEquals(listOf("coins-1"), gateway.consumed)
        assertTrue(k.state.value.unconsumedPurchases.isEmpty())

        assertEquals(BillingStatus.OK, k.buy(activity, "coins_100"))
        assertEquals(2, gateway.launches.size)
        gateway.sheetReturns(owned("coins_100", token = "coins-2", acknowledged = false))
        settle()
        assertEquals(listOf("coins-2"), k.state.value.unconsumedPurchases.map { it.purchaseToken })
    }

    @Test
    fun `an unconsumed consumable comes back on restore so it can still be granted`() = runTest {
        val gateway = FakeGateway(oneTimePurchases = listOf(owned("coins_100", token = "lost-coins", acknowledged = false)))
        val state = kit(gateway).state.value
        assertEquals(listOf("lost-coins"), state.unconsumedPurchases.map { it.purchaseToken })
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `item already owned for a consumable restores it`() = runTest {
        val gateway = FakeGateway()
        val k = kit(gateway)
        gateway.oneTimePurchases = listOf(owned("coins_100", token = "coins-1", acknowledged = false))
        gateway.updates.emit(PurchaseUpdate(BillingStatus.ITEM_ALREADY_OWNED, emptyList()))
        settle()
        assertEquals(listOf("coins-1"), k.state.value.unconsumedPurchases.map { it.purchaseToken })
    }

    @Test
    fun `consume refuses lifetime products and subscriptions`() = runTest {
        val gateway = FakeGateway(
            purchases = listOf(owned("pro_monthly", token = "sub")),
            oneTimePurchases = listOf(owned("remove_ads", token = "ads")),
        )
        val k = kit(gateway)
        assertEquals(BillingStatus.DEVELOPER_ERROR, k.consume("ads"))
        assertEquals(BillingStatus.DEVELOPER_ERROR, k.consume("sub"))
        assertTrue(gateway.consumed.isEmpty())
        assertTrue(k.state.value.isActive("remove_ads"))
    }

    @Test
    fun `pending one-time purchases unlock nothing and wait to be granted`() = runTest {
        val gateway = FakeGateway(
            oneTimePurchases = listOf(
                owned("remove_ads", token = "ads", acknowledged = false, pending = true),
                owned("coins_100", token = "coins", acknowledged = false, pending = true),
            )
        )
        val state = kit(gateway).state.value
        assertFalse(state.isActive("remove_ads"))
        assertTrue(state.unconsumedPurchases.isEmpty())
        assertEquals(setOf("remove_ads", "coins_100"), state.pendingProductIds)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `one-time purchases of products the kit was not told about are ignored`() = runTest {
        val gateway = FakeGateway(oneTimePurchases = listOf(owned("legacy_item", token = "legacy", acknowledged = false)))
        val state = kit(gateway).state.value
        assertFalse(state.isActive("legacy_item"))
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `apps without one-time products never query them`() = runTest {
        val gateway = FakeGateway()
        BillingKit(gateway, listOf("pro_monthly"), backgroundScope).start()
        settle()
        assertEquals(0, gateway.oneTimePurchaseQueries)
    }
}
