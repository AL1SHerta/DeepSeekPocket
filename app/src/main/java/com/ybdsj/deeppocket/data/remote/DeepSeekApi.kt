package com.ybdsj.deeppocket.data.remote

import com.ybdsj.deeppocket.data.remote.dto.BalanceResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * DeepSeek 官方 API 中本应用会用到的部分。
 *
 * 目前只有余额查询这一个官方端点：
 * GET https://api.deepseek.com/user/balance
 *
 * 注意：官方**没有**历史用量、账单、按模型或按 Key 的统计接口，
 * 所以不要在这里凭空加「用量」端点。
 */
interface DeepSeekApi {

    /**
     * 查询账户余额。
     *
     * @param authorization 完整 Authorization 头，形如 `Bearer sk-xxxx`。
     * 用参数显式传入而不是拦截器，是为了让「哪个 Key 发的请求」在代码里一眼可见，
     * 也方便单测直接控制。
     */
    @GET("user/balance")
    suspend fun getBalance(
        @Header("Authorization") authorization: String,
    ): Response<BalanceResponseDto>

    companion object {
        /** 官方 API 的正式域名，网络层白名单只放行它。 */
        const val BASE_URL: String = "https://api.deepseek.com/"

        /** 请求时使用的 Host，用于白名单校验。 */
        const val ALLOWED_HOST: String = "api.deepseek.com"
    }
}
