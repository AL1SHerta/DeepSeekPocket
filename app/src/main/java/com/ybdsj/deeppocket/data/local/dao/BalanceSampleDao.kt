package com.ybdsj.deeppocket.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ybdsj.deeppocket.data.local.entity.BalanceSampleEntity

@Dao
interface BalanceSampleDao {

    @Insert
    suspend fun insert(sample: BalanceSampleEntity): Long

    /** 某币种在 [from] 之后（含）的采样，按时间升序。 */
    @Query(
        "SELECT * FROM balance_samples " +
            "WHERE currency = :currency AND sampled_at >= :from " +
            "ORDER BY sampled_at ASC",
    )
    suspend fun since(currency: String, from: Long): List<BalanceSampleEntity>

    /** 最近若干条采样（按时间倒序），用于展示采样覆盖范围。 */
    @Query("SELECT * FROM balance_samples ORDER BY sampled_at DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<BalanceSampleEntity>

    /** 全部采样中最早的一条时刻；没有任何采样时返回 null。 */
    @Query("SELECT MIN(sampled_at) FROM balance_samples")
    suspend fun firstSampleAt(): Long?

    @Query("SELECT COUNT(*) FROM balance_samples")
    suspend fun count(): Int

    /** 清理过期采样，避免数据库无限增长。 */
    @Query("DELETE FROM balance_samples WHERE sampled_at < :before")
    suspend fun deleteBefore(before: Long): Int
}
