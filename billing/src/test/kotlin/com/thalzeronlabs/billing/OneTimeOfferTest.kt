package com.thalzeronlabs.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneTimeOfferTest {
    @Test
    fun `discount percent is rounded down and only present for a valid lower price`() {
        fun offer(price: Long, fullPrice: Long?) = OneTimeOffer(
            offerToken = "token",
            formattedPrice = "price",
            priceMicros = price,
            currencyCode = "BRL",
            fullPriceMicros = fullPrice,
        )

        assertEquals(25, offer(15_000_000, 20_000_000).discountPercent)
        assertEquals(33, offer(1_990_000, 3_000_000).discountPercent)
        assertNull(offer(15, null).discountPercent)
        assertNull(offer(20, 20).discountPercent)
        assertNull(offer(21, 20).discountPercent)
        assertNull(offer(0, 0).discountPercent)
        assertNull(offer(1, -1).discountPercent)
        assertEquals(99, offer(1, Long.MAX_VALUE).discountPercent)
    }
}
