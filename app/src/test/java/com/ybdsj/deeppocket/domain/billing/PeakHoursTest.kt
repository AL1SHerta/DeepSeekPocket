package com.ybdsj.deeppocket.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 峰谷判定的核心用例。
 *
 * 规则出处（官方 Models & Pricing 原文）：
 * "Peak hours are 01:00 - 04:00 and 06:00 - 10:00 UTC, Monday through Friday,
 *  excluding Chinese public holidays. All other hours are off-peak,
 *  including weekends and Chinese public holidays in full."
 *
 * 2026-10-27 是周二，2026-10-30 周五，2026-10-31 周六，2026-11-01 周日。
 */
class PeakHoursTest {

    private fun utc(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        date.atTime(LocalTime.of(hour, minute)).toInstant(ZoneOffset.UTC)

    private val tuesday = LocalDate.of(2026, 10, 27)
    private val friday = LocalDate.of(2026, 10, 30)
    private val saturday = LocalDate.of(2026, 10, 31)
    private val sunday = LocalDate.of(2026, 11, 1)

    @Test
    fun `weekday inside first window is peak`() {
        assertTrue(PeakHours.evaluate(utc(tuesday, 2)).isPeak)
    }

    @Test
    fun `weekday inside second window is peak`() {
        assertTrue(PeakHours.evaluate(utc(tuesday, 7)).isPeak)
    }

    @Test
    fun `just before first window is off peak`() {
        assertFalse(PeakHours.evaluate(utc(tuesday, 0, 59)).isPeak)
    }

    @Test
    fun `window end is exclusive`() {
        assertFalse(PeakHours.evaluate(utc(tuesday, 4)).isPeak)
        assertFalse(PeakHours.evaluate(utc(tuesday, 10)).isPeak)
    }

    @Test
    fun `gap between two windows is off peak`() {
        assertFalse(PeakHours.evaluate(utc(tuesday, 5)).isPeak)
    }

    @Test
    fun `last peak minute is still peak`() {
        assertTrue(PeakHours.evaluate(utc(tuesday, 9, 59)).isPeak)
    }

    @Test
    fun `weekend is always off peak even inside peak window`() {
        assertFalse(PeakHours.evaluate(utc(saturday, 2)).isPeak)
        assertFalse(PeakHours.evaluate(utc(sunday, 7)).isPeak)
        assertTrue(PeakHours.evaluate(utc(saturday, 2)).isWeekend)
        assertTrue(PeakHours.evaluate(utc(sunday, 7)).isWeekend)
    }

    @Test
    fun `weekday is not weekend`() {
        assertFalse(PeakHours.evaluate(utc(tuesday, 2)).isWeekend)
    }

    @Test
    fun `next change from inside peak window is the window end`() {
        val status = PeakHours.evaluate(utc(tuesday, 2))
        assertTrue(status.isPeak)
        assertEquals(utc(tuesday, 4), status.nextChangeAt)
        assertFalse(status.nextIsPeak)
        assertEquals(Duration.ofHours(2), status.timeUntilChange)
    }

    @Test
    fun `next change from off peak morning gap is next window start`() {
        val status = PeakHours.evaluate(utc(tuesday, 5))
        assertFalse(status.isPeak)
        assertEquals(utc(tuesday, 6), status.nextChangeAt)
        assertTrue(status.nextIsPeak)
    }

    @Test
    fun `after friday last window the next change skips the whole weekend`() {
        val status = PeakHours.evaluate(utc(friday, 11))
        assertFalse(status.isPeak)
        // 周五 10:00 UTC 之后一直错峰，直到下周一 01:00 UTC 才重新进入高峰
        val nextMonday = LocalDate.of(2026, 11, 2)
        assertEquals(utc(nextMonday, 1), status.nextChangeAt)
        assertTrue(status.nextIsPeak)
    }

    @Test
    fun `saturday stays off peak until monday`() {
        val status = PeakHours.evaluate(utc(saturday, 12))
        assertFalse(status.isPeak)
        assertEquals(utc(LocalDate.of(2026, 11, 2), 1), status.nextChangeAt)
    }

    @Test
    fun `beijing windows match official utc windows shifted by eight hours`() {
        assertEquals(TimeWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)), PeakHours.PEAK_WINDOWS_BEIJING[0])
        assertEquals(TimeWindow(LocalTime.of(14, 0), LocalTime.of(18, 0)), PeakHours.PEAK_WINDOWS_BEIJING[1])
    }
}
