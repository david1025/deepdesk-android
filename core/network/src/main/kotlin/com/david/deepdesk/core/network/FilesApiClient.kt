package com.david.deepdesk.core.network

import com.david.deepdesk.core.common.AppLog
import com.david.deepdesk.core.model.ApiErrorEnvelope
import com.david.deepdesk.core.model.FileDeleteResponse
import com.david.deepdesk.core.model.FileUploadResponse
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Files API 客户端（首选 Anthropic 兼容路径，与 Messages 的 file_id 引用一致，§5.1）。
 * POST /anthropic/v1/files：multipart，purpose 字段必须位于 file 字段之前（官方约束）。
 * 上传进度通过包装 RequestBody 统计写入字节。
 */
class FilesApiClient(
    private val client: OkHttpClient,
    private val baseUrl: String = DeepSeekNetworkFactory.BASE_URL,
) {

    /** 上传成功返回响应；HTTP/业务失败抛 [DeepSeekApiException] */
    fun uploadBlocking(
        file: File,
        mimeType: String,
        fileName: String,
        purpose: String?,
        expiresAfterSeconds: Long? = null,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FileUploadResponse {
        val source = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val countingBody = CountingRequestBody(source) { written, total -> onProgress(written, total) }

        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
        // 顺序约束：purpose 在 file 之前
        if (purpose != null) mp.addFormDataPart("purpose", purpose)
        if (expiresAfterSeconds != null) {
            mp.addFormDataPart("expires_after[anchor]", "created_at")
            mp.addFormDataPart("expires_after[seconds]", expiresAfterSeconds.toString())
        }
        mp.addFormDataPart("file", fileName, countingBody)

        val request = Request.Builder()
            .url(baseUrl + "anthropic/v1/files")
            .post(mp.build())
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val err = runCatching {
                    ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), text).error
                }.getOrNull()
                throw DeepSeekApiException.fromBody(response.code, err, response.header("x-request-id"))
            }
            try {
                return ApiJson.decodeFromString(FileUploadResponse.serializer(), text)
            } catch (e: Exception) {
                throw DeepSeekApiException(response.code, "parse_error", "文件上传响应解析失败", response.header("x-request-id"))
            }
        }
    }

    fun isAvailableBlocking(fileId: String): Boolean {
        val request = Request.Builder()
            .url((baseUrl + "anthropic/v1/files").toHttpUrl().newBuilder().addPathSegment(fileId).build())
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(10, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (response.code == 404 || response.code == 410) return false
            if (!response.isSuccessful) {
                val error = runCatching {
                    ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), response.body?.string().orEmpty()).error
                }.getOrNull()
                throw DeepSeekApiException.fromBody(response.code, error, response.header("x-request-id"))
            }
            return true
        }
    }

    fun deleteBlocking(fileId: String): FileDeleteResponse {
        val request = Request.Builder()
            .url(baseUrl + "anthropic/v1/files/$fileId")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw DeepSeekApiException(response.code, null, "删除远端文件失败（${response.code}）", response.header("x-request-id"))
            }
            return runCatching { ApiJson.decodeFromString(FileDeleteResponse.serializer(), text) }
                .getOrElse { FileDeleteResponse(id = fileId) }
        }
    }

    /** 统计进度的请求体包装 */
    private class CountingRequestBody(
        private val delegate: RequestBody,
        private val onWrite: (Long, Long) -> Unit,
    ) : RequestBody() {
        private val written = java.util.concurrent.atomic.AtomicLong(0)

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength()
            val buffer = okio.Buffer()
            delegate.writeTo(buffer)
            val chunk = okio.Buffer()
            while (!buffer.exhausted()) {
                val read = buffer.read(chunk, CHUNK)
                sink.write(chunk, read)
                val w = written.addAndGet(read)
                onWrite(w, total)
            }
            buffer.close()
        }

        companion object { const val CHUNK = 64L * 1024 }
    }
}
