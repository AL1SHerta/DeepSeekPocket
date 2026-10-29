package com.ybdsj.deeppocket.data.repository

import com.ybdsj.deeppocket.core.error.ApiError
import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.remote.DeepSeekApi
import com.ybdsj.deeppocket.data.remote.dto.BalanceResponseDto
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.BalanceEntry
import com.ybdsj.deeppocket.domain.model.Currency
import kotlinx.serialization.SerializationException
import okhttp3.Headers
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 余额数据源。
 *
 * 职责只有一个：把「一次 HTTP 查询」翻译成 [ApiResult]。
 * 所有失败都在这里被分类，上层（ViewModel / UI）不需要认识 HTTP。
 */
class BalanceRepository(private val api: DeepSeekApi) {

    /**
     * 查询余额。
     *
     * @param apiKey 用户的明文 Key（调用方负责只在内存中持有）。
     */
    suspend fun fetchBalance(apiKey: String): ApiResult<Balance> {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return ApiResult.Failure(ApiError.Unauthorized)

        return try {
            val response = api.getBalance(authorization = "Bearer $trimmed")
            if (!response.isSuccessful) {
                return ApiResult.Failure(mapHttpError(response.code(), response.headers()))
            }
            val dto = response.body() ?: return ApiResult.Failure(ApiError.InvalidResponse)
            toDomain(dto)
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(ApiError.Timeout, e)
        } catch (e: InterruptedIOException) {
            // OkHttp 的读超时抛的是 InterruptedIOException 子类，这里兜住其余情况
            ApiResult.Failure(ApiError.Timeout, e)
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network, e)
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.InvalidResponse, e)
        }
    }

    /** HTTP 状态码 → 错误分类。分类依据是官方文档列出的状态码表。 */
    private fun mapHttpError(code: Int, headers: Headers): ApiError = when (code) {
        400, 422 -> ApiError.InvalidRequest
        401 -> ApiError.Unauthorized
        402 -> ApiError.InsufficientBalance
        429 -> ApiError.RateLimited(headers.get("Retry-After")?.trim()?.toLongOrNull())
        500, 502, 503, 504 -> ApiError.ServerError(code)
        else -> ApiError.Unknown(code)
    }

    /**
     * DTO → 领域模型。
     *
     * 金额用 [BigDecimal] 解析，解析不出来就整体判定为 [ApiError.InvalidResponse]，
     * **不允许**用 0 或任何默认值顶替。
     */
    private fun toDomain(dto: BalanceResponseDto): ApiResult<Balance> {
        val entries = ArrayList<BalanceEntry>(dto.balanceInfos.size)
        for (info in dto.balanceInfos) {
            val total = info.totalBalance.trim().toBigDecimalOrNull()
            val granted = info.grantedBalance.trim().toBigDecimalOrNull()
            val toppedUp = info.toppedUpBalance.trim().toBigDecimalOrNull()
            if (total == null || granted == null || toppedUp == null) {
                return ApiResult.Failure(ApiError.InvalidResponse)
            }
            entries += BalanceEntry(
                currency = Currency.fromCode(info.currency),
                rawCurrencyCode = info.currency.trim(),
                total = total,
                granted = granted,
                toppedUp = toppedUp,
            )
        }
        return ApiResult.Success(Balance(isAvailable = dto.isAvailable, entries = entries))
    }
}
