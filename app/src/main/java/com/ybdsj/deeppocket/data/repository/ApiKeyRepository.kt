package com.ybdsj.deeppocket.data.repository

import com.ybdsj.deeppocket.data.local.dao.ApiKeyDao
import com.ybdsj.deeppocket.data.local.entity.ApiKeyEntity
import com.ybdsj.deeppocket.data.security.SecretCipher
import com.ybdsj.deeppocket.domain.model.StoredApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 新增 Key 的结果。校验失败是正常的用户输入问题，用独立类型而不是异常表达。 */
sealed interface AddKeyResult {
    data object Success : AddKeyResult
    data class InvalidName(val message: String) : AddKeyResult
    data class InvalidKey(val message: String) : AddKeyResult
    data class StorageFailure(val cause: Throwable) : AddKeyResult
}

/**
 * API Key 的增删改查与加解密。
 *
 * 明文 Key 只在这个类内部以局部变量的形式短暂存在：
 * 写入时立刻加密，读取时只在调用方需要真正发请求前解密。
 * 任何日志、UI 状态、SavedStateHandle 都不应出现明文。
 */
class ApiKeyRepository(
    private val dao: ApiKeyDao,
    private val cipher: SecretCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observeKeys(): Flow<List<StoredApiKey>> =
        dao.observeAll().map { entities -> entities.map { it.toStoredKey() } }

    suspend fun keyCount(): Int = dao.count()

    /** 新增并（在没有其他 Key 时）自动设为当前使用。 */
    suspend fun addKey(rawName: String, rawKey: String): AddKeyResult {
        val name = rawName.trim()
        val apiKey = rawKey.trim()

        if (name.isEmpty()) return AddKeyResult.InvalidName("请给这个 Key 起个名字")
        if (name.length > MAX_NAME_LENGTH) {
            return AddKeyResult.InvalidName("名字最多 $MAX_NAME_LENGTH 个字符")
        }
        if (apiKey.isEmpty()) return AddKeyResult.InvalidKey("请输入 API Key")

        validateApiKey(apiKey)?.let { return AddKeyResult.InvalidKey(it) }

        return try {
            val encrypted = cipher.encrypt(apiKey)
            val shouldActivate = dao.count() == 0
            val entity = ApiKeyEntity(
                name = name,
                cipherText = encrypted.cipherText,
                iv = encrypted.iv,
                keyHint = maskKey(apiKey),
                createdAt = now(),
                lastUsedAt = null,
                // 只在新增激活项时置 true；已有激活项则保持现状
                isActive = shouldActivate,
            )
            dao.insert(entity)
            AddKeyResult.Success
        } catch (e: Exception) {
            AddKeyResult.StorageFailure(e)
        }
    }

    suspend fun rename(id: Long, rawName: String): AddKeyResult {
        val name = rawName.trim()
        if (name.isEmpty()) return AddKeyResult.InvalidName("名字不能为空")
        if (name.length > MAX_NAME_LENGTH) {
            return AddKeyResult.InvalidName("名字最多 $MAX_NAME_LENGTH 个字符")
        }
        return try {
            dao.rename(id, name)
            AddKeyResult.Success
        } catch (e: Exception) {
            AddKeyResult.StorageFailure(e)
        }
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
        // 删掉的是当前激活项时，自动把最早的一条设为激活，避免「有 Key 但没有选中的 Key」这种坏状态
        if (dao.findActive() == null) {
            dao.getAll().firstOrNull()?.let { dao.activateOnly(it.id) }
        }
    }

    suspend fun setActive(id: Long) = dao.activateOnly(id)

    /** 取当前激活的 Key 的安全投影，没有则 null。 */
    suspend fun activeKey(): StoredApiKey? = dao.findActive()?.toStoredKey()

    /**
     * 解密出明文 Key。**调用方必须在用完后尽快丢弃引用**，
     * 不要放进 ViewModel 的 State、不要写日志、不要序列化。
     */
    suspend fun resolvePlainKey(id: Long): String? {
        val entity = dao.findById(id) ?: return null
        return try {
            cipher.decrypt(
                com.ybdsj.deeppocket.data.security.EncryptedPayload(
                    iv = entity.iv,
                    cipherText = entity.cipherText,
                ),
            )
        } catch (e: Exception) {
            // 解密失败通常意味着 KeyStore 里的密钥已失效（如系统重置、应用数据被清）
            // 此时该条记录已无法使用，返回 null 让上层提示用户重新录入。
            null
        }
    }

    suspend fun markUsed(id: Long) = dao.markUsed(id, now())

    private fun validateApiKey(apiKey: String): String? = when {
        apiKey.any { it.isWhitespace() } -> "API Key 不能包含空格或换行"
        apiKey.length < MIN_KEY_LENGTH -> "API Key 看起来不完整，请检查是否复制齐全"
        apiKey.length > MAX_KEY_LENGTH -> "API Key 长度异常"
        else -> null
    }

    private fun maskKey(apiKey: String): String {
        val tail = apiKey.takeLast(HINT_TAIL_LENGTH)
        return "••••$tail"
    }

    private fun ApiKeyEntity.toStoredKey() = StoredApiKey(
        id = id,
        name = name,
        keyHint = keyHint,
        isActive = isActive,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    companion object {
        const val MAX_NAME_LENGTH = 30
        const val MIN_KEY_LENGTH = 16
        const val MAX_KEY_LENGTH = 512
        private const val HINT_TAIL_LENGTH = 4
    }
}
