package com.ybdsj.deeppocket.core.result

import com.ybdsj.deeppocket.core.error.ApiError

/**
 * 网络与业务调用的统一结果类型。
 *
 * 用 [ApiResult] 而不是直接抛异常，是为了让 UI 层「必须」处理失败分支，
 * 避免出现「加载失败却显示 0 元」这种骗人的界面。
 */
sealed interface ApiResult<out T> {
    /** 成功，[data] 一定是可用数据。 */
    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * 失败。
     *
     * @param error 已分类的错误，带面向用户的中文文案。
     * @param cause 原始异常，仅用于日志与调试，禁止直接展示给用户，也禁止把 API Key 带进去。
     */
    data class Failure(val error: ApiError, val cause: Throwable? = null) : ApiResult<Nothing>
}

/** 成功时取值，失败时返回 null。 */
fun <T> ApiResult<T>.dataOrNull(): T? = (this as? ApiResult.Success)?.data

/** 失败时取错误，成功时返回 null。 */
fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error
