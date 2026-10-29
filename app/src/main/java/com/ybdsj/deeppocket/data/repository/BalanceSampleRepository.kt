package com.ybdsj.deeppocket.data.repository

import com.ybdsj.deeppocket.data.local.dao.BalanceSampleDao
import com.ybdsj.deeppocket.data.local.entity.BalanceSampleEntity
import com.ybdsj.deeppocket.domain.billing.PeakHours
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.BalancePoint
import com.ybdsj.deeppocket.domain.model.Currency
import com.ybdsj.deeppocket.domain.model.DailyUsage
import com.ybdsj.deeppocket.domain.model.DailyUsageCalculator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 余额采样仓库。
 *
 * 官方接口只给"当前余额快照"，没有历史用量。要让"今日用量"有据可依，只能：
 * 每次余额查询成功后落一条采样，再用当天采样序列里**余额下降**的部分累加。
 *
 * 这里刻意不把"余额差值"直接叫成"消费额"：上升视为充值并跳过，避免充值把用量抵掉。
 */
class BalanceSampleRepository(
    private val dao: BalanceSampleDao,
    private val zoneId: ZoneId = PeakHours.BEIJING,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 记录一次余额快照。只在查询成功后调用；没有任何币种条目时不写入。 */
    suspend fun record(balance: Balance, apiKeyId: Long?): Boolean {
        val entry = balance.primary ?: return false
        dao.insert(
            BalanceSampleEntity(
                currency = entry.currency.code,
                total = entry.total.toPlainString(),
                granted = entry.granted.toPlainString(),
                toppedUp = entry.toppedUp.toPlainString(),
                sampledAt = now(),
                apiKeyId = apiKeyId,
            ),
        )
        return true
    }

    /** 今日（北京时间 00:00 起）某个币种的用量估算。 */
    suspend fun todayUsage(currency: Currency): DailyUsage =
        usageSince(dayStartMillis(), currency)

    /** 从 [fromMillis] 起的用量估算，便于单测与后续"近 7 天"扩展。 */
    suspend fun usageSince(fromMillis: Long, currency: Currency): DailyUsage {
        val samples = dao.since(currency.code, fromMillis)
        val points = samples.mapNotNull { entity ->
            entity.total.toBigDecimalOrNull()?.let { BalancePoint(total = it, sampledAt = entity.sampledAt) }
        }
        return DailyUsageCalculator.compute(currency, points)
    }

    /** 全部采样中最早的一条时刻，用于展示"数据从何时开始积累"。 */
    suspend fun coverageStartAt(): Long? = dao.firstSampleAt()

    suspend fun sampleCount(): Int = dao.count()

    /** 清理过期采样，避免数据库无限增长。 */
    suspend fun pruneOlderThan(days: Long): Int =
        dao.deleteBefore(now() - Duration.ofDays(days).toMillis())

    private fun dayStartMillis(): Long =
        Instant.ofEpochMilli(now())
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
}
