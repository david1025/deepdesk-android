package com.dsh.deepseek.feature.voice.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV（16-bit PCM）写头与数据追加。
 * 流式写入：录音期间持续追加 PCM，结束时回填 RIFF/data 长度字段。
 */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int = 1) {

    private var dataBytes = 0L
    private var raf: RandomAccessFile? = null

    /** 打开文件并写入 44 字节占位头 */
    fun open() {
        raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(buildHeader(0L))
        }
        dataBytes = 0
    }

    /** 追加 PCM 数据（16-bit little-endian） */
    fun appendPcm(pcm: ByteArray) {
        val r = raf ?: return
        r.write(pcm)
        dataBytes += pcm.size
    }

    /** 结束并回填长度字段；调用后文件即合法 WAV */
    fun close(): File {
        raf?.let { r ->
            r.seek(0)
            r.write(buildHeader(dataBytes))
            r.close()
        }
        raf = null
        return file
    }

    fun abort() {
        raf?.close()
        raf = null
        file.delete()
    }

    val pcmByteCount: Long get() = dataBytes

    val durationMs: Long
        get() {
            val bytesPerSec = sampleRate.toLong() * channels * 2
            if (bytesPerSec <= 0) return 0
            return dataBytes * 1000L / bytesPerSec
        }

    /** 44 字节标准 WAV 头 */
    private fun buildHeader(dataLen: Long): ByteArray {
        val totalLen = 36 + dataLen
        val out = ByteArray(44)
        fun putInt(offset: Int, value: Long) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
            out[offset + 2] = ((value shr 16) and 0xff).toByte()
            out[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        // RIFF header
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putInt(4, totalLen)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte(); out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        // fmt chunk
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte(); out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        putInt(16, 16)               // fmt chunk size
        putShort(20, 1)              // PCM
        putShort(22, channels)
        putInt(24, sampleRate.toLong())
        putInt(28, sampleRate.toLong() * channels * 2) // byte rate
        putShort(32, channels * 2)   // block align
        putShort(34, 16)             // bits per sample
        // data chunk
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte(); out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        putInt(40, dataLen)
        return out
    }
}

/** 线性插值降采样（48k → 16k 等）；仅用于设备不支持目标采样率时的兜底 */
object Resampler {

    fun downsample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        var pos = 0.0
        for (i in out.indices) {
            val idx = pos.toInt()
            val frac = pos - idx
            val s0 = input[idx].toDouble()
            val s1 = input.getOrElse(idx + 1) { input[idx] }.toDouble()
            out[i] = ((s0 * (1 - frac) + s1 * frac)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += ratio
        }
        return out
    }

    /** RMS → 0..1 音量（波形展示用） */
    fun amplitude(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        val rms = kotlin.math.sqrt(sum / pcm.size)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
