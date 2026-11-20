package com.david.deepdesk.feature.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 分段回归（§3.5：单次 ≤10,000 计费字符）。
 */
class TtsSegmenterTest {

    @Test
    fun `短文本不分段`() {
        val out = TtsPlayer.segmentText("你好，我是 DeepSeek。")
        assertEquals(1, out.size)
        assertEquals("你好，我是 DeepSeek。", out[0])
    }

    @Test
    fun `空白文本返回空`() {
        assertTrue(TtsPlayer.segmentText("   ").isEmpty())
    }

    @Test
    fun `超长文本按段落切分且不超限`() {
        val paragraph = "这是一个测试段落。".repeat(200) // 1600 chars
        val text = (1..8).joinToString("\n\n") { paragraph }
        assertTrue(text.length > 9000)
        val segments = TtsPlayer.segmentText(text)
        assertTrue(segments.size > 1)
        segments.forEach { seg -> assertTrue(seg.length <= 9100) }
        // 内容无丢失（总长度近似守恒）
        val joined = segments.joinToString("") { it }
        assertEquals(text.replace("\n\n", "").length, joined.replace("\n\n", "").length)
    }

    @Test
    fun `单段超限按句子切`() {
        val text = "句子。".repeat(3000) // 9000+ chars, no paragraphs
        val segments = TtsPlayer.segmentText(text, maxChars = 1000)
        assertTrue(segments.size > 1)
        segments.forEach { seg -> assertTrue(seg.length <= 1100) }
    }
}
