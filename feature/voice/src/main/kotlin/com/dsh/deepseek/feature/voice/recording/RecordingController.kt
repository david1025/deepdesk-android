package com.dsh.deepseek.feature.voice.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.feature.voice.audio.Resampler
import com.dsh.deepseek.feature.voice.audio.WavWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音状态机（§3.4）：idle → recording → processing → recognized / error。
 * - 16kHz / 单声道 / 16-bit PCM WAV；设备不支持时以更高采样率采集再线性降采样
 * - 单次最长 120 秒，达到上限自动停止
 * - 不持久化原始语音为默认行为：recognized/error 后由调用方清理临时 WAV
 */
class RecordingController(private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data object PermissionRequired : State
        data class Recording(val elapsedMs: Long, val amplitude: Float) : State
        /** 采集完成，等待/正在 ASR。reachedLimit：触达 120 秒或 8 MiB 后自动结束 */
        data class Processing(val file: File, val reachedLimit: Boolean = false) : State
        data class Recognized(val text: String, val durationMs: Long) : State
        /** file 非空表示可重试上传 */
        data class Error(val message: String, val file: File? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var recordJob: Job? = null
    private var warmupJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var warmupRecord: AudioRecord? = null
    private var wavWriter: WavWriter? = null
    private var tempFile: File? = null
    private var startedAt = 0L
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var discarded = false
    @Volatile private var primed = false
    private val audioLock = Any()

    /** 音频约束（官方 ASR 契约） */
    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 120_000L
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 短于约 200ms 的 WAV 会被 ASR 判为 no audio，本地直接当未识别。 */
        const val MIN_PCM_BYTES = 16_000 * 2 / 5
        private const val TAG = "Recorder"
        private val FALLBACK_RATES = intArrayOf(48000, 44100, 32000, 22050)
    }

    /**
     * 冷启动预热 MIC HAL。首次按住说话前调用，避免第一段 WAV 只有文件头。
     * 前置条件：RECORD_AUDIO 已授权。
     */
    @SuppressLint("MissingPermission")
    fun warmup() {
        if (primed) return
        if (_state.value is State.Recording || _state.value is State.Processing) return
        if (warmupJob?.isActive == true) return
        val (sampleRate, minBuf) = pickSampleRate() ?: return
        warmupJob = scope.launch(Dispatchers.IO) {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL,
                ENCODING,
                minBuf * 2,
            )
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return@launch
                }
                val began = synchronized(audioLock) {
                    if (audioRecord != null || _state.value is State.Recording) {
                        false
                    } else {
                        warmupRecord = rec
                        rec.startRecording()
                        true
                    }
                }
                if (!began) {
                    rec.release()
                    return@launch
                }
                val buf = ShortArray(minBuf)
                val deadline = System.currentTimeMillis() + 600L
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (audioRecord != null) break
                    rec.read(buf, 0, buf.size)
                }
                primed = true
            } catch (_: Exception) {
            } finally {
                releaseWarmup(rec)
            }
        }
    }

    /** 前置条件：RECORD_AUDIO 已授权（权限请求由 UI 层 ActivityResult 完成） */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_state.value is State.Recording || _state.value is State.Processing) return
        recordJob?.cancel()
        stopWarmup()
        tempFile?.delete()
        tempFile = null
        stopRequested.set(false)
        discarded = false

        val picked = pickSampleRate()
        if (picked == null) {
            _state.value = State.Error("当前设备不支持录音采样")
            return
        }
        val sampleRate = picked.first
        val minBuf = picked.second

        val file = File(context.cacheDir, "voice/rec_${System.currentTimeMillis()}.wav")
        file.parentFile?.mkdirs()
        tempFile = file
        // 写入的 PCM 一律是 16 kHz（原生采集或降采样后）。WAV 头必须用目标采样率，
        // 不能用设备采集率，否则不支持 16 kHz 的设备会把语音加速交给 ASR。
        val writer = WavWriter(file, TARGET_SAMPLE_RATE).also { wavWriter = it }

        val actualRate = sampleRate
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                actualRate,
                CHANNEL,
                ENCODING,
                minBuf * 2,
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "AudioRecord 创建失败", e)
            _state.value = State.Error("无法启动录音：${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.value = State.Error("录音设备初始化失败")
            return
        }
        audioRecord = record
        startedAt = System.currentTimeMillis()

        _state.value = State.Recording(0L, 0f)

        recordJob = scope.launch(Dispatchers.IO) {
            val downsample = actualRate != TARGET_SAMPLE_RATE
            val bufSize = minBuf
            val buffer = ShortArray(bufSize)
            writer.open()
            try {
                val began = synchronized(audioLock) {
                    if (stopRequested.get() || discarded) {
                        false
                    } else {
                        record.startRecording()
                        true
                    }
                }
                if (began) {
                    waitUntilRecording(record)
                    while (isActive && !stopRequested.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val pcm = if (downsample) {
                                Resampler.downsample(buffer.copyOf(read), actualRate, TARGET_SAMPLE_RATE)
                            } else {
                                buffer.copyOf(read)
                            }
                            writer.appendPcm(shortsToBytes(pcm))
                            val elapsed = System.currentTimeMillis() - startedAt
                            val amp = Resampler.amplitude(pcm)
                            _state.value = State.Recording(elapsed, amp)
                            if (elapsed >= MAX_DURATION_MS || writer.durationMs >= MAX_DURATION_MS) {
                                stopRequested.set(true)
                                break
                            }
                            if (file.length() >= MAX_FILE_BYTES) {
                                stopRequested.set(true)
                                break
                            }
                        } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                            break
                        } else if (stopRequested.get()) {
                            break
                        } else {
                            // 0 / ERROR_INVALID_OPERATION：首次启动 HAL 预热，让出 CPU 再读
                            delay(10)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "录音异常", e)
                if (!discarded) {
                    writer.abort()
                    _state.value = State.Error("录音中断，请重试")
                }
                return@launch
            } finally {
                releaseAudio()
            }
            if (discarded) return@launch
            writer.close()
            wavWriter = null
            if (discarded) return@launch
            val out = tempFile
            if (out == null || writer.pcmByteCount < MIN_PCM_BYTES) {
                out?.delete()
                tempFile = null
                _state.value = State.Recognized("", 0)
            } else {
                _state.value = State.Processing(out, reachedLimit = writer.durationMs >= MAX_DURATION_MS - 500)
            }
        }
    }

    /** 点击“完成”：停止采集，由录音协程收尾并进入 processing。 */
    fun finish(): File? {
        if (_state.value !is State.Recording) return null
        requestStop()
        return tempFile
    }

    /** 放弃本次录音：释放资源并删除临时文件 */
    fun cancel() {
        discarded = true
        requestStop()
        recordJob?.cancel()
        releaseAudio()
        wavWriter?.abort()
        wavWriter = null
        tempFile?.delete()
        tempFile = null
        _state.value = State.Idle
    }

    /** 由调用方在 ASR 成功/用户放弃后调用：清空临时文件（§3.4 隐私约束） */
    fun cleanupTempFile() {
        discardTempFile()
        _state.value = State.Idle
    }

    /** 删掉临时 WAV，不改状态。空识别结果仍要留在 error 供用户重录。 */
    fun discardTempFile() {
        tempFile?.delete()
        tempFile = null
        wavWriter = null
    }

    fun reset() {
        recordJob?.cancel()
        stopWarmup()
        releaseAudio()
        wavWriter = null
        tempFile = null
        _state.value = State.Idle
    }

    /** Lifecycle ON_STOP：立即释放（§7.2） */
    fun release() {
        if (_state.value is State.Recording) {
            cancel()
        } else {
            reset()
        }
    }

    /** ASR 成功回调（由 AsrExecutor 调用方写入，保持状态机单一来源） */
    fun notifyRecognized(text: String, durationMs: Long) {
        _state.value = State.Recognized(text, durationMs)
    }

    /** ASR 失败回调；file 非空时 UI 提供重试上传入口 */
    fun notifyError(message: String, file: File?) {
        _state.value = State.Error(message, file)
    }

    /** 识别失败后的重试：回到 processing，由调用方再次发起 ASR */
    fun beginProcessing(file: File) {
        if (!file.exists() || file.length() == 0L) {
            _state.value = State.Error("录音文件不存在，请重新录制", null)
            return
        }
        tempFile = file
        _state.value = State.Processing(file, reachedLimit = false)
    }

    // ------------------------------ 内部 ------------------------------

    private fun requestStop() {
        stopRequested.set(true)
        synchronized(audioLock) {
            try {
                val rec = audioRecord
                if (rec != null && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun waitUntilRecording(record: AudioRecord, timeoutMs: Long = 800L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (
            record.recordingState != AudioRecord.RECORDSTATE_RECORDING &&
            !stopRequested.get() &&
            System.currentTimeMillis() < deadline
        ) {
            delay(10)
        }
    }

    private fun pickSampleRate(): Pair<Int, Int>? {
        var sampleRate = TARGET_SAMPLE_RATE
        var minBuf = tryMinBuffer(sampleRate)
        if (minBuf == null) {
            for (rate in FALLBACK_RATES) {
                minBuf = tryMinBuffer(rate)
                if (minBuf != null) {
                    sampleRate = rate
                    break
                }
            }
        }
        return minBuf?.let { sampleRate to it }
    }

    private fun stopWarmup() {
        warmupJob?.cancel()
        warmupJob = null
        synchronized(audioLock) {
            warmupRecord?.let { rec ->
                try {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                } catch (_: Exception) {
                }
                try {
                    rec.release()
                } catch (_: Exception) {
                }
            }
            warmupRecord = null
        }
    }

    private fun releaseWarmup(rec: AudioRecord?) {
        synchronized(audioLock) {
            if (rec == null || warmupRecord !== rec) return
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
            warmupRecord = null
        }
    }

    private fun releaseAudio() {
        synchronized(audioLock) {
            audioRecord?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            audioRecord = null
        }
    }

    private fun tryMinBuffer(sampleRate: Int): Int? {
        return try {
            val size = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
            if (size <= 0) null else size
        } catch (_: Exception) {
            null
        }
    }

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
