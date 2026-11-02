package com.thalzeronlabs.billing.sample

import android.app.Activity
import com.thalzeronlabs.billing.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The whole sample flow on the JVM: the paywall ViewModel driving BillingKit over the fake store. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity = Activity()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.paywall(): Pair<PaywallViewModel, () -> PaywallUiState> {
        val vm = PaywallViewModel(FakeBillingGateway())
        backgroundScope.launch { vm.state.collect {} } // keep the WhileSubscribed state hot, like the UI does
        advanceUntilIdle()
        return vm to { vm.state.value }
    }

    @Test
    fun `the paywall shows both plans and the trial`() = runTest(dispatcher) {
        val (_, state) = paywall()
        assertFalse(state().loading)
        assertEquals(listOf("pro_monthly", "pro_yearly"), state().plans.map { it.productId })
        assertEquals("$4.99", state().plans[0].price)
        assertEquals("P1M", state().plans[0].billingPeriod)
        assertEquals("P1W", state().plans[0].freeTrialPeriod)
        assertEquals("$39.99", state().plans[1].price)
        assertEquals(listOf("remove_ads", "coins_100"), state().extras.map { it.productId })
    }

    @Test
    fun `buying unlocks Pro and restore finds it again`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_monthly")
        advanceUntilIdle()
        assertTrue(state().premium)
        assertTrue(state().plans.first { it.productId == "pro_monthly" }.owned)

        vm.restore()
        advanceUntilIdle()
        assertEquals("Purchases restored.", state().message)
        assertTrue(state().premium)
    }

    @Test
    fun `switching to the cheaper yearly plan keeps monthly until renewal`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_monthly")
        advanceUntilIdle()

        vm.buy(activity, "pro_yearly") // $39.99/yr < $4.99/mo: a downgrade, deferred to the renewal date
        advanceUntilIdle()

        val owned = state().plans.filter { it.owned }.map { it.productId }
        assertEquals(listOf("pro_monthly"), owned)
    }

    @Test
    fun `upgrading to monthly replaces yearly at once`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_yearly")
        advanceUntilIdle()

        vm.buy(activity, "pro_monthly") // $4.99/mo > $39.99/yr: an upgrade, applied now
        advanceUntilIdle()

        val owned = state().plans.filter { it.owned }.map { it.productId }
        assertEquals(listOf("pro_monthly"), owned)
    }

    @Test
    fun `reset demo forgets the purchase`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_yearly")
        advanceUntilIdle()
        vm.resetDemo()
        advanceUntilIdle()
        assertFalse(vm.state.first().premium)
        assertFalse(state().premium)
    }

    @Test
    fun `remove ads is bought once and kept`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "remove_ads")
        advanceUntilIdle()
        assertTrue(state().adsRemoved)
        assertFalse(state().premium) // a lifetime product is not a subscription

        vm.buy(activity, "remove_ads")
        advanceUntilIdle()
        assertEquals("You already own this.", state().message)
    }

    @Test
    fun `coins are granted, consumed and can be bought again`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "coins_100")
        advanceUntilIdle()
        assertEquals(100, state().coins)

        vm.buy(activity, "coins_100")
        advanceUntilIdle()
        assertEquals(200, state().coins)
    }

    @Test
    fun `account hold takes Pro away and says why`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_monthly")
        advanceUntilIdle()

        vm.simulate(SubscriptionStatus.ON_HOLD)
        advanceUntilIdle()
        assertFalse(state().premium)
        assertEquals(SubscriptionStatus.ON_HOLD, state().status?.status)

        vm.simulate(null)
        advanceUntilIdle()
        assertTrue(state().premium)
        assertNull(state().status)
    }

    @Test
    fun `grace period and canceled keep Pro with a banner`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_yearly")
        advanceUntilIdle()

        vm.simulate(SubscriptionStatus.GRACE_PERIOD)
        advanceUntilIdle()
        assertTrue(state().premium)
        assertEquals(SubscriptionStatus.GRACE_PERIOD, state().status?.status)

        vm.simulate(SubscriptionStatus.CANCELED)
        advanceUntilIdle()
        assertTrue(state().premium)
        assertEquals(SubscriptionStatus.CANCELED, state().status?.status)
    }

    @Test
    fun `paused takes Pro away`() = runTest(dispatcher) {
        val (vm, state) = paywall()
        vm.buy(activity, "pro_monthly")
        advanceUntilIdle()
        vm.simulate(SubscriptionStatus.PAUSED)
        advanceUntilIdle()
        assertFalse(state().premium)
        assertEquals(SubscriptionStatus.PAUSED, state().status?.status)
    }
}
