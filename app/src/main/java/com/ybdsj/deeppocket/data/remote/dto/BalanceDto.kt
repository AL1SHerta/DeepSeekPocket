package com.ybdsj.deeppocket.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET https://api.deepseek.com/user/balance 的响应体。
 *
 * 官方示例：
 * ```json
 * {
 *   "is_available": true,
 *   "balance_infos": [
 *     { "currency": "CNY", "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00" }
 *   ]
 * }
 * ```
 *
 * 注意：所有金额字段都是**字符串**，官方没有给数字类型。
 * 这里照样用 String 接，转 BigDecimal 的动作放到 domain 层，
 * 免得浮点误差污染金额。
 */
@Serializable
data class BalanceResponseDto(
    @SerialName("is_available") val isAvailable: Boolean = false,
    @SerialName("balance_infos") val balanceInfos: List<BalanceInfoDto> = emptyList(),
)

/** 单个币种的余额明细。 */
@Serializable
data class BalanceInfoDto(
    @SerialName("currency") val currency: String = "",
    @SerialName("total_balance") val totalBalance: String = "",
    @SerialName("granted_balance") val grantedBalance: String = "",
    @SerialName("topped_up_balance") val toppedUpBalance: String = "",
)
