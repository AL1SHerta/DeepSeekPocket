package com.ybdsj.deeppocket.domain.model

import java.math.BigDecimal

/**
 * 币种。
 *
 * 官方目前实际只返回 CNY，但接口是列表形式，理论上可能多币种，
 * 所以保留 [UNKNOWN] 兜底：遇到没见过的币种不崩、不猜，原样展示代码。
 */
enum class Currency(val code: String, val symbol: String) {
    CNY("CNY", "¥"),
    USD("USD", "$"),
    UNKNOWN("", ""),
    ;

    companion object {
        fun fromCode(raw: String): Currency =
            entries.firstOrNull { it.code.equals(raw.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 单个币种的余额。
 *
 * 三个金额都来自官方字段，全部用 [BigDecimal]，不做任何四舍五入之外的加工：
 * - [total] 总余额（= 赠金 + 充值）
 * - [granted] 赠金余额
 * - [toppedUp] 充值余额
 */
data class BalanceEntry(
    val currency: Currency,
    val rawCurrencyCode: String,
    val total: BigDecimal,
    val granted: BigDecimal,
    val toppedUp: BigDecimal,
)

/**
 * 一次余额查询的完整结果。
 *
 * [entries] 为空代表服务端没给任何币种信息——UI 要显示「无余额信息」，不能显示 0。
 */
data class Balance(
    val isAvailable: Boolean,
    val entries: List<BalanceEntry>,
) {
    /** 主展示项：优先 CNY，否则第一条，都没有则为 null。 */
    val primary: BalanceEntry?
        get() = entries.firstOrNull { it.currency == Currency.CNY } ?: entries.firstOrNull()
}
