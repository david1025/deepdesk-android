package com.dsh.deepseek.core.network

import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.model.ApiErrorBody

/**
 * DeepSeek API 异常：携带 HTTP 状态、错误类型与 request_id（诊断字段，不含请求体）。
 */
class DeepSeekApiException(
    val httpStatus: Int?,
    val errorType: String?,
    override val message: String,
    val requestId: String? = null,
) : Exception(message) {

    fun toAppError(): AppError = when (httpStatus) {
        401 -> AppError(AppError.Kind.AUTH, "API Key 无效或已失效，请在设置中检查", requestId, httpStatus, errorType)
        402 -> AppError(AppError.Kind.PAYMENT, "账户余额不足，请前往 DeepSeek 官网充值", requestId, httpStatus, errorType)
        403 -> AppError(AppError.Kind.FORBIDDEN, "无权限访问该能力（403）", requestId, httpStatus, errorType)
        413 -> AppError(AppError.Kind.PAYLOAD_TOO_LARGE, "文件超出大小限制", requestId, httpStatus, errorType)
        429 -> AppError(AppError.Kind.RATE_LIMIT, "请求过于频繁，请稍后重试", requestId, httpStatus, errorType)
        in 500..599 -> AppError(AppError.Kind.SERVER, "服务暂时不可用（$httpStatus），请稍后重试", requestId, httpStatus, errorType)
        else -> AppError(AppError.Kind.UNKNOWN, message.ifBlank { "请求失败" }, requestId, httpStatus, errorType)
    }

    companion object {
        fun fromBody(status: Int, error: ApiErrorBody?, requestId: String?): DeepSeekApiException =
            DeepSeekApiException(
                httpStatus = status,
                errorType = error?.type,
                message = error?.message ?: "请求失败（HTTP $status）",
                requestId = requestId,
            )

        fun fromNetwork(cause: Throwable): DeepSeekApiException =
            DeepSeekApiException(null, "network_error", cause.message ?: "网络异常", null)
    }
}

/** SSE 流中服务端下发 error 事件的业务错误（HTTP 层可能仍是 200） */
class DeepSeekStreamErrorException(
    val errorType: String?,
    override val message: String,
    val requestId: String? = null,
) : Exception(message) {
    fun toAppError(): AppError = when (errorType) {
        "invalid_request_error" -> AppError(AppError.Kind.UNKNOWN, message, requestId, null, errorType)
        "authentication_error" -> AppError(AppError.Kind.AUTH, "API Key 无效或已失效，请在设置中检查", requestId, null, errorType)
        "billing_error", "insufficient_balance" -> AppError(AppError.Kind.PAYMENT, "账户余额不足", requestId, null, errorType)
        "permission_error" -> AppError(AppError.Kind.FORBIDDEN, "无权限访问该能力", requestId, null, errorType)
        "rate_limit_error", "overloaded_error" -> AppError(AppError.Kind.RATE_LIMIT, "请求过于频繁，请稍后重试", requestId, null, errorType)
        "api_error" -> AppError(AppError.Kind.SERVER, "服务暂时不可用，请稍后重试", requestId, null, errorType)
        else -> AppError(AppError.Kind.UNKNOWN, message.ifBlank { "请求失败" }, requestId, null, errorType)
    }
}
