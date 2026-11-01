package com.thalzeronlabs.billing.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodLabelTest {

    @Test
    fun `single periods read naturally after a price`() {
        assertEquals("month", periodLabel("P1M", plural = false))
        assertEquals("year", periodLabel("P1Y", plural = false))
    }

    @Test
    fun `trial lengths keep their count`() {
        assertEquals("1 week", periodLabel("P1W"))
        assertEquals("3 days", periodLabel("P3D"))
        assertEquals("3 months", periodLabel("P3M"))
    }

    @Test
    fun `unknown formats are shown as they are`() {
        assertEquals("P1Y2M", periodLabel("P1Y2M"))
    }
}
