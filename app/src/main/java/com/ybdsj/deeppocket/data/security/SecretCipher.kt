package com.ybdsj.deeppocket.data.security

/**
 * 对称加解密抽象。
 *
 * 生产实现是 [KeystoreSecretCipher]（Android Keystore 托管的 AES-GCM 密钥）；
 * 单元测试用纯内存假实现替换，从而在不依赖 Android 运行时的情况下验证上层逻辑。
 */
interface SecretCipher {

    /** 用一把新的随机 IV 加密一段文本。 */
    fun encrypt(plainText: String): EncryptedPayload

    /** 解密。IV 不匹配或密文被篡改时抛 [javax.crypto.AEADBadTagException]。 */
    fun decrypt(payload: EncryptedPayload): String
}

/**
 * 一段自包含的加密结果：IV 与密文分开存放，因为 GCM 必须拿到原始 IV 才能解密。
 * 两者都会以 BLOB 落库，数据库里看不到任何可读的密钥片段。
 */
class EncryptedPayload(
    val iv: ByteArray,
    val cipherText: ByteArray,
) {
    // ByteArray 的默认 equals 是引用比较，这里显式给出内容比较，
    // 方便测试里直接断言往返结果。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return iv.contentEquals(other.iv) && cipherText.contentEquals(other.cipherText)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + cipherText.contentHashCode()

    override fun toString(): String =
        "EncryptedPayload(iv=${iv.size}B, cipherText=${cipherText.size}B)"
}
