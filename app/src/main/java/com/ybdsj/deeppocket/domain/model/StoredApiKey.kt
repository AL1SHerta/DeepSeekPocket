package com.ybdsj.deeppocket.domain.model

/**
 * 一条 API Key 在界面层的安全投影。
 *
 * 这里**没有明文 Key**：只有用户起的名字和末 4 位提示。
 * 明文只在真正发起请求前一刻，由 Repository 解密到内存局部变量里使用。
 */
data class StoredApiKey(
    val id: Long,
    val name: String,
    val keyHint: String,
    val isActive: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?,
)
