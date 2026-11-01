package com.thalzeronlabs.billing.sample

import android.app.Activity
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
        assertEquals("$4.99 / month", state().plans[0].price)
        assertEquals("1 week", state().plans[0].trial)
        assertEquals("$39.99 / year", state().plans[1].price)
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
}
