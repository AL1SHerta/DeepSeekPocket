package com.ybdsj.deeppocket.domain.model

import java.math.BigDecimal

/** 一次余额观测值（与持久化层解耦的领域对象）。 */
data class BalancePoint(
    val total: BigDecimal,
    val sampledAt: Long,
)

/**
 * 今日用量估算。
 *
 * 官方**没有**提供逐日用量/账单接口，这里的数据完全由本应用自己的余额采样推算：
 * 把当天相邻两次采样的差额中"余额下降"的部分累加起来。
 *
 * 因此必须向用户如实标注：
 * - 这是采样推算值，不是官方计费口径；
 * - [coverageStartAt] 之前的消耗无法回溯（应用没在跑就没采样）；
 * - 两次刷新之间的多笔消耗会被合并成一段下降；
 * - 余额上升视为充值（[hasRecharge]），不计入用量，也不做"净额抵消"。
 */
data class DailyUsage(
    val currency: Currency,
    /** 当天累计的余额下降额（估算用量）。 */
    val estimatedSpend: BigDecimal,
    /** 当天是否观测到余额上升（充值）。 */
    val hasRecharge: Boolean,
    /** 当天参与的采样条数。 */
    val sampleCount: Int,
    /** 当天第一条采样的时刻，即本次统计的覆盖起点。 */
    val coverageStartAt: Long?,
    /** 当天最后一条采样的时刻。 */
    val lastSampleAt: Long?,
    /** 当天最新一次采样的余额。 */
    val latestTotal: BigDecimal?,
) {
    /** 采样不足两条时无法算出任何变化。 */
    val hasEnoughSamples: Boolean get() = sampleCount >= 2
}

/** 把采样序列换算成 [DailyUsage]。 */
object DailyUsageCalculator {

    fun compute(
        currency: Currency,
        points: List<BalancePoint>,
    ): DailyUsage {
        val sorted = points.sortedBy { it.sampledAt }

        var drop = BigDecimal.ZERO
        var sawRecharge = false

        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1].total
            val current = sorted[i].total
            val delta = previous.subtract(current)
            when {
                // 余额减少：视为这段时间内的消耗
                delta.signum() > 0 -> drop = drop.add(delta)
                // 余额增加：视为充值，不参与抵消
                delta.signum() < 0 -> sawRecharge = true
            }
        }

        return DailyUsage(
            currency = currency,
            estimatedSpend = drop,
            hasRecharge = sawRecharge,
            sampleCount = sorted.size,
            coverageStartAt = sorted.firstOrNull()?.sampledAt,
            lastSampleAt = sorted.lastOrNull()?.sampledAt,
            latestTotal = sorted.lastOrNull()?.total,
        )
    }
}
