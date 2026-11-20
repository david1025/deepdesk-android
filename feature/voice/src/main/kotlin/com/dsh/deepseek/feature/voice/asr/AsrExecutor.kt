package com.dsh.deepseek.feature.voice.asr

import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.network.DeepSeekApiException
import com.dsh.deepseek.core.network.ToolsApiClient
import com.dsh.deepseek.feature.voice.recording.RecordingController
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * ASR 上传执行器（F11）：WAV → DeepSeek ASR → 转写文本。
 * 失败时保留临时 WAV 供用户重试；成功后立即清理（§3.4 性能与隐私）。
 */
class AsrExecutor(private val toolsClient: ToolsApiClient) {

    data class Result(val text: String, val durationMs: Long, val requestId: String?)

    private val generation = AtomicInteger(0)
    @Volatile private var activeCall: Call? = null

    /** 终止进行中的上传。已取消的 [recognize] 返回 null，不回填草稿。 */
    fun cancelActive() {
        generation.incrementAndGet()
        activeCall?.cancel()
    }

    /**
     * 阻塞实现，调用方在 IO 协程执行。
     * 用户取消、切后台或离开录音流程时返回 null。
     */
    fun recognize(wavFile: File): Result? {
        val gen = generation.get()
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            throw DeepSeekApiException(null, "local_error", "录音文件为空", null)
        }
        if (wavFile.length() > RecordingController.MAX_FILE_BYTES) {
            throw DeepSeekApiException(413, "file_too_large", "录音超过 8 MiB 上限", null)
        }
        val call = toolsClient.openAsrCall(wavFile)
        activeCall = call
        if (gen != generation.get()) {
            call.cancel()
            return null
        }
        return try {
            call.execute().use { response ->
                if (gen != generation.get() || call.isCanceled()) return null
                val parsed = toolsClient.readAsrResponse(response)
                if (gen != generation.get()) return null
                Result(
                    text = parsed.text ?: "",
                    durationMs = parsed.durationMs ?: 0L,
                    requestId = parsed.requestId,
                )
            }
        } catch (e: IOException) {
            if (gen != generation.get() || call.isCanceled()) null else throw e
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    companion object {
        fun toAppError(e: Exception): AppError = when (e) {
            is DeepSeekApiException -> e.toAppError()
            else -> AppError(AppError.Kind.LOCAL, e.message ?: "语音识别失败")
        }

        fun userFacingHint(err: AppError): String {
            val m = err.message
            return if (
                m.contains("no audio", ignoreCase = true) ||
                m.contains("Invalid audio", ignoreCase = true) ||
                m.contains("录音文件为空")
            ) {
                "未识别到内容"
            } else {
                m.ifBlank { "未识别到内容" }
            }
        }
    }
}
