package com.ybdsj.deeppocket.domain.billing

import java.math.BigDecimal

/** 一次阈值判断的结果。[nextSuppressed] 需要写回设置，供下次判断使用。 */
data class AlertDecision(
    val shouldNotify: Boolean,
    val nextSuppressed: Boolean,
)

/**
 * 余额预警的纯逻辑判断。
 *
 * 规则很简单，但必须精确，所以单独抽出来做单元测试：
 * - 未设置阈值（null / ≤0）→ 永不提醒，同时把去重标记复位；
 * - 余额未知（查询失败）→ 不提醒，也不改变去重标记；
 * - 余额低于阈值 → 第一次提醒，之后保持沉默直到余额回到阈值以上；
 * - 余额不低于阈值 → 不提醒，复位去重标记（这样下次跌破会再提醒一次）。
 */
object AlertEvaluator {

    fun evaluate(
        total: BigDecimal?,
        threshold: BigDecimal?,
        alreadySuppressed: Boolean,
    ): AlertDecision {
        if (threshold == null || threshold <= BigDecimal.ZERO) {
            return AlertDecision(shouldNotify = false, nextSuppressed = false)
        }
        if (total == null) {
            return AlertDecision(shouldNotify = false, nextSuppressed = alreadySuppressed)
        }
        return if (total < threshold) {
            AlertDecision(shouldNotify = !alreadySuppressed, nextSuppressed = true)
        } else {
            AlertDecision(shouldNotify = false, nextSuppressed = false)
        }
    }
}
