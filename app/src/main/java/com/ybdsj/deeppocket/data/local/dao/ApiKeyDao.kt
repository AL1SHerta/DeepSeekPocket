package com.ybdsj.deeppocket.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ybdsj.deeppocket.data.local.entity.ApiKeyEntity
import kotlinx.coroutines.flow.Flow

/** API Key 表的读写入口。所有写操作走 suspend，读列表走 Flow 自动刷新 UI。 */
@Dao
interface ApiKeyDao {

    @Query("SELECT * FROM api_keys ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys ORDER BY created_at ASC, id ASC")
    suspend fun getAll(): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ApiKeyEntity?

    @Query("SELECT * FROM api_keys WHERE is_active = 1 LIMIT 1")
    suspend fun findActive(): ApiKeyEntity?

    @Query("SELECT COUNT(*) FROM api_keys")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: ApiKeyEntity): Long

    @Update
    suspend fun update(entity: ApiKeyEntity)

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 改名。 */
    @Query("UPDATE api_keys SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /**
     * 把 [id] 设为唯一激活项。
     *
     * `id = :id` 在 SQLite 里是布尔表达式（1/0），
     * 一条 UPDATE 就能把其余全部置为未激活，避免多条语句之间的中间状态。
     */
    @Query("UPDATE api_keys SET is_active = (id = :id)")
    suspend fun activateOnly(id: Long)

    /** 记录一次成功使用的时间。 */
    @Query("UPDATE api_keys SET last_used_at = :timestamp WHERE id = :id")
    suspend fun markUsed(id: Long, timestamp: Long)
}
