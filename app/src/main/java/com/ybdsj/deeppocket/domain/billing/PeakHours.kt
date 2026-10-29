package com.ybdsj.deeppocket.domain.billing

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 一段以本地时间表示的时段 [start, end)。 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean = !time.isBefore(start) && time.isBefore(end)
}

/** 某一时刻的计费时段判定结果。 */
data class PeakStatus(
    /** 当前是否处于高峰时段（高峰价为标准价，错峰价为半价）。 */
    val isPeak: Boolean,
    /** 是否周末（官方规则中周末全天错峰）。 */
    val isWeekend: Boolean,
    /** 当天是否中国法定节假日（放假，全天错峰）。 */
    val isHoliday: Boolean = false,
    /** 当天是否调休上班日（周末补班，按工作日计算）。 */
    val isMakeupWorkday: Boolean = false,
    /** 内置节假日表是否覆盖当天。为 false 时当天只能按星期几判断，可能误判。 */
    val calendarCoversToday: Boolean = false,
    /** 当前时刻。 */
    val now: Instant,
    /** 下一次**状态翻转**的时刻（高峰→错峰或错峰→高峰，而不是任意窗口边界）。 */
    val nextChangeAt: Instant,
    /** 翻转之后是否进入高峰。 */
    val nextIsPeak: Boolean,
) {
    val timeUntilChange: Duration get() = Duration.between(now, nextChangeAt)
}

/**
 * 时段徽标文案：App 内首页卡片与桌面小组件共用同一套说法，避免两处各写一份后漂移。
 *
 * 高峰＝梁文峰（红），错峰＝梁文谷（绿）。
 */
object PeakBadge {
    const val PEAK = "梁文峰 · 高峰"
    const val OFF_PEAK = "梁文谷 · 错峰"
}

/**
 * DeepSeek 计费时段（峰谷）判定。
 *
 * 规则来源：官方文档 Models & Pricing 页原文 ——
 * "Off-peak rates are half of the peak rates. **Peak hours are 01:00 - 04:00 and
 * 06:00 - 10:00 UTC, Monday through Friday, excluding Chinese public holidays.**
 * All other hours are off-peak, including weekends and Chinese public holidays in full."
 *
 * 换算到北京时间（UTC+8）即：周一至周五 09:00–12:00 与 14:00–18:00 为高峰，
 * 其余时间（含整个周末）为错峰，错峰价 = 高峰价的一半。
 *
 * 关于「排除中国法定节假日」：规则里的节假日是国务院逐年公布的静态安排
 * （春节、中秋按农历，且每年有调休补班），无法由算法推算。本应用改为内置
 * 一份由官方通知整理的 [HolidayCalendar] 静态表，**不申请日历权限、不联网**。
 * 表未覆盖的日期会退化为按星期几判断，此时 [PeakStatus.calendarCoversToday]
 * 为 false，UI 必须如实提示。
 */
object PeakHours {

    val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 官方原文口径：UTC 时间窗。 */
    val PEAK_WINDOWS_UTC: List<TimeWindow> = listOf(
        TimeWindow(LocalTime.of(1, 0), LocalTime.of(4, 0)),
        TimeWindow(LocalTime.of(6, 0), LocalTime.of(10, 0)),
    )

    /** 同一规则换算成北京时间后的展示用窗口。 */
    val PEAK_WINDOWS_BEIJING: List<TimeWindow> = listOf(
        TimeWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)),
        TimeWindow(LocalTime.of(14, 0), LocalTime.of(18, 0)),
    )

    /**
     * 判定 [now] 所处的计费时段。
     *
     * @param calendar 节假日表；缺省为空表（等价于只看星期几的旧行为）。
     */
    fun evaluate(now: Instant, calendar: HolidayCalendar = HolidayCalendar.EMPTY): PeakStatus {
        val utc = now.atZone(ZoneOffset.UTC)
        val date = utc.toLocalDate()
        val isWeekend = utc.dayOfWeek == DayOfWeek.SATURDAY || utc.dayOfWeek == DayOfWeek.SUNDAY
        val isPeak = isPeakAtUtc(date, utc.toLocalTime(), calendar)
        val next = findNextStateChange(now, isPeak, calendar)
        return PeakStatus(
            isPeak = isPeak,
            isWeekend = isWeekend,
            isHoliday = calendar.isHoliday(date),
            isMakeupWorkday = calendar.isMakeupWorkday(date),
            calendarCoversToday = calendar.covers(date),
            now = now,
            nextChangeAt = next,
            nextIsPeak = isPeakAt(next, calendar),
        )
    }

    /** [instant] 这一刻是否处于高峰。 */
    fun isPeakAt(instant: Instant, calendar: HolidayCalendar = HolidayCalendar.EMPTY): Boolean {
        val utc = instant.atZone(ZoneOffset.UTC)
        return isPeakAtUtc(utc.toLocalDate(), utc.toLocalTime(), calendar)
    }

    private fun isPeakAtUtc(date: LocalDate, time: LocalTime, calendar: HolidayCalendar): Boolean {
        if (!calendar.isWorkingDay(date)) return false
        return PEAK_WINDOWS_UTC.any { it.contains(time) }
    }

    /**
     * 找下一个让状态真正翻转的时刻。
     *
     * 只看窗口边界是不够的：例如周五 10:00 UTC 是窗口结束边界，但那一刻前后
     * 都算错峰（周末继续错峰），因此要挑"翻转点"而不是任意边界。
     * 遇到节假日时同样如此：节假日前后的窗口整体不构成高峰。
     */
    private fun findNextStateChange(
        now: Instant,
        currentIsPeak: Boolean,
        calendar: HolidayCalendar,
    ): Instant {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val candidates = buildList {
            for (dayOffset in 0..14) {
                val date = today.plusDays(dayOffset.toLong())
                PEAK_WINDOWS_UTC.forEach { window ->
                    add(date.atTime(window.start).toInstant(ZoneOffset.UTC))
                    add(date.atTime(window.end).toInstant(ZoneOffset.UTC))
                }
            }
        }
        return candidates
            .filter { it.isAfter(now) }
            .sorted()
            .firstOrNull { isPeakAt(it, calendar) != currentIsPeak }
            // 理论上 15 天内一定存在翻转点（连续长假最长 9 天）；兜底返回最早边界，避免崩溃。
            ?: candidates.filter { it.isAfter(now) }.minOrNull()
            ?: now
    }
}
