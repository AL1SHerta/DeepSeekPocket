package com.ybdsj.deeppocket.data.remote

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 只允许访问白名单 Host 的拦截器。
 *
 * 这是安全约束的第六道防线：即使哪天有人改了 baseUrl 或加了新接口，
 * 只要 Host 不在白名单里，请求会在发出前就被拦下来，
 * 保证 API Key 永远不会被带到第三方域名。
 */
class HostGuardInterceptor(private val allowedHost: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        require(host.equals(allowedHost, ignoreCase = true)) {
            "Blocked request to non-allowlisted host: $host"
        }
        return chain.proceed(request)
    }
}

/**
 * 网络栈装配。
 *
 * 刻意做成普通对象 + 参数注入，而不是 DI 框架：
 * 这个应用只有一个后端、一个端点，Hilt 带来的构建复杂度和收益不成正比。
 */
object NetworkFactory {

    /** 所有请求共用的 JSON 配置。 */
    val json: Json = Json {
        // 服务端将来加字段不该让 App 崩
        ignoreUnknownKeys = true
        // 严格模式：不接受非标准 JSON，避免把畸形数据当成功
        isLenient = false
        explicitNulls = false
    }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 30L

    /**
     * 构造 [DeepSeekApi]。
     *
     * @param baseUrl 必须以 `/` 结尾，Retrofit 的硬性要求。
     * @param allowedHost 唯一允许访问的 Host，与 [baseUrl] 配套。
     * @param readTimeoutSeconds 读超时，单测里会调小以便验证超时分支。
     */
    fun createApi(
        baseUrl: String = DeepSeekApi.BASE_URL,
        allowedHost: String = DeepSeekApi.ALLOWED_HOST,
        connectTimeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
    ): DeepSeekApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 不跟随重定向：Authorization 头一旦被带到别的地址就是密钥泄漏
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor(HostGuardInterceptor(allowedHost))
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekApi::class.java)
    }
}
