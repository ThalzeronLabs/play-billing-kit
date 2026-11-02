package com.thalzeronlabs.billing.compose

import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallStringsTest {

    private val strings = PaywallStrings()

    @Test
    fun `prices read naturally with their period`() {
        assertEquals("R$ 9,90 / month", strings.pricePerPeriod("R$ 9,90", "P1M"))
        assertEquals("R$ 99,90 / year", strings.pricePerPeriod("R$ 99,90", "P1Y"))
        assertEquals("R$ 24,90 / 3 months", strings.pricePerPeriod("R$ 24,90", "P3M"))
        assertEquals("R$ 19,90", strings.pricePerPeriod("R$ 19,90", null))
    }

    @Test
    fun `durations multiply the period by its cycles`() {
        assertEquals("1 week", strings.duration("P1W"))
        assertEquals("3 days", strings.duration("P3D"))
        assertEquals("3 months", strings.duration("P1M", 3))
        assertEquals("2 weeks", strings.duration("P1W", 2))
    }

    @Test
    fun `unknown period formats are shown as they are`() {
        assertEquals("P1Y2M", strings.periodUnit("P1Y2M"))
        assertEquals("P1Y2M", strings.duration("P1Y2M"))
    }

    @Test
    fun `button labels follow the plan`() {
        assertEquals("Start 1 week free trial", strings.buyButton(PaywallPlan.from(MONTHLY, MONTHLY_TRIAL)))
        assertEquals("Subscribe", strings.buyButton(PaywallPlan.from(MONTHLY, MONTHLY_BASE)))
        assertEquals("Buy", strings.buyButton(PaywallPlan.from(REMOVE_ADS, REMOVE_ADS.offers.single())))
    }

    @Test
    fun `every status that needs the user has a message`() {
        val needsUser = listOf(
            SubscriptionStatus.GRACE_PERIOD, SubscriptionStatus.ON_HOLD, SubscriptionStatus.PAUSED,
            SubscriptionStatus.SUSPENDED, SubscriptionStatus.CANCELED, SubscriptionStatus.PENDING,
        )
        needsUser.forEach { assertNotNull(it.name, strings.statusMessage(SubscriptionInfo("p", it))) }
        assertNull(strings.statusMessage(SubscriptionInfo("p", SubscriptionStatus.ACTIVE)))
        assertNull(strings.statusMessage(SubscriptionInfo("p", SubscriptionStatus.EXPIRED)))
    }

    @Test
    fun `payment problems ask to fix the payment, pause asks to resume`() {
        assertEquals("Fix payment", strings.statusAction(SubscriptionStatus.GRACE_PERIOD))
        assertEquals("Fix payment", strings.statusAction(SubscriptionStatus.ON_HOLD))
        assertEquals("Resume", strings.statusAction(SubscriptionStatus.PAUSED))
        assertEquals("Resubscribe", strings.statusAction(SubscriptionStatus.CANCELED))
        assertNull(strings.statusAction(SubscriptionStatus.PENDING))
    }

    @Test
    fun `a canceled subscription says until when it lasts`() {
        val custom = object : PaywallStrings() {
            override fun formatDate(epochMillis: Long) = "day $epochMillis"
        }
        val message = custom.statusMessage(SubscriptionInfo("p", SubscriptionStatus.CANCELED, expiryTimeMillis = 42))!!
        assertTrue(message, message.endsWith("until day 42."))
    }
}
