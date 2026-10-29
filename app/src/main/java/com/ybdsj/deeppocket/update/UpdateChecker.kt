package com.ybdsj.deeppocket.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 一次版本检查的结果，只有三种可能，调用方用 when 穷举即可。
 */
sealed interface UpdateCheckResult {

    /**
     * 有可用的新版本。
     *
     * @property manifest 远端清单原文。
     * @property isIgnored 用户此前点过"忽略此版本"（`ignoredVersionCode >= manifest.versionCode`）。
     *   UI 可据此静默处理：不弹窗、只在设置页留一行提示。
     */
    data class Available(
        val manifest: UpdateManifest,
        val isIgnored: Boolean,
    ) : UpdateCheckResult

    /** 已经是最新（远端 versionCode ≤ 本机 versionCode）。 */
    data object UpToDate : UpdateCheckResult

    /**
     * 检查失败。
     *
     * @property reason 给用户看的简短原因，**已去掉堆栈**，可直接当提示文案。
     */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 版本检测：拉一次远端 `update.json`，和本机 versionCode 比大小。
 *
 * 刻意做成"一个类 + 一个纯函数"：网络部分无可测性可言（不引 MockWebServer），
 * 真正容易出错的版本判定放在 [evaluate] 里，纯函数、可单测。
 */
class UpdateChecker(
    private val client: OkHttpClient = UpdateChecker.defaultHttpClient,
) {

    /**
     * 检查是否有新版本。
     *
     * 按 [UpdateConfig.CHECK_URLS] 的顺序逐个更新源尝试（主地址 GitHub raw，失败回落 jsDelivr 镜像），
     * 任意一个源成功解析出清单就立刻返回结果——所以正常情况只发一次请求，
     * 只有主地址不可达时才会多花几秒。
     *
     * @param currentVersionCode 本机 versionCode（`BuildConfig.VERSION_CODE`）。
     * @param ignoredVersionCode 用户忽略过的 versionCode，0 或更小表示没忽略过。
     * @param urls 更新源列表，默认 [UpdateConfig.CHECK_URLS]；单测或调试可直接传一个地址。
     * @return 三种结果之一；**不会向调用方抛异常**（唯一的例外是协程取消，
     *   取消时必须继续向上抛，否则会破坏结构化并发）。
     */
    suspend fun check(
        currentVersionCode: Int,
        ignoredVersionCode: Int,
        urls: List<String> = UpdateConfig.CHECK_URLS,
    ): UpdateCheckResult {
        if (urls.isEmpty()) {
            return UpdateCheckResult.Failed("没有配置更新源")
        }

        // 总预算：三个源各等一次超时太慢了，整体封顶 UpdateConfig.TOTAL_CHECK_BUDGET_MS
        val deadline = System.currentTimeMillis() + UpdateConfig.TOTAL_CHECK_BUDGET_MS
        // 只报**第一个**失败原因：主地址的报错对排查最有意义，镜像的失败通常只是连带结果
        var firstFailure: UpdateCheckResult.Failed? = null

        for (url in urls) {
            if (System.currentTimeMillis() >= deadline) break

            val raw = try {
                fetch(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = UpdateCheckResult.Failed(shortReason(error))
                continue
            }

            val manifest = try {
                UpdateManifest.parse(raw)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 源是通的，只是清单格式不对：换下一个源大概率也一样，但仍然试一次——CDN 可能缓存着旧版好文件
                if (firstFailure == null) {
                    firstFailure = UpdateCheckResult.Failed("更新清单格式不正确：${shortMessage(error)}")
                }
                continue
            }

            return evaluate(manifest, currentVersionCode, ignoredVersionCode)
        }

        return firstFailure ?: UpdateCheckResult.Failed(
            "检查更新超时：${urls.size} 个更新源都没在 ${UpdateConfig.TOTAL_CHECK_BUDGET_MS / 1000} 秒内返回结果",
        )
    }

    /**
     * GET 清单并用 UTF-8 解成字符串。
     *
     * 失败一律抛异常，由 [check] 统一兜底转成 [UpdateCheckResult.Failed]。
     */
    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException("服务器返回 HTTP ${response.code}")
            }
            // OkHttp 的 ResponseBody.string() 会按响应头/默认 UTF-8 解码并关闭流，只能调用一次
            val body: String? = response.body?.string()
            if (body.isNullOrBlank()) {
                throw IOException("响应内容为空")
            }
            body
        }
    }

    companion object {

        /** 连接超时：清单只有几百字节，连不上就是网络问题，别让用户干等。 */
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** 读超时：GitHub raw 在国内偶尔很慢，给到 10 秒。 */
        private const val READ_TIMEOUT_MS = 10_000L

        /** 整体调用上限：兜住"连上了但一直不吐数据"的极端情况。 */
        private const val CALL_TIMEOUT_MS = 20_000L

        /**
         * 默认客户端。
         *
         * 刻意不复用 [com.ybdsj.deeppocket.data.remote.NetworkFactory] 的那个：
         * 那边带着 `HostGuardInterceptor` 与"不跟随重定向"的密钥安全策略，
         * 而更新检查走的是 GitHub/网盘这类与后端无关的域名，混用会把自己拦死。
         * 这里也不带任何鉴权头——检查更新不需要 API Key，也就没有泄漏面。
         */
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /**
         * 版本判定（纯函数，单测直接测它）。
         *
         * - 远端 versionCode ≤ 本机 → [UpdateCheckResult.UpToDate]；
         * - 远端更高 → [UpdateCheckResult.Available]，并且
         *   `isIgnored = manifest.versionCode <= ignoredVersionCode`——
         *   即用户忽略过这个版本（或更高版本）就标记为已忽略；
         *   一旦远端又涨到比 `ignoredVersionCode` 更高，就会重新"未忽略"，再次提醒。
         */
        @JvmStatic
        fun evaluate(
            manifest: UpdateManifest,
            currentVersionCode: Int,
            ignoredVersionCode: Int,
        ): UpdateCheckResult {
            if (manifest.versionCode <= currentVersionCode) {
                return UpdateCheckResult.UpToDate
            }
            return UpdateCheckResult.Available(
                manifest = manifest,
                isIgnored = manifest.versionCode <= ignoredVersionCode,
            )
        }
    }
}

/**
 * 把 OkHttp 的回调式调用包成挂起函数。
 *
 * 必须在协程取消时 cancel 掉底层 call，否则协程没了、请求还在后台跑。
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                // 协程已经走了，没人消费这个 body，必须自己关掉，否则连接泄漏
                response.close()
            }
        }
    })
}

/** 失败原因的简版描述：只留一句话，不带堆栈、不带异常类全名。 */
private fun shortReason(error: Throwable): String {
    val message = shortMessage(error)
    return when (error) {
        is IllegalArgumentException -> "更新地址不可用：$message"
        is IOException -> "网络请求失败：$message"
        else -> "检查更新失败：$message"
    }
}

private fun shortMessage(error: Throwable): String {
    val raw = error.message?.trim()?.take(120)
    return if (raw.isNullOrEmpty()) error.javaClass.simpleName else raw
}
