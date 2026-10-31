package com.thalzeronlabs.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferSelectorTest {

    private val best = OfferSelector.bestForUser()

    @Test
    fun `free trial wins when the user is eligible`() {
        assertEquals("trial7", best.select(PRO_MONTHLY, null)?.offerId)
    }

    @Test
    fun `order of offers does not matter`() {
        val reversed = PRO_MONTHLY.copy(offers = PRO_MONTHLY.offers.reversed())
        assertEquals("trial7", best.select(reversed, null)?.offerId)
    }

    @Test
    fun `without a trial the base plan is sold`() {
        val noTrial = PRO_MONTHLY.copy(offers = PRO_MONTHLY.offers.filter { it.isBasePlan })
        assertNull(best.select(noTrial, null)?.offerId)
    }

    @Test
    fun `an intro price cheaper than the base plan is preferred`() {
        val intro = offer("monthly", "intro", phase(5_000_000, cycles = 3), phase(10_000_000))
        val product = PRO_MONTHLY.copy(offers = listOf(PRO_MONTHLY.offers.first(), intro))
        assertEquals("intro", best.select(product, null)?.offerId)
    }

    @Test
    fun `an offer that costs more than the base plan is never picked`() {
        val weird = offer("monthly", "weird", phase(15_000_000))
        val product = PRO_MONTHLY.copy(offers = listOf(PRO_MONTHLY.offers.first(), weird))
        assertNull(best.select(product, null)?.offerId)
    }

    @Test
    fun `offers tagged manual-only are skipped`() {
        val tagged = PRO_MONTHLY.copy(
            offers = PRO_MONTHLY.offers.map { if (it.offerId == "trial7") it.copy(tags = listOf("manual-only")) else it }
        )
        assertNull(best.select(tagged, null)?.offerId)
    }

    @Test
    fun `base plan filter is respected`() {
        val both = PRO_MONTHLY.copy(offers = PRO_MONTHLY.offers + PRO_YEARLY.offers)
        assertEquals("yearly", best.select(both, "yearly")?.basePlanId)
    }

    @Test
    fun `basePlanOnly and byOfferId do what they say`() {
        assertNull(OfferSelector.basePlanOnly.select(PRO_MONTHLY, null)?.offerId)
        assertEquals("trial7", OfferSelector.byOfferId("trial7").select(PRO_MONTHLY, null)?.offerId)
        assertNull(OfferSelector.byOfferId("missing").select(PRO_MONTHLY, null))
    }
}
