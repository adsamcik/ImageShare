package com.imageshare.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeforeAfterCardTest {
    @Test
    fun reductionPctComputesPositiveReduction() {
        assertEquals(60, reductionPct(before = 1_000, after = 400))
    }

    @Test
    fun reductionPctComputesNegativeReduction() {
        assertEquals(-50, reductionPct(before = 1_000, after = 1_500))
    }

    @Test
    fun reductionPctReturnsNullWhenBeforeUnknown() {
        assertNull(reductionPct(before = null, after = 400))
    }

    @Test
    fun reductionPctReturnsNullWhenBeforeZero() {
        assertNull(reductionPct(before = 0, after = 400))
    }
}
