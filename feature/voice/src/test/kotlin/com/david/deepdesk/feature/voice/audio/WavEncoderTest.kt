package com.david.deepdesk.feature.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WAV 编码与重采样回归（AT-08/AT-09 音频链路）。
 */
class WavEncoderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pcmBytes(shorts: ShortArray): ByteArray {
        val out = ByteArray(shorts.size * 2)
        shorts.forEachIndexed { i, v ->
            out[i * 2] = (v.toInt() and 0xff).toByte()
            out[i * 2 + 1] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `WAV 头字段与时长正确`() {
        val file = tmp.newFile("t.wav")
        val writer = WavWriter(file, 16000, 1)
        writer.open()
        writer.appendPcm(pcmBytes(ShortArray(16000))) // 1 秒
        writer.appendPcm(pcmBytes(ShortArray(16000))) // 又 1 秒
        writer.close()

        val bytes = file.readBytes()
        // RIFF
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        // data 长度 = 64000 bytes
        val dataLen = ((bytes[40].toInt() and 0xff) or
            ((bytes[41].toInt() and 0xff) shl 8) or
            ((bytes[42].toInt() and 0xff) shl 16) or
            ((bytes[43].toInt() and 0xff) shl 24)).toLong()
        assertEquals(64000L, dataLen)
        // RIFF 总长 = 36 + data
        val riffLen = ((bytes[4].toInt() and 0xff) or
            ((bytes[5].toInt() and 0xff) shl 8) or
            ((bytes[6].toInt() and 0xff) shl 16) or
            ((bytes[7].toInt() and 0xff) shl 24)).toLong()
        assertEquals(36L + 64000L, riffLen)
        assertEquals(2000L, writer.durationMs)
    }

    @Test
    fun `不足一秒的时长按字节换算`() {
        val file = tmp.newFile("short.wav")
        val writer = WavWriter(file, 16000, 1)
        writer.open()
        writer.appendPcm(pcmBytes(ShortArray(3200))) // 200ms
        writer.close()
        assertEquals(200L, writer.durationMs)
        assertEquals(6400L, writer.pcmByteCount)
    }

    @Test
    fun `降采样长度与采样范围`() {
        val input = ShortArray(48000) { i -> (i % 1000).toShort() }
        val out = Resampler.downsample(input, 48000, 16000)
        assertEquals(16000, out.size)
        out.forEach { v -> assertTrue(v in Short.MIN_VALUE..Short.MAX_VALUE) }
    }

    @Test
    fun `振幅归一化`() {
        assertEquals(0f, Resampler.amplitude(ShortArray(100)), 0.0001f)
        val loud = ShortArray(100) { Short.MAX_VALUE }
        assertTrue(Resampler.amplitude(loud) > 0.99f)
    }
}
