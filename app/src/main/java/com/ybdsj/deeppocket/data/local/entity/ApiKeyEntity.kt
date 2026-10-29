package com.ybdsj.deeppocket.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个 API Key 的本地记录。
 *
 * 安全约束：**这里永远不存明文 Key**。
 * 真实密钥被 [com.ybdsj.deeppocket.data.security.SecretCipher] 加密后，
 * 以 [cipherText] + [iv] 两段字节落库，解密密钥由 Android Keystore 持有且不可导出。
 *
 * [keyHint] 只保留末 4 位，用于列表里让用户分辨是哪个 Key，即使泄露也无法还原。
 */
@Entity(tableName = "api_keys")
data class ApiKeyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 用户起的名字，如「个人」「公司试用」。 */
    @ColumnInfo(name = "name")
    val name: String,

    /** AES-GCM 密文。 */
    @ColumnInfo(name = "cipher_text", typeAffinity = ColumnInfo.BLOB)
    val cipherText: ByteArray,

    /** 该次加密使用的随机 IV（12 字节），GCM 要求每次不同。 */
    @ColumnInfo(name = "iv", typeAffinity = ColumnInfo.BLOB)
    val iv: ByteArray,

    /** 只用于展示的尾部提示，如 `••••abcd`。 */
    @ColumnInfo(name = "key_hint")
    val keyHint: String,

    /** 创建时间（epoch millis）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 最近一次成功查询余额的时间，未用过为 null。 */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,

    /** 是否为当前选中使用的 Key。全表最多一条为 true。 */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,
) {
    // data class 里有 ByteArray 时 equals/hashCode 会退化成引用比较，
    // 这里忽略该问题：实体从不参与集合去重或相等判断。
}
