package com.ybdsj.deeppocket.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次余额采样。
 *
 * 官方接口只返回"此刻的余额快照"，没有任何历史用量接口，所以"今日用量"只能靠
 * 本应用自己按时间点采样、再用相邻采样的**下降幅度**累加估算。因此：
 *
 * - 采样是离散的，两次刷新之间发生的消耗会被合并成一次下降，无法拆分到时刻。
 * - 余额上升视为充值，不计入用量（见 `DailyUsageCalculator`）。
 * - 金额一律以十进制字符串存储（[total] 等字段），避免用 Float/Double 存钱带来的误差。
 */
@Entity(
    tableName = "balance_samples",
    indices = [Index(value = ["currency", "sampled_at"])],
)
data class BalanceSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 币种代码，如 CNY / USD。 */
    @ColumnInfo(name = "currency")
    val currency: String,

    /** 以下三个金额均为 BigDecimal 的十进制字符串表示。 */
    @ColumnInfo(name = "total")
    val total: String,

    @ColumnInfo(name = "granted")
    val granted: String,

    @ColumnInfo(name = "topped_up")
    val toppedUp: String,

    /** 采样时刻（epoch millis）。 */
    @ColumnInfo(name = "sampled_at")
    val sampledAt: Long,

    /** 采样时使用的是哪个本地 Key（仅用于排查，余额本身是账号级的）。 */
    @ColumnInfo(name = "api_key_id")
    val apiKeyId: Long? = null,
)
