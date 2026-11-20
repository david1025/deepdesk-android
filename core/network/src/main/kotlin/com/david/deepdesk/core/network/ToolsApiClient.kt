package com.david.deepdesk.core.network

import com.david.deepdesk.core.model.ApiErrorEnvelope
import com.david.deepdesk.core.model.AsrResponse
import com.david.deepdesk.core.model.FetchApiResponse
import com.david.deepdesk.core.model.FetchRequest
import com.david.deepdesk.core.model.OcrResponse
import com.david.deepdesk.core.model.SearchApiResponse
import com.david.deepdesk.core.model.SearchRequest
import com.david.deepdesk.core.model.TtsRequest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File

/**
 * 独立工具 API（Bearer 认证）：ASR / TTS / OCR；Search / Fetch 走 REST。
 * 全部阻塞式实现，由调用方在 IO 协程中执行并自行处理取消。
 */
class ToolsApiClient(
    private val client: OkHttpClient,
    private val restApi: DeepSeekRestApi,
    private val baseUrl: String = DeepSeekNetworkFactory.BASE_URL,
) {

    // ------------------------------ ASR（F11） ------------------------------

    /** 16-bit PCM WAV / Ogg Opus，≤120s、≤8MiB（客户端预检在调用方完成） */
    fun asrBlocking(wavFile: File): AsrResponse =
        openAsrCall(wavFile).execute().use { response -> decodeJson(response, AsrResponse.serializer()) }

    /** 可取消的 ASR 上传。调用方在离开录音流程时 [Call.cancel]。 */
    fun openAsrCall(wavFile: File): Call {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url(baseUrl + "v1/tools/asr")
            .post(body)
            .build()
        return client.newCall(request)
    }

    fun readAsrResponse(response: Response): AsrResponse = decodeJson(response, AsrResponse.serializer())

    // ------------------------------ OCR（F14） ------------------------------

    fun ocrBlocking(file: File, mimeType: String, fileName: String): OcrResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()
        val request = Request.Builder()
            .url(baseUrl + "v1/tools/ocr")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val resp = decodeJson(response, OcrResponse.serializer())
            if (resp.isBusinessError) {
                // HTTP 200 + 业务 error：不得误判成功（§9）
                throw DeepSeekApiException(200, "ocr_business_error", "OCR 解析失败（业务错误）", resp.requestId)
            }
            return resp
        }
    }

    // ------------------------------ TTS（F12） ------------------------------

    /**
     * 整段 TTS：返回响应体 PCM/WAV 流式 Source（24kHz 16-bit mono）。
     * 调用方负责关闭 Response；HTTP 200 后中途断流需由播放侧感知（§5.5）。
     */
    fun ttsStream(request: TtsRequest): Response {
        val json = ApiJson.encodeToString(TtsRequest.serializer(), request)
            .toRequestBody("application/json".toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url(baseUrl + "v1/tools/tts")
            .post(json)
            .build()
        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val text = try { response.body?.string() } catch (_: Exception) { null }
            val err = text?.let {
                runCatching { ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), it).error }.getOrNull()
            }
            response.close()
            throw DeepSeekApiException.fromBody(response.code, err, response.header("x-request-id"))
        }
        return response
    }

    // ------------------------------ Search / Fetch（F15） ------------------------------

    suspend fun search(query: String, count: Int? = null): SearchApiResponse {
        val resp = restApi.search(SearchRequest(query, count))
        if (resp.isBusinessError) {
            throw DeepSeekApiException(200, "search_business_error", "搜索返回业务错误", resp.requestId)
        }
        return resp
    }

    suspend fun fetch(url: String): FetchApiResponse {
        val resp = restApi.fetch(FetchRequest(url))
        if (resp.isBusinessError) {
            throw DeepSeekApiException(200, "fetch_business_error", "网页抓取返回业务错误", resp.requestId)
        }
        return resp
    }

    // ------------------------------ 内部 ------------------------------

    private inline fun <reified T> decodeJson(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T {
        val text = response.body?.string() ?: ""
        val requestId = response.header("x-request-id")
        if (!response.isSuccessful) {
            val err = runCatching { ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), text).error }.getOrNull()
            throw DeepSeekApiException.fromBody(response.code, err, requestId)
        }
        return try {
            ApiJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw DeepSeekApiException(response.code, "parse_error", "响应解析失败", requestId)
        }
    }
}
