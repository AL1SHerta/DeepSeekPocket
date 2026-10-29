package com.ybdsj.deeppocket.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.AEADBadTagException

/**
 * 在真实设备上验证 Android Keystore 加密链路。
 *
 * 这些断言无法在 JVM 单元测试里跑：AndroidKeyStore 只有真实系统才有实现，
 * 因此放在 androidTest，由 `connectedDebugAndroidTest` 在平板/模拟器上执行。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {

    private fun newCipher(): KeystoreSecretCipher =
        KeystoreSecretCipher(keyAlias = "test_alias_${System.nanoTime()}")

    @Test
    fun encryptThenDecryptReturnsOriginalText() {
        val cipher = newCipher()
        val plain = "sk-1234567890abcdefghijklmnopqrstuv"

        val payload = cipher.encrypt(plain)

        assertEquals(plain, cipher.decrypt(payload))
    }

    @Test
    fun samePlainTextProducesDifferentCipherTextEachTime() {
        val cipher = newCipher()
        val plain = "sk-abcdefghijklmnopqrstuvwxyz012345"

        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)

        // GCM 每次使用随机 IV，所以 IV 与密文都不应相同（避免密文比对泄露信息）
        assertNotEquals(first.iv.toList(), second.iv.toList())
        assertNotEquals(first.cipherText.toList(), second.cipherText.toList())
        assertEquals(plain, cipher.decrypt(first))
        assertEquals(plain, cipher.decrypt(second))
    }

    @Test
    fun cipherTextDoesNotContainPlainText() {
        val cipher = newCipher()
        val plain = "sk-plaintext-must-not-appear"

        val payload = cipher.encrypt(plain)
        val asString = String(payload.cipherText, Charsets.ISO_8859_1)

        assertFalse(asString.contains("sk-plaintext"))
    }

    @Test
    fun tamperedCipherTextIsRejected() {
        val cipher = newCipher()
        val payload = cipher.encrypt("sk-1234567890abcdefghijklmnopqrstuv")

        val tampered = payload.cipherText.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(EncryptedPayload(payload.iv, tampered))
        }
    }
}
