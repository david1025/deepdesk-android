package com.david.deepdesk.core.network

import com.david.deepdesk.core.common.ApiKeyProvider
import com.david.deepdesk.core.common.AppLog

/**
 * 请求头策略（设计文档 §5.1）：
 * - anthropic 路径 → x-api-key；anthropic v1 路径一律带 anthropic-beta（files 与消息中的 file_id 引用均需要）
 * - 其余（models、tools 路径）→ Authorization: Bearer
 * 密钥缺失时不加头，由服务端返回 401 引导用户配置。
 */
class AuthInterceptor(private val keyProvider: ApiKeyProvider) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val path = original.url.encodedPath
        val key = keyProvider.getApiKey()

        var builder = original.newBuilder()
        if (!key.isNullOrBlank()) {
            builder = if (path.startsWith("/anthropic")) {
                builder.header("x-api-key", key)
            } else {
                builder.header("Authorization", "Bearer $key")
            }
        }
        if (path.startsWith("/anthropic/v1/")) {
            builder = builder.header("anthropic-beta", FILES_API_BETA)
        }
        val request = builder
            .header("User-Agent", USER_AGENT)
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val FILES_API_BETA = "files-api-2025-04-14"
        const val USER_AGENT = "DeepSeekNativeAndroid/1.1.0 (third-party BYOK client)"
    }
}

/** 日志脱敏拦截器：只输出方法、路径、状态码，绝不输出请求头与请求体 */
class RedactedLoggingInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        AppLog.d(NET_TAG, "--> ${request.method} ${request.url.encodedPath}")
        val response = chain.proceed(request)
        AppLog.d(NET_TAG, "<-- ${response.code} ${request.url.encodedPath} (${response.receivedResponseAtMillis - response.sentRequestAtMillis}ms)")
        return response
    }

    companion object { const val NET_TAG = "Network" }
}
