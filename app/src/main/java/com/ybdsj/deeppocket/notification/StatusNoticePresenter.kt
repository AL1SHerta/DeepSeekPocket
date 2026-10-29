package com.ybdsj.deeppocket.notification

import com.ybdsj.deeppocket.data.repository.BalanceSampleRepository
import com.ybdsj.deeppocket.data.settings.SettingsRepository
import com.ybdsj.deeppocket.domain.billing.HolidayCalendar
import com.ybdsj.deeppocket.domain.billing.PeakHours
import com.ybdsj.deeppocket.domain.billing.PeakStatus
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * 把「余额 / 今日用量 / 峰谷时段」组装成常驻通知的内容。
 *
 * 前台刷新（[com.ybdsj.deeppocket.ui.home.HomeViewModel]）与后台巡检
 * （[com.ybdsj.deeppocket.work.BalanceCheckWorker]）共用这一条路径：
 * 两边都调，才不会出现「开着 App 通知不更新、只有后台才更新」的怪现象。
 *
 * 开关关闭、没有通知权限、或余额还没拿到时，一律撤掉通知而不是留一条过期数据。
 */
object StatusNoticePresenter {

    suspend fun sync(
        settingsRepository: SettingsRepository,
        notifier: StatusNotifier,
        sampleRepository: BalanceSampleRepository,
        holidayCalendar: HolidayCalendar,
        balance: Balance?,
        now: Long = System.currentTimeMillis(),
    ) {
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
        val enabled = settings?.statusNotificationEnabled == true
        if (!enabled || !notifier.canPostNotifications()) {
            notifier.cancel()
            return
        }

        // 余额可能还没拿到（刚开开关、刚换 Key），这时仍然挂出通知但金额显示「—」，
        // 让用户确认开关已生效；下一次刷新会把它替换成真实数字。
        val entry = balance?.primary
        val currency = entry?.currency?.takeIf { it != Currency.UNKNOWN } ?: Currency.CNY
        val usage = runCatching { sampleRepository.todayUsage(currency) }.getOrNull()
        val peak = PeakHours.evaluate(Instant.ofEpochMilli(now), holidayCalendar)

        notifier.update(
            balanceText = entry?.let { it.currency.symbol + formatAmount(it.total) } ?: "—",
            usageText = usage
                ?.takeIf { it.hasEnoughSamples }
                ?.let { it.currency.symbol + formatAmount(it.estimatedSpend) }
                ?: "—",
            peakText = describePeak(peak),
        )
    }

    /** 「高峰 · 距错峰 1 小时 20 分钟」这种一句话摘要。 */
    private fun describePeak(status: PeakStatus): String {
        val phase = if (status.isPeak) "高峰" else "错峰"
        val next = if (status.nextIsPeak) "高峰" else "错峰"
        return "$phase · 距$next ${formatCountdown(status.timeUntilChange)}"
    }

    private fun formatAmount(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun formatCountdown(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        return when {
            totalMinutes < 1 -> "不到 1 分钟"
            totalMinutes < 60 -> "$totalMinutes 分钟"
            else -> {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
            }
        }
    }
}
