package com.dsh.deepseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解码器回归（AT-22）：跨包断行、心跳注释、多行 data、CRLF 混排。
 */
class SseDecoderTest {

    @Test
    fun `单事件解析`() {
        val text = """
            event: message
            data: {"type":"message_stop"}

        """.trimIndent()
        val events = SseDecoder.parseAll(text)
        assertEquals(1, events.size)
        assertEquals("""{"type":"message_stop"}""", events[0])
    }

    @Test
    fun `心跳注释行被忽略`() {
        val text = ": ping\n\n" +
            ": keep-alive\n" +
            "data: {\"a\":1}\n\n"
        val events = SseDecoder.parseAll(text)
        assertEquals(1, events.size)
        assertEquals("""{"a":1}""", events[0])
    }

    @Test
    fun `多行 data 以换行连接`() {
        val text = "data: line1\ndata: line2\n\n"
        val events = SseDecoder.parseAll(text)
        assertEquals(listOf("line1\nline2"), events)
    }

    @Test
    fun `CRLF 与 CR 混排`() {
        val text = "data: a\r\ndata: b\r\rdata: c\n"
        val events = SseDecoder.parseAll(text)
        // \r\n 与孤立 \r 均为行终止；空行触发分帧 → 2 个事件
        assertEquals(2, events.size)
        assertEquals("a\nb", events[0])
        assertEquals("c", events[1])
    }

    @Test
    fun `流结束未跟随空行也能 flush`() {
        val decoder = SseDecoder()
        val out = mutableListOf<String>()
        decoder.push("data: partial")?.let(out::add)
        assertTrue(out.isEmpty())
        decoder.flush()?.let(out::add)
        assertEquals(listOf("partial"), out)
    }

    @Test
    fun `连续多帧解析`() {
        val text = "data: one\n\ndata: two\n\ndata: three\n\n"
        val events = SseDecoder.parseAll(text)
        assertEquals(listOf("one", "two", "three"), events)
    }

    @Test
    fun `SSE 事件按协议类型反序列化`() {
        val json = ApiJson
        val event = json.decodeFromString(
            MessageStreamClient.SseEventSerializer,
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
        )
        assertTrue(event is com.dsh.deepseek.core.model.SseEvent.ContentBlockDelta)
    }

    @Test
    fun `未知事件类型在解析层降级而不破坏流`() {
        // sealed 反序列化未知 discriminator 抛 SerializationException；
        // MessageStreamClient.parseEvent 捕获后降级为 Unknown——此处验证序列化器行为契约
        var threw = false
        try {
            ApiJson.decodeFromString(
                MessageStreamClient.SseEventSerializer,
                """{"type":"future_event","x":1}""",
            )
        } catch (e: kotlinx.serialization.SerializationException) {
            threw = true
        }
        assertTrue("未知 discriminator 应抛 SerializationException 由上层兜底", threw)
    }
}
