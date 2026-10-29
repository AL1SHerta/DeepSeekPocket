package com.ybdsj.deeppocket.domain.billing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 中国法定节假日表。
 *
 * DeepSeek 的峰值计价规则是「UTC 01:00–04:00 与 06:00–10:00、周一至周五、
 * **排除中国法定节假日**」，因此要准确判断某一刻是不是高峰，就必须知道
 * 当天在中国是不是法定节假日，以及是不是「调休上班日」（周末但要上班的日子，
 * 官方按工作日算，仍然会进入高峰）。
 *
 * 这两类日期无法用算法推算（春节、中秋是农历，调休安排由国务院逐年公布），
 * 所以本应用内置一份官方通知整理出的静态表，随版本更新，**不申请日历权限**。
 */
data class HolidayCalendar(
    /** 数据版本，例如 "2026.1"。 */
    val version: String,
    /** 数据来源说明，直接展示给用户。 */
    val source: String,
    /** 表覆盖的最后一天；超过这一天的日期只能退化为「按星期几判断」。 */
    val validUntil: LocalDate,
    /** 法定节假日（放假）：全天错峰。 */
    val holidays: Set<LocalDate>,
    /** 调休上班日（周末补班）：按工作日计算，可能进入高峰。 */
    val makeupWorkdays: Set<LocalDate>,
) {

    /** 这张表是否覆盖 [date]。不覆盖时 [PeakHours] 的判定会退化为纯星期判断。 */
    fun covers(date: LocalDate): Boolean = !date.isAfter(validUntil)

    fun isHoliday(date: LocalDate): Boolean = date in holidays

    fun isMakeupWorkday(date: LocalDate): Boolean = date in makeupWorkdays

    /**
     * [date] 是不是需要上班的日子。
     *
     * 优先级：法定节假日 → 否；调休上班日 → 是；其余按周一至周五判断。
     */
    fun isWorkingDay(date: LocalDate): Boolean = when {
        date in holidays -> false
        date in makeupWorkdays -> true
        else -> date.dayOfWeek.value <= 5
    }

    companion object {
        /** 空表：所有日期都退化为按星期几判断（等价于旧版本行为）。 */
        val EMPTY: HolidayCalendar = HolidayCalendar(
            version = "none",
            source = "",
            validUntil = LocalDate.MIN,
            holidays = emptySet(),
            makeupWorkdays = emptySet(),
        )
    }
}

/** 把 assets 里的 JSON 解析成 [HolidayCalendar]，解析失败由调用方决定降级策略。 */
object HolidayCalendarLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): HolidayCalendar {
        val dto = json.decodeFromString<HolidayAssetDto>(raw)
        val holidays = mutableSetOf<LocalDate>()
        val makeup = mutableSetOf<LocalDate>()
        dto.years.values.forEach { year ->
            year.holidays.forEach { holidays += LocalDate.parse(it) }
            year.makeupWorkdays.forEach { makeup += LocalDate.parse(it) }
        }
        return HolidayCalendar(
            version = dto.version,
            source = dto.source,
            validUntil = LocalDate.parse(dto.validUntil),
            holidays = holidays,
            makeupWorkdays = makeup,
        )
    }
}

@Serializable
private data class HolidayAssetDto(
    val version: String = "",
    val source: String = "",
    val validUntil: String,
    val years: Map<String, HolidayYearDto> = emptyMap(),
)

@Serializable
private data class HolidayYearDto(
    val holidays: List<String> = emptyList(),
    val makeupWorkdays: List<String> = emptyList(),
)
