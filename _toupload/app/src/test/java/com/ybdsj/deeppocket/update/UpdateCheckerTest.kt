package com.ybdsj.deeppocket.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多更新源回落逻辑的测试。
 *
 * 用一个本地 http 服务模拟三种真实会遇到的情况：
 * 主地址 404/500（仓库还没上传清单、raw 被墙后走了代理）、镜像正常、清单内容坏掉。
 * 这样测的是 [UpdateChecker.check] 真实的网络调用与解析串联，而不是拿假数据骗自己。
 */
class UpdateCheckerTest {

    private var server: HttpServer? = null

    private val manifestJson = """
        {
          "versionCode": 9,
          "versionName": "9.9.9",
          "downloadUrl": "https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw",
          "notes": ["测试清单"],
          "publishedAt": "2026-10-29"
        }
    """.trimIndent()

    /** 起一个本地服务，路径与状态码都由调用方给。 */
    private fun startServer(routes: Map<String, Pair<Int, String>>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, statusAndBody) ->
            http.createContext(path) { exchange: HttpExchange ->
                val bytes = statusAndBody.second.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(statusAndBody.first, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `falls back to mirror when primary fails`() = runBlocking {
        val base = startServer(
            mapOf(
                "/update.json" to (404 to "not found"),
                "/mirror.json" to (200 to manifestJson),
            ),
        )

        val result = UpdateChecker().check(
            currentVersionCode = 4,
            ignoredVersionCode = 0,
            urls = listOf("$base/update.json", "$base/mirror.json"),
        )

        assertTrue("应回落镜像并判定为有新版本，实际是 $result", result is UpdateCheckResult.Available)
        val manifest = (result as UpdateCheckResult.Available).manifest
        assertEquals(9, manifest.versionCode)
        assertEquals("9.9.9", manifest.versionName)
    }

    @Test
    fun `falls back when primary returns broken json`() = runBlocking {
        val base = startServer(
            mapOf(
                "/update.json" to (200 to "<html>rate limited</html>"),
                "/mirror.json" to (200 to manifestJson),
            ),
        )

        val result = UpdateChecker().check(
            currentVersionCode = 4,
            ignoredVersionCode = 0,
            urls = listOf("$base/update.json", "$base/mirror.json"),
        )

        assertTrue("清单格式坏掉时应继续试下一个源，实际是 $result", result is UpdateCheckResult.Available)
    }

    @Test
    fun `returns up to date from the first source that answers`() = runBlocking {
        val base = startServer(mapOf("/update.json" to (200 to manifestJson)))

        val result = UpdateChecker().check(
            currentVersionCode = 9,
            ignoredVersionCode = 0,
            urls = listOf("$base/update.json"),
        )

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `ignored version is flagged but still available`() = runBlocking {
        val base = startServer(mapOf("/update.json" to (200 to manifestJson)))

        val result = UpdateChecker().check(
            currentVersionCode = 4,
            ignoredVersionCode = 9,
            urls = listOf("$base/update.json"),
        )

        assertTrue(result is UpdateCheckResult.Available && result.isIgnored)
    }

    @Test
    fun `all sources failing reports the first reason`() = runBlocking {
        val base = startServer(
            mapOf(
                "/a.json" to (404 to "not found"),
                "/b.json" to (500 to "boom"),
            ),
        )

        val result = UpdateChecker().check(
            currentVersionCode = 4,
            ignoredVersionCode = 0,
            urls = listOf("$base/a.json", "$base/b.json"),
        )

        assertTrue("全部失败时必须返回 Failed，实际是 $result", result is UpdateCheckResult.Failed)
        // 报的是第一个源（最有排查价值的那条），不是最后一个
        assertTrue((result as UpdateCheckResult.Failed).reason.contains("404"))
    }
}
