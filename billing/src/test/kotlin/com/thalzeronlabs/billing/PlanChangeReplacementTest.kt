package com.thalzeronlabs.billing

import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plan changes use the per-product replacement params Google documents as preferred
 * (https://developer.android.com/google/play/billing/subscriptions#use_subscriptionproductreplacementparams_for_replacement_preferred).
 * Their ReplacementMode constants do NOT have the same values as the deprecated SubscriptionUpdateParams ones
 * ("the value mapping has been updated"), so every PlanChange is checked here.
 */
class PlanChangeReplacementTest {

    @Test
    fun `every PlanChange maps to the matching per-product replacement mode`() {
        val expected = mapOf(
            PlanChange.WITH_TIME_PRORATION to ReplacementMode.WITH_TIME_PRORATION,
            PlanChange.CHARGE_PRORATED_PRICE to ReplacementMode.CHARGE_PRORATED_PRICE,
            PlanChange.WITHOUT_PRORATION to ReplacementMode.WITHOUT_PRORATION,
            PlanChange.CHARGE_FULL_PRICE to ReplacementMode.CHARGE_FULL_PRICE,
            PlanChange.DEFERRED to ReplacementMode.DEFERRED,
        )
        assertEquals(PlanChange.entries.toSet(), expected.keys)
        expected.forEach { (change, mode) -> assertEquals(change.name, mode, productReplacementModeOf(change)) }
    }

    @Test
    fun `the deprecated constants are not reused where the values differ`() {
        // SubscriptionUpdateParams.ReplacementMode: CHARGE_FULL_PRICE = 5, DEFERRED = 6.
        // Reusing them here would turn a full-price switch into a deferred one, and a deferred one into KEEP_EXISTING.
        assertNotEquals(5, productReplacementModeOf(PlanChange.CHARGE_FULL_PRICE))
        assertNotEquals(6, productReplacementModeOf(PlanChange.DEFERRED))
        assertEquals(4, productReplacementModeOf(PlanChange.CHARGE_FULL_PRICE))
        assertEquals(5, productReplacementModeOf(PlanChange.DEFERRED))
    }

    @Test
    fun `the product a purchase token belongs to is remembered`() {
        val index = OwnedProductIndex()
        index.record(listOf(owned("pro_monthly", token = "t1"), owned("pro_yearly", token = "t2")))
        assertEquals("pro_monthly", index.productOf("t1"))
        assertEquals("pro_yearly", index.productOf("t2"))
        assertNull(index.productOf("unknown"))
    }

    @Test
    fun `a newer report for the same token wins`() {
        val index = OwnedProductIndex()
        index.record(listOf(owned("pro_monthly", token = "t1")))
        // After a deferred change Play keeps the token but may report another product once it renews.
        index.record(listOf(owned("pro_yearly", token = "t1")))
        assertEquals("pro_yearly", index.productOf("t1"))
    }
}
