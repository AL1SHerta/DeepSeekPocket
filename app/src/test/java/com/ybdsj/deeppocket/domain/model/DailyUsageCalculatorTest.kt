package com.ybdsj.deeppocket.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DailyUsageCalculatorTest {

    private fun point(total: String, at: Long) =
        BalancePoint(total = BigDecimal(total), sampledAt = at)

    @Test
    fun `empty samples produce zero usage`() {
        val usage = DailyUsageCalculator.compute(Currency.CNY, emptyList())
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal.ZERO))
        assertEquals(0, usage.sampleCount)
        assertFalse(usage.hasEnoughSamples)
    }

    @Test
    fun `single sample cannot produce a delta`() {
        val usage = DailyUsageCalculator.compute(Currency.CNY, listOf(point("57.47", 1_000)))
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal.ZERO))
        assertEquals(1, usage.sampleCount)
        assertFalse(usage.hasEnoughSamples)
    }

    @Test
    fun `balance drop is counted as usage`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(point("57.47", 1_000), point("56.00", 2_000)),
        )
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal("1.47")))
        assertEquals(2, usage.sampleCount)
        assertTrue(usage.hasEnoughSamples)
        assertFalse(usage.hasRecharge)
    }

    @Test
    fun `balance rise is treated as recharge and not counted`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(point("10.00", 1_000), point("110.00", 2_000)),
        )
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal.ZERO))
        assertTrue(usage.hasRecharge)
    }

    @Test
    fun `usage and recharge are separated within one day`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(
                point("20.00", 1_000),
                point("18.50", 2_000), // 消耗 1.50
                point("118.50", 3_000), // 充值 100，不计入
                point("117.00", 4_000), // 消耗 1.50
            ),
        )
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal("3.00")))
        assertTrue(usage.hasRecharge)
        assertEquals(4, usage.sampleCount)
    }

    @Test
    fun `unchanged balance contributes nothing`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(point("57.47", 1_000), point("57.47", 2_000)),
        )
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal.ZERO))
        assertFalse(usage.hasRecharge)
    }

    @Test
    fun `samples are sorted by time before comparison`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(point("56.00", 2_000), point("57.47", 1_000)),
        )
        assertEquals(0, usage.estimatedSpend.compareTo(BigDecimal("1.47")))
    }

    @Test
    fun `coverage range reports first and last sample`() {
        val usage = DailyUsageCalculator.compute(
            Currency.CNY,
            listOf(point("57.47", 1_000), point("56.00", 5_000)),
        )
        assertEquals(1_000L, usage.coverageStartAt)
        assertEquals(5_000L, usage.lastSampleAt)
        assertEquals(0, usage.latestTotal!!.compareTo(BigDecimal("56.00")))
    }
}
