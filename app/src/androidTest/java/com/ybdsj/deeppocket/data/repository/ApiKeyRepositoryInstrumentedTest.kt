package com.ybdsj.deeppocket.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ybdsj.deeppocket.data.local.DeepPocketDatabase
import com.ybdsj.deeppocket.data.security.KeystoreSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真实设备上跑通「Keystore 加密 + Room 落库 + 解密回原文」的完整链路。
 *
 * 用 in-memory 数据库，避免污染真实数据；加密仍走真实 AndroidKeyStore。
 */
@RunWith(AndroidJUnit4::class)
class ApiKeyRepositoryInstrumentedTest {

    private lateinit var database: DeepPocketDatabase
    private lateinit var repository: ApiKeyRepository

    private val testKey = "sk-1234567890abcdefghijklmnopqrstuv"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DeepPocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ApiKeyRepository(
            dao = database.apiKeyDao(),
            cipher = KeystoreSecretCipher(keyAlias = "test_repo_alias_${System.nanoTime()}"),
            now = { FIXED_NOW },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addKeyStoresCipherTextAndRoundTrips() = runBlocking {
        val result = repository.addKey("个人账号", testKey)

        assertEquals(AddKeyResult.Success, result)

        val stored = database.apiKeyDao().getAll().single()
        assertEquals("个人账号", stored.name)
        assertTrue("首个 Key 应自动激活", stored.isActive)
        assertEquals("••••${testKey.takeLast(4)}", stored.keyHint)

        // 数据库里存的是密文，不能出现明文的任何片段
        val rawCipher = String(stored.cipherText, Charsets.ISO_8859_1)
        assertFalse(rawCipher.contains("sk-"))
        assertFalse(rawCipher.contains(testKey))

        // 但可以解回原文
        assertEquals(testKey, repository.resolvePlainKey(stored.id))
    }

    @Test
    fun onlyOneKeyIsActiveAtATime() = runBlocking {
        repository.addKey("A", "sk-aaaaaaaaaaaaaaaaaaaa1234")
        repository.addKey("B", "sk-bbbbbbbbbbbbbbbbbbbb5678")

        val second = database.apiKeyDao().getAll().first { it.name == "B" }
        repository.setActive(second.id)

        val keys = database.apiKeyDao().getAll()
        assertEquals(1, keys.count { it.isActive })
        assertEquals("B", keys.first { it.isActive }.name)
    }

    @Test
    fun deletingActiveKeyActivatesAnotherOne() = runBlocking {
        repository.addKey("A", "sk-aaaaaaaaaaaaaaaaaaaa1234")
        repository.addKey("B", "sk-bbbbbbbbbbbbbbbbbbbb5678")

        val active = database.apiKeyDao().findActive()
        assertNotNull(active)
        repository.delete(active!!.id)

        val remaining = database.apiKeyDao().getAll().single()
        assertTrue("删掉激活项后应自动选出新的激活项", remaining.isActive)
    }

    @Test
    fun renameUpdatesNameOnly() = runBlocking {
        repository.addKey("旧名字", testKey)
        val stored = database.apiKeyDao().getAll().single()

        assertEquals(AddKeyResult.Success, repository.rename(stored.id, "新名字"))

        val renamed = database.apiKeyDao().findById(stored.id)!!
        assertEquals("新名字", renamed.name)
        assertEquals(stored.keyHint, renamed.keyHint)
        assertEquals(testKey, repository.resolvePlainKey(stored.id))
    }

    @Test
    fun blankOrTooShortKeyIsRejected() = runBlocking {
        assertTrue(repository.addKey("x", "") is AddKeyResult.InvalidKey)
        assertTrue(repository.addKey("x", "sk-short") is AddKeyResult.InvalidKey)
        assertTrue(repository.addKey("x", "sk-has space inside 1234") is AddKeyResult.InvalidKey)
        assertTrue(repository.addKey("", testKey) is AddKeyResult.InvalidName)

        assertEquals(0, database.apiKeyDao().count())
    }

    @Test
    fun deletedKeyCannotBeResolved() = runBlocking {
        repository.addKey("A", testKey)
        val stored = database.apiKeyDao().getAll().single()

        repository.delete(stored.id)

        assertNull(repository.resolvePlainKey(stored.id))
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
