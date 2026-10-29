package com.ybdsj.deeppocket.core.error

/**
 * DeepSeek 接口可能返回的错误分类。
 *
 * 分类依据是官方文档列出的状态码，加上网络层与解析层自身会遇到的失败：
 * 400 Invalid Format、401 Authentication Fails、402 Insufficient Balance、
 * 422 Invalid Parameters、429 Rate Limit Reached、500 Server Error、503 Server Overloaded。
 *
 * 每个分支都带一句面向用户的中文说明，UI 直接展示，不再自己拼文案。
 */
sealed interface ApiError {
    /** 给用户看的一句话，不含技术细节、不含 API Key。 */
    val userMessage: String

    /** 401：API Key 无效、被删除或格式不对。 */
    data object Unauthorized : ApiError {
        override val userMessage: String = "API Key 无效或已被撤销，请检查后重新添加"
    }

    /** 402：账户余额不足。请求本身合法，是账户状态问题。 */
    data object InsufficientBalance : ApiError {
        override val userMessage: String = "账户余额不足，请先充值"
    }

    /** 400 / 422：请求参数不被接受，通常意味着客户端构造的请求有问题。 */
    data object InvalidRequest : ApiError {
        override val userMessage: String = "请求参数不被服务端接受，请稍后重试或反馈问题"
    }

    /** 429：触发限流，官方按账号计，与具体 Key 无关。 */
    data class RateLimited(val retryAfterSeconds: Long? = null) : ApiError {
        override val userMessage: String = if (retryAfterSeconds != null) {
            "请求过于频繁，请在 ${retryAfterSeconds} 秒后重试"
        } else {
            "请求过于频繁，请稍后重试"
        }
    }

    /** 5xx：服务端故障或过载。 */
    data class ServerError(val statusCode: Int) : ApiError {
        override val userMessage: String = "DeepSeek 服务暂时不可用（HTTP $statusCode），请稍后重试"
    }

    /** 连不上：无网络、DNS 失败、连接被拒。 */
    data object Network : ApiError {
        override val userMessage: String = "网络连接失败，请检查网络后重试"
    }

    /** 连接或读取超时。 */
    data object Timeout : ApiError {
        override val userMessage: String = "请求超时，请稍后重试"
    }

    /** 响应体不是预期的 JSON，或金额字段不是合法数字。**绝不猜测数值**。 */
    data object InvalidResponse : ApiError {
        override val userMessage: String = "返回数据格式异常，暂时无法显示余额"
    }

    /** 未归类的状态码。 */
    data class Unknown(val statusCode: Int) : ApiError {
        override val userMessage: String = "发生未知错误（HTTP $statusCode）"
    }
}
