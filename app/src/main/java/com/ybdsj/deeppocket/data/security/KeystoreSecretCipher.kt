package com.ybdsj.deeppocket.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 托管密钥的 AES-256-GCM 实现。
 *
 * 设计要点：
 * 1. 密钥本体由系统 Keystore 生成与保存，**永远不进应用进程内存、不可导出**，
 *    即便应用被 root 或数据库被拖走，攻击者也拿不到可用的密钥材料。
 * 2. 每次加密由系统生成新的随机 IV（默认 randomized encryption required），
 *    因此同一明文两次加密的密文不同，避免密文比对泄露信息。
 * 3. 使用 GCM 而不是 CBC：GCM 自带完整性校验，密文被篡改会直接解密失败，
 *    不会静默返回错误数据。
 * 4. 不使用已废弃的 security-crypto / EncryptedSharedPreferences，
 *    直接对接 Keystore API 更透明、依赖更少。
 */
class KeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plainText: String): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(iv = cipher.iv, cipherText = cipherText)
    }

    override fun decrypt(payload: EncryptedPayload): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            obtainOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv),
        )
        return String(cipher.doFinal(payload.cipherText), Charsets.UTF_8)
    }

    /** 取已有密钥；首次调用时生成一把。密钥一旦生成就不再导出。 */
    private fun obtainOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 不要求生物识别：工具类应用不应在查询余额时弹指纹。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "deep_pocket_api_key_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
