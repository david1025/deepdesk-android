package com.dsh.deepseek.feature.voice.tts

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.core.network.DeepSeekApiException
import com.dsh.deepseek.core.network.ToolsApiClient
import com.dsh.deepseek.core.model.TtsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * DeepSeek TTS 播放器（F12，§3.5）：
 * - 整段 POST /v1/tools/tts，PCM 24kHz/16-bit/mono 流式回包
 * - AudioTrack(MODE_STREAM) 播放；AudioFocusRequest 管理焦点
 * - 单次 ≤10,000 计费字符：长答案分段连续播放
 * - 同时最多朗读一条消息；切换音色/退出/焦点丢失立即停止
 */
class TtsPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val toolsClient: ToolsApiClient,
) {
    sealed interface State {
        data object Idle : State
        data class Playing(val messageId: String, val segmentIndex: Int, val segmentCount: Int) : State
        data class Failed(val error: AppError) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var playJob: Job? = null
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    fun toggle(messageId: String, text: String, voiceId: String) {
        if (_state.value is State.Playing && (_state.value as State.Playing).messageId == messageId) {
            stop()
            return
        }
        play(messageId, text, voiceId)
    }

    fun play(messageId: String, text: String, voiceId: String) {
        stop()
        val segments = segmentText(text)
        if (segments.isEmpty()) return
        stopped = false
        playJob = scope.launch(Dispatchers.IO) {
            if (!requestFocus()) {
                _state.value = State.Failed(AppError(AppError.Kind.LOCAL, "未能获取音频焦点"))
                return@launch
            }
            try {
                segments.forEachIndexed { index, segment ->
                    if (stopped || !currentCoroutineContext().isActive) return@launch
                    _state.value = State.Playing(messageId, index, segments.size)
                    playSegment(segment, voiceId)
                }
                _state.value = State.Idle
            } catch (e: DeepSeekApiException) {
                AppLog.w(TAG, "TTS 失败: ${e.message}")
                _state.value = State.Failed(e.toAppError())
            } catch (e: IOException) {
                // 流中途断开：不得宣称完整朗读完成（§5.5）
                _state.value = if (!stopped) {
                    State.Failed(AppError(AppError.Kind.NETWORK, "朗读中断"))
                } else {
                    State.Idle
                }
            } catch (e: Exception) {
                if (!stopped) _state.value = State.Failed(AppError(AppError.Kind.LOCAL, "播放失败：${e.message}"))
            } finally {
                abandonFocus()
            }
        }
    }

    fun stop() {
        stopped = true
        playJob?.cancel()
        playJob = null
        releaseTrack()
        if (_state.value is State.Playing || _state.value is State.Failed) {
            _state.value = State.Idle
        }
    }

    /** 切换音色立即停止旧播放 */
    fun onVoiceChanged() = stop()

    // ------------------------------ 内部 ------------------------------

    private suspend fun playSegment(text: String, voiceId: String) = withContext(Dispatchers.IO) {
        val response = toolsClient.ttsStream(
            TtsRequest(text = text, voiceId = voiceId, format = "pcm", filterMarkdown = true)
        )
        response.use { resp ->
            val source = resp.body?.source() ?: throw IOException("空音频流")
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSizeBytes())
                .build()
            track = audioTrack
            audioTrack.play()

            val buffer = ByteArray(8192)
            while (!stopped && currentCoroutineContext().isActive) {
                val n = source.read(buffer)
                if (n == -1) break // 流正常结束
                if (n > 0) {
                    val written = audioTrack.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IOException("AudioTrack 写入失败: $written")
                }
            }
            audioTrack.stop()
            audioTrack.release()
            track = null
        }
    }

    private fun releaseTrack() {
        track?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

    private fun bufferSizeBytes(): Int {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        return min * 4
    }

    private fun requestFocus(): Boolean = try {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    stop() // 焦点丢失（来电等）立即停止
                }
            }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch (e: Exception) {
        AppLog.w(TAG, "音频焦点请求失败", e)
        true // 老设备兜底：仍尝试播放
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        const val SAMPLE_RATE = 24000
        const val MAX_BILLING_CHARS = 10000
        private const val TAG = "TtsPlayer"

        /**
         * ≤10,000 计费字符分段：优先段落，其次句子边界。
         * 纯函数（可 JVM 单测）；分段间保持连续播放状态（§3.5）。
         */
        fun segmentText(text: String, maxChars: Int = 9000): List<String> {
            if (text.isBlank()) return emptyList()
            if (text.length <= maxChars) return listOf(text)
            val paragraphs = text.split("\n\n")
            val out = mutableListOf<String>()
            var current = StringBuilder()
            for (p in paragraphs) {
                if (current.length + p.length + 2 > maxChars) {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current = StringBuilder()
                    }
                    // 单段仍超限：按句子切
                    if (p.length > maxChars) {
                        var buf = StringBuilder()
                        p.split(Regex("(?<=[。！？.!?\n])")).forEach { sentence ->
                            if (buf.length + sentence.length > maxChars) {
                                if (buf.isNotEmpty()) out.add(buf.toString())
                                buf = StringBuilder(sentence)
                            } else {
                                buf.append(sentence)
                            }
                        }
                        current = buf
                    } else {
                        current.append(p)
                    }
                } else {
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(p)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
            return out
        }
    }
}
