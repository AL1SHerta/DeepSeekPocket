package com.ybdsj.deeppocket.data.remote

import com.ybdsj.deeppocket.core.error.ApiError
import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.repository.BalanceRepository
import com.ybdsj.deeppocket.domain.model.Currency
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * 余额接口契约测试。
 *
 * 覆盖官方文档列出的全部状态码 + 网络层自身会遇到的失败，
 * 全部跑在 MockWebServer 上，不依赖真实网络、不消耗真实账号额度。
 */
class BalanceRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(readTimeoutSeconds: Long = 5L): BalanceRepository =
        BalanceRepository(
            NetworkFactory.createApi(
                baseUrl = server.url("/").toString(),
                allowedHost = server.url("/").host,
                readTimeoutSeconds = readTimeoutSeconds,
            ),
        )

    private fun enqueue(code: Int, body: String = "{}") {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    // ---------- 成功路径 ----------

    @Test
    fun `200 with official sample parses exact decimals`() = runBlocking {
        enqueue(
            200,
            """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}
            ]}
            """.trimIndent(),
        )

        val result = repository().fetchBalance("sk-test-key")

        assertTrue("期望成功，实际 $result", result is ApiResult.Success)
        val balance = (result as ApiResult.Success).data
        assertTrue(balance.isAvailable)
        assertEquals(1, balance.entries.size)

        val entry = balance.entries.single()
        assertEquals(Currency.CNY, entry.currency)
        // 用 BigDecimal 比较，确认没有经过 Double
        assertEquals(BigDecimal("110.00"), entry.total)
        assertEquals(BigDecimal("10.00"), entry.granted)
        assertEquals(BigDecimal("100.00"), entry.toppedUp)
        assertEquals(entry, balance.primary)
    }

    @Test
    fun `sends bearer authorization header`() = runBlocking {
        enqueue(200, """{"is_available":true,"balance_infos":[]}""")

        repository().fetchBalance("sk-abc123")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/user/balance", recorded.path)
        assertEquals("Bearer sk-abc123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `200 with empty balance list is success with no entries`() = runBlocking {
        enqueue(200, """{"is_available":false,"balance_infos":[]}""")

        val result = repository().fetchBalance("sk-x")

        assertTrue(result is ApiResult.Success)
        val balance = (result as ApiResult.Success).data
        assertEquals(false, balance.isAvailable)
        assertTrue(balance.entries.isEmpty())
        assertNull("没有币种时 primary 必须为 null，不能伪造 0 元", balance.primary)
    }

    @Test
    fun `unknown currency is kept as raw code instead of crashing`() = runBlocking {
        enqueue(
            200,
            """{"is_available":true,"balance_infos":[
              {"currency":"JPY","total_balance":"500","granted_balance":"0","topped_up_balance":"500"}
            ]}""",
        )

        val result = repository().fetchBalance("sk-x")

        assertTrue(result is ApiResult.Success)
        val entry = (result as ApiResult.Success).data.entries.single()
        assertEquals(Currency.UNKNOWN, entry.currency)
        assertEquals("JPY", entry.rawCurrencyCode)
        assertEquals(BigDecimal("500"), entry.total)
    }

    // ---------- 服务端错误码 ----------

    @Test
    fun `401 maps to unauthorized`() = runBlocking {
        enqueue(401, """{"error":{"message":"Authentication Fails"}}""")

        val result = repository().fetchBalance("sk-bad")

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `402 maps to insufficient balance`() = runBlocking {
        enqueue(402, """{"error":{"message":"Insufficient Balance"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InsufficientBalance, (result as ApiResult.Failure).error)
    }

    @Test
    fun `400 maps to invalid request`() = runBlocking {
        enqueue(400, """{"error":{"message":"Invalid Format"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InvalidRequest, (result as ApiResult.Failure).error)
    }

    @Test
    fun `422 maps to invalid request`() = runBlocking {
        enqueue(422, """{"error":{"message":"Invalid Parameters"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InvalidRequest, (result as ApiResult.Failure).error)
    }

    @Test
    fun `429 maps to rate limited with retry-after seconds`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", " 30 ")
                .setBody("""{"error":{"message":"Rate Limit Reached"}}"""),
        )

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.RateLimited(30L), (result as ApiResult.Failure).error)
    }

    @Test
    fun `429 without retry-after still maps to rate limited`() = runBlocking {
        enqueue(429, """{"error":{"message":"Rate Limit Reached"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.RateLimited(null), (result as ApiResult.Failure).error)
    }

    @Test
    fun `500 maps to server error`() = runBlocking {
        enqueue(500, """{"error":{"message":"Server Error"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.ServerError(500), (result as ApiResult.Failure).error)
    }

    @Test
    fun `503 maps to server error`() = runBlocking {
        enqueue(503, """{"error":{"message":"Server Overloaded"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.ServerError(503), (result as ApiResult.Failure).error)
    }

    @Test
    fun `unmapped status code becomes unknown`() = runBlocking {
        enqueue(418, """{"error":{"message":"teapot"}}""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.Unknown(418), (result as ApiResult.Failure).error)
    }

    // ---------- 数据异常 ----------

    @Test
    fun `malformed json maps to invalid response`() = runBlocking {
        enqueue(200, """{"is_available": true, "balance_infos": [""")

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InvalidResponse, (result as ApiResult.Failure).error)
    }

    @Test
    fun `non numeric amount maps to invalid response instead of guessing zero`() = runBlocking {
        enqueue(
            200,
            """{"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"not-a-number","granted_balance":"10.00","topped_up_balance":"100.00"}
            ]}""",
        )

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InvalidResponse, (result as ApiResult.Failure).error)
    }

    @Test
    fun `empty body maps to invalid response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))

        val result = repository().fetchBalance("sk-x")

        assertEquals(ApiError.InvalidResponse, (result as ApiResult.Failure).error)
    }

    // ---------- 网络层 ----------

    @Test
    fun `read timeout maps to timeout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"is_available":true,"balance_infos":[]}""")
                .setBodyDelay(3, TimeUnit.SECONDS),
        )

        val result = repository(readTimeoutSeconds = 1L).fetchBalance("sk-x")

        assertEquals(ApiError.Timeout, (result as ApiResult.Failure).error)
    }

    @Test
    fun `blank api key fails fast without hitting network`() = runBlocking {
        val result = repository().fetchBalance("   ")

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `non allowlisted host is blocked before sending`() = runBlocking {
        val api = NetworkFactory.createApi(
            baseUrl = server.url("/").toString(),
            allowedHost = DeepSeekApi.ALLOWED_HOST,
        )

        try {
            api.getBalance("Bearer sk-x")
            fail("期望白名单拦截抛异常，但请求被放行了")
        } catch (expected: IOException) {
            // OkHttp 会拦截器里抛出的异常包装成 IOException（canceled due to ...）
            assertTrue(
                "异常信息应指出被拦截的 Host，实际：${expected.message}",
                expected.message?.contains("non-allowlisted host") == true,
            )
        }
        assertEquals("白名单拦截必须发生在发出请求之前", 0, server.requestCount)
    }
}
