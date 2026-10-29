package com.ybdsj.deeppocket.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * 节假日表的解析与峰谷联动测试。
 *
 * 关键验证点：法定节假日当天**即使落在周一至周五的工作时段内也必须判为错峰**，
 * 而调休上班日（周末但补班）**即使落在周六周日也必须按工作日进入高峰**。
 */
class HolidayCalendarTest {

    private val sampleJson = """
        {
          "version": "test.1",
          "source": "unit-test",
          "validUntil": "2026-12-31",
          "years": {
            "2026": {
              "holidays": ["2026-10-01", "2026-10-02"],
              "makeupWorkdays": ["2026-09-20", "2026-10-10"]
            }
          }
        }
    """.trimIndent()

    private val calendar = HolidayCalendarLoader.parse(sampleJson)

    // --- 解析 ---

    @Test
    fun `parse reads version source and validUntil`() {
        assertEquals("test.1", calendar.version)
        assertEquals("unit-test", calendar.source)
        assertEquals("2026-12-31", calendar.validUntil.toString())
    }

    @Test
    fun `parse flattens holidays and makeup workdays`() {
        assertEquals(2, calendar.holidays.size)
        assertEquals(2, calendar.makeupWorkdays.size)
        assertTrue(calendar.isHoliday(java.time.LocalDate.parse("2026-10-01")))
        assertTrue(calendar.isMakeupWorkday(java.time.LocalDate.parse("2026-10-10")))
    }

    @Test
    fun `covers is inclusive of validUntil`() {
        assertTrue(calendar.covers(java.time.LocalDate.parse("2026-12-31")))
        assertFalse(calendar.covers(java.time.LocalDate.parse("2027-01-01")))
    }

    // --- 工作日判定 ---

    @Test
    fun `holiday on a thursday is not a working day`() {
        // 2026-10-01 是周四，法定节假日
        assertFalse(calendar.isWorkingDay(java.time.LocalDate.parse("2026-10-01")))
    }

    @Test
    fun `makeup saturday is a working day`() {
        // 2026-10-10 是周六，但调休上班
        assertTrue(calendar.isWorkingDay(java.time.LocalDate.parse("2026-10-10")))
    }

    @Test
    fun `ordinary weekend stays non working`() {
        // 2026-10-11 是周日，未调休
        assertFalse(calendar.isWorkingDay(java.time.LocalDate.parse("2026-10-11")))
    }

    @Test
    fun `ordinary monday is a working day`() {
        // 2026-10-12 是周一，普通工作日
        assertTrue(calendar.isWorkingDay(java.time.LocalDate.parse("2026-10-12")))
    }

    // --- 与峰谷联动 ---

    @Test
    fun `peak window on national holiday is off-peak`() {
        // 北京时间 2026-10-01 09:30 = UTC 01:30，落在高峰窗口内，但当天是国庆
        val instant = Instant.parse("2026-10-01T01:30:00Z")
        assertFalse(PeakHours.isPeakAt(instant, calendar))
        val status = PeakHours.evaluate(instant, calendar)
        assertFalse(status.isPeak)
        assertTrue(status.isHoliday)
        assertTrue(status.calendarCoversToday)
    }

    @Test
    fun `peak window on makeup workday weekend is peak`() {
        // 北京时间 2026-10-10 09:30 = UTC 01:30，周六但调休上班
        val instant = Instant.parse("2026-10-10T01:30:00Z")
        assertTrue(PeakHours.isPeakAt(instant, calendar))
        val status = PeakHours.evaluate(instant, calendar)
        assertTrue(status.isPeak)
        assertTrue(status.isMakeupWorkday)
        assertTrue(status.isWeekend)
        assertTrue(status.calendarCoversToday)
    }

    @Test
    fun `peak window on ordinary monday is peak`() {
        val instant = Instant.parse("2026-10-12T01:30:00Z")
        assertTrue(PeakHours.isPeakAt(instant, calendar))
    }

    @Test
    fun `whole holiday day is off-peak even outside windows`() {
        // 北京时间 2026-10-02 20:00 = UTC 12:00，本来就不是高峰，再确认状态干净
        val instant = Instant.parse("2026-10-02T12:00:00Z")
        assertFalse(PeakHours.isPeakAt(instant, calendar))
        assertTrue(PeakHours.evaluate(instant, calendar).isHoliday)
    }

    @Test
    fun `after validity the calendar degrades to weekday logic`() {
        // 2027-01-04 是周一（表已过期），按星期几判断仍会进入高峰
        val instant = Instant.parse("2027-01-04T01:30:00Z")
        assertTrue(PeakHours.isPeakAt(instant, calendar))
        assertFalse(PeakHours.evaluate(instant, calendar).calendarCoversToday)
    }

    @Test
    fun `empty calendar never reports holiday`() {
        val status = PeakHours.evaluate(Instant.parse("2026-10-01T01:30:00Z"), HolidayCalendar.EMPTY)
        assertFalse(status.isHoliday)
        assertFalse(status.calendarCoversToday)
        // 空表下周四 09:30 会被当成工作日高峰——这是旧行为，也是必须向用户披露的降级。
        assertTrue(status.isPeak)
    }

    @Test
    fun `next state change skips the remaining holiday`() {
        // 用真实内置表：北京时间 2026-10-07 12:30（UTC 04:30）是国庆最后一天的两段
        // 高峰之间，错峰中；下一次进入高峰要到 10-08（周四，节后首个工作日）的
        // UTC 01:00，而不是 10-07 当天的 UTC 06:00。
        val bundled = HolidayCalendarLoader.parse(File("src/main/assets/cn_holidays.json").readText())
        val now = Instant.parse("2026-10-07T04:30:00Z")
        val status = PeakHours.evaluate(now, bundled)
        assertTrue(status.isHoliday)
        assertFalse(status.isPeak)
        assertTrue(status.nextIsPeak)
        assertEquals("2026-10-08T01:00:00Z", status.nextChangeAt.toString())
    }

    // --- 真实内置数据 ---

    @Test
    fun `bundled asset parses and covers the published 2026 schedule`() {
        val file = File("src/main/assets/cn_holidays.json")
        assertTrue("找不到内置节假日文件：${file.absolutePath}", file.exists())
        val bundled = HolidayCalendarLoader.parse(file.readText())

        assertEquals("2026.1", bundled.version)
        assertTrue(bundled.source.contains("国务院办公厅"))
        assertEquals("2026-12-31", bundled.validUntil.toString())

        // 官方通知：元旦3 + 春节9 + 清明3 + 劳动5 + 端午3 + 中秋3 + 国庆7 = 33 天
        assertEquals(33, bundled.holidays.size)
        // 调休上班日：1/4、2/14、2/28、5/9、9/20、10/10
        assertEquals(6, bundled.makeupWorkdays.size)

        // 抽查几个关键日子
        assertTrue(bundled.isHoliday(java.time.LocalDate.parse("2026-02-17")))   // 春节
        assertTrue(bundled.isHoliday(java.time.LocalDate.parse("2026-10-07")))   // 国庆最后一天
        assertTrue(bundled.isMakeupWorkday(java.time.LocalDate.parse("2026-02-14"))) // 春节前补班
        assertFalse(bundled.isHoliday(java.time.LocalDate.parse("2026-02-14")))
    }
}
