package com.ybdsj.deeppocket.data.remote

import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.repository.BalanceRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 密钥泄漏回归测试。
 *
 * 目标不是验证某个功能，而是**钉死一条安全约束**：无论请求成功还是失败，
 * API Key 都不得出现在任何会被记录或展示的字符串里——包括
 * `ApiError.userMessage`（会显示在界面上）、异常的 `message`（会被日志打印）、
 * 以及 `ApiResult.Failure` 的 `toString()`（调试时可能被一起打出来）。
 *
 * 任何一次重构只要不小心把 Authorization 头拼进异常消息，这些用例就会变红。
 */
class ApiKeyLeakTest {

    private lateinit var server: MockWebServer

    /** 形如真实 Key 的探针串，一旦出现在断言文本里就说明泄漏了。 */
    private val canaryKey = "sk-leak-canary-0123456789abcdef"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repository(): BalanceRepository = BalanceRepository(
        NetworkFactory.createApi(
            baseUrl = server.url("/").toString(),
            allowedHost = server.url("/").host,
            readTimeoutSeconds = 5L,
        ),
    )

    private fun enqueue(code: Int) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"stub"}}"""),
        )
    }

    /** 断言这段文本的任何一个变体里都不含探针 Key。 */
    private fun assertNoLeak(label: String, text: String?) {
        if (text == null) return
        assertFalse("$label 泄漏了 API Key：$text", text.contains(canaryKey))
        assertFalse("$label 泄漏了 Authorization 头：$text", text.contains("Bearer", ignoreCase = true))
        // 连部分片段也不允许出现：截取探针中段的 12 个字符做二次检查
        assertFalse("$label 泄漏了 API Key 片段：$text", text.contains(canaryKey.takeLast(12)))
    }

    @Test
    fun `401 failure does not echo api key`() = runBlocking {
        enqueue(401)
        val result = repository().fetchBalance(canaryKey)
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertNoLeak("userMessage", failure.error.userMessage)
        assertNoLeak("cause.message", failure.cause?.message)
        assertNoLeak("cause.toString", failure.cause?.toString())
        assertNoLeak("failure.toString", failure.toString())
    }

    @Test
    fun `429 failure does not echo api key`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "17")
                .setHeader("Content-Type", "application/json")
                .setBody("{}"),
        )
        val result = repository().fetchBalance(canaryKey)
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertNoLeak("userMessage", failure.error.userMessage)
        assertNoLeak("cause.message", failure.cause?.message)
        assertNoLeak("failure.toString", failure.toString())
    }

    @Test
    fun `500 failure does not echo api key`() = runBlocking {
        enqueue(500)
        val result = repository().fetchBalance(canaryKey)
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertNoLeak("userMessage", failure.error.userMessage)
        assertNoLeak("cause.message", failure.cause?.message)
        assertNoLeak("failure.toString", failure.toString())
    }

    @Test
    fun `malformed body failure does not echo api key`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"is_available":true,"balance_infos":"not-a-list"}"""),
        )
        val result = repository().fetchBalance(canaryKey)
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertNoLeak("userMessage", failure.error.userMessage)
        assertNoLeak("cause.message", failure.cause?.message)
        assertNoLeak("cause.toString", failure.cause?.toString())
        assertNoLeak("failure.toString", failure.toString())
    }

    @Test
    fun `network failure does not echo api key`() = runBlocking {
        // 先把 Repository 建好（此时 server 仍在监听），再关掉服务端制造真实的连接失败
        val repo = repository()
        server.shutdown()
        val result = repo.fetchBalance(canaryKey)
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertNotNull(failure.cause)
        assertNoLeak("userMessage", failure.error.userMessage)
        assertNoLeak("cause.message", failure.cause?.message)
        assertNoLeak("cause.toString", failure.cause?.toString())
        assertNoLeak("failure.toString", failure.toString())
    }

    @Test
    fun `successful response does not contain api key`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"is_available":true,"balance_infos":[
                      {"currency":"CNY","total_balance":"1.00","granted_balance":"0.00","topped_up_balance":"1.00"}
                    ]}
                    """.trimIndent(),
                ),
        )
        val result = repository().fetchBalance(canaryKey)
        // 成功结果里只应该有余额数据；把整个结果对象序列化后检查一遍
        assertNoLeak("result.toString", result.toString())
    }

    @Test
    fun `blank key short-circuits without sending request`() = runBlocking {
        val result = repository().fetchBalance("   ")
        assertTrue(result is ApiResult.Failure)
        // 没有发出任何请求，服务端计数保持 0
        assertTrue(server.requestCount == 0)
        assertNoLeak("userMessage", (result as ApiResult.Failure).error.userMessage)
    }
}
