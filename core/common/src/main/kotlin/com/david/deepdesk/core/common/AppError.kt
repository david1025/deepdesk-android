package com.david.deepdesk.core.common

/**
 * 统一业务错误模型（设计文档 §9 错误处理矩阵）。
 * 继承 Exception 以支持本地预检等场景直接抛出并被统一捕获；
 * UI 根据 [AppError.kind] 决定提示文案与是否允许重试，不得依据 HTTP 200 判定成功。
 */
data class AppError(
    val kind: Kind,
    override val message: String,
    val requestId: String? = null,
    val httpStatus: Int? = null,
    val apiErrorCode: String? = null,
) : Exception(message) {
    enum class Kind {
        /** 401 API Key 错误：不重试，引导去设置页 */
        AUTH,
        /** 402 余额不足：不重试，保留草稿 */
        PAYMENT,
        /** 403 权限/授权限制 */
        FORBIDDEN,
        /** 413 文件过大：显示格式对应上限，本地预检拦截 */
        PAYLOAD_TOO_LARGE,
        /** 当前会话携带的上下文超过请求限制 */
        CONTEXT_LIMIT,
        /** 429 限流：退避重试，避免重试风暴 */
        RATE_LIMIT,
        /** 5xx 服务端错误：可重试 */
        SERVER,
        /** 网络不可用/断流 */
        NETWORK,
        /** 响应无法解析（HTTP 200 但业务失败） */
        PARSE,
        /** 内容安全拒答（stop_reason=refusal） */
        REFUSAL,
        /** 用户主动取消（停止生成/取消录音） */
        CANCELLED,
        /** 本地资源错误（录音设备、文件读取等） */
        LOCAL,
        /** 未知错误 */
        UNKNOWN,
    }

    val isRetryable: Boolean
        get() = kind == Kind.SERVER || kind == Kind.NETWORK || kind == Kind.RATE_LIMIT

    companion object {
        fun cancelled() = AppError(Kind.CANCELLED, "已取消")
        fun network(message: String = "网络连接不可用") = AppError(Kind.NETWORK, message)
        fun local(message: String) = AppError(Kind.LOCAL, message)
        fun parse(message: String) = AppError(Kind.PARSE, message)
    }
}
