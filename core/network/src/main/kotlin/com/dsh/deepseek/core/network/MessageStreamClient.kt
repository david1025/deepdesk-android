package com.dsh.deepseek.core.network

import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.core.model.ApiErrorEnvelope
import com.dsh.deepseek.core.model.MessageRequest
import com.dsh.deepseek.core.model.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Messages 主聊天 SSE 客户端（设计文档 §5.2/§5.3）。
 * POST /anthropic/v1/messages，以 message_stop 为成功终态；
 * 断流无 message_stop 时以 __interrupted__ 载体通知上游标记 interrupted；
 * HTTP 非 2xx 解析 error 结构并以 __http_error__ 载体传递。
 */
class MessageStreamClient(
    private val client: OkHttpClient,
    private val baseUrl: String = DeepSeekNetworkFactory.BASE_URL,
) {

    fun stream(request: MessageRequest): Flow<SseEvent> = callbackFlow {
        val body = ApiJson.encodeToString(MessageRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val call = client.newCall(
            Request.Builder()
                .url(baseUrl + "anthropic/v1/messages")
                .header("Accept", "text/event-stream")
                .post(body)
                .build()
        )

        val canceled = AtomicBoolean(false)
        val readerThread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        trySend(parseHttpError(response))
                        return@use
                    }
                    val source: BufferedSource = response.body?.source()
                        ?: run {
                            trySend(failureEvent(null, null, "空响应体"))
                            return@use
                        }
                    readEventStream(source, isCanceled = { canceled.get() }) { event -> trySend(event) }
                }
            } catch (t: Exception) {
                when {
                    canceled.get() -> Unit // 用户停止生成：由上游落 STOPPED
                    t is IOException -> {
                        // 断流：上游按 INTERRUPTED 收尾（不算 ERROR，避免重发扣费）
                        AppLog.w(TAG, "SSE 流中断: ${t.message}")
                        trySend(SseEvent.Unknown(MARK_INTERRUPTED, ""))
                    }
                    else -> {
                        AppLog.e(TAG, "SSE 读流异常", t)
                        trySend(SseEvent.Unknown(MARK_FAILED, t.message ?: "请求失败"))
                    }
                }
            } finally {
                close()
            }
        }
        readerThread.name = "dsh-sse-reader"
        readerThread.start()

        awaitClose {
            // 用户停止生成 / 收集取消 → 取消底层调用（已收内容保留为 STOPPED）
            canceled.set(true)
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun readEventStream(source: BufferedSource, isCanceled: () -> Boolean, send: (SseEvent) -> Unit) {
        val decoder = SseDecoder()
        while (true) {
            val line = try {
                source.readUtf8Line() ?: break // EOF = 服务端关闭（无 message_stop 交由上游判定）
            } catch (e: IOException) {
                if (isCanceled()) break
                throw e
            }
            val data = decoder.push(line)
            if (data != null) dispatchEvent(data, send)
        }
        decoder.flush()?.let { dispatchEvent(it, send) }
    }

    private fun dispatchEvent(data: String, send: (SseEvent) -> Unit) {
        parseEvent(data)?.let(send)
    }

    private fun parseEvent(data: String): SseEvent? {
        if (data.isBlank()) return null
        return try {
            ApiJson.decodeFromString(SseEventSerializer, data)
        } catch (e: SerializationException) {
            // 未知事件类型：降级为 Unknown 保留原始数据，不破坏流
            val type = runCatching {
                ApiJson.parseToJsonElement(data).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull()
            AppLog.w(TAG, "未知 SSE 事件 type=$type")
            SseEvent.Unknown(type ?: "__unknown__", data.take(2000))
        } catch (e: Exception) {
            AppLog.w(TAG, "SSE 事件解析失败: ${e.message}")
            null
        }
    }

    private fun parseHttpError(response: Response): SseEvent {
        val requestId = response.header("x-request-id")
        val raw = try {
            response.body?.string()
        } catch (_: Exception) {
            null
        }
        val parsed: com.dsh.deepseek.core.model.ApiErrorBody? = raw?.let {
            runCatching { ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), it).error }.getOrNull()
        }
        val apiError = com.dsh.deepseek.core.model.ApiErrorBody(parsed?.type, parsed?.message)
        return failureEvent(response.code, apiError.type, apiError.message ?: "请求失败（HTTP ${response.code}）", requestId)
    }

    private fun failureEvent(status: Int?, type: String?, message: String, requestId: String? = null): SseEvent =
        SseEvent.Unknown(
            MARK_HTTP_ERROR,
            ApiJson.encodeToString(
                FailureCarrier.serializer(),
                FailureCarrier(status, type, message, requestId),
            ),
        )

    @kotlinx.serialization.Serializable
    data class FailureCarrier(
        val status: Int? = null,
        val type: String? = null,
        val message: String? = null,
        @kotlinx.serialization.SerialName("request_id") val requestId: String? = null,
    )

    companion object {
        private const val TAG = "MsgStream"

        /** 程序化事件标记（不经 JSON 反序列化，直接构造 Unknown） */
        const val MARK_INTERRUPTED = "__interrupted__"
        const val MARK_FAILED = "__failed__"
        const val MARK_HTTP_ERROR = "__http_error__"

        /** 对外共享（Reducer 测试构造事件） */
        val SseEventSerializer: KSerializer<SseEvent> = SseEvent.serializer()
    }
}
