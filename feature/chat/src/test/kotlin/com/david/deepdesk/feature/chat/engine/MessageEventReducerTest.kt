package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.SseEvent
import com.david.deepdesk.core.model.StopReason
import com.david.deepdesk.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reducer 回归：完整事件流、思考块、工具块、拒答、断流语义（AT-03/AT-06/AT-14/AT-22）。
 */
class MessageEventReducerTest {

    private fun event(json: String): SseEvent =
        ApiJson.decodeFromString(SseEvent.serializer(), json)

    private fun feed(reducer: MessageEventReducer, vararg jsonEvents: String) {
        jsonEvents.forEach { reducer.onEvent(event(it)) }
    }

    @Test
    fun `完整文本流累积为正文块`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"message_start","message":{"id":"m1","model":"deepseek-flash","usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，世界"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}""",
            """{"type":"message_stop"}""",
        )
        val snap = reducer.snapshot()
        assertTrue(snap.isComplete)
        assertEquals(StopReason.END_TURN, snap.stopReason)
        assertEquals(1, snap.blocks.size)
        val text = snap.blocks[0] as MessageEventReducer.DraftBlock.Text
        assertEquals("你好，世界", text.text.toString())
    }

    @Test
    fun `思考块增量与签名保留`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先分析"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"需求"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"答案"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_stop"}""",
        )
        val snap = reducer.snapshot()
        assertEquals(2, snap.blocks.size)
        val thinking = snap.blocks[0] as MessageEventReducer.DraftBlock.Thinking
        assertEquals("先分析需求", thinking.text.toString())
        assertEquals("sig123", thinking.signature)
        assertTrue(thinking.closed)
        assertNotNull(thinking.durationMs)
        assertTrue(thinking.durationMs!! >= 0L)
    }

    @Test
    fun `联网搜索工具块与引用`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"t1","name":"deepseek_web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"action\":\"search\",\"query\":\"AI 新闻\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"t1","content":[{"type":"web_search_result","title":"A","url":"https://a.com","encrypted_content":"x"},{"type":"web_search_result","title":"B","url":"https://b.com","encrypted_content":"y"}]}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://a.com","title":"A","encrypted_index":"e1","cited_text":"片段"}}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_stop"}""",
        )
        val snap = reducer.snapshot()
        val tool = snap.blocks[0] as MessageEventReducer.DraftBlock.ServerToolUse
        assertEquals("search", tool.action)
        assertEquals("AI 新闻", tool.query)
        val result = snap.blocks[1] as MessageEventReducer.DraftBlock.WebSearchResult
        assertEquals(2, result.hits.size)
        assertEquals("https://a.com", result.hits[0].url)
        val text = snap.blocks[2] as MessageEventReducer.DraftBlock.Text
        assertEquals(1, text.citations.size)
        assertEquals("https://a.com", text.citations[0].url)
    }

    @Test
    fun `搜索结果错误码正确记录`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"content_block_start","index":0,"content_block":{"type":"web_search_tool_result","tool_use_id":"t1","content":{"type":"web_search_tool_result_error","error_code":"too_many_requests"}}}""",
        )
        val result = reducer.snapshot().blocks[0] as MessageEventReducer.DraftBlock.WebSearchResult
        assertEquals("too_many_requests", result.errorCode)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `拒答 stop_reason 触发 refusal`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"部分内容…"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null,"stop_details":{"type":"refusal","category":null,"explanation":null}}}""",
            """{"type":"message_stop"}""",
        )
        val snap = reducer.snapshot()
        assertEquals(StopReason.REFUSAL, snap.stopReason)
        assertNotNull(snap.refusal)
    }

    @Test
    fun `断流无 message_stop 标记 interrupted 且保留已收内容`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半截"}}""",
        )
        // 模拟 MessageStreamClient 网络断流时发出的程序化载体（不经 JSON 反序列化）
        reducer.onEvent(SseEvent.Unknown("__interrupted__", ""))
        val snap = reducer.snapshot()
        assertTrue(!snap.isComplete)
        assertNotNull(snap.failure)
        assertTrue(snap.failure!!.interrupted)
        assertEquals("半截", (snap.blocks[0] as MessageEventReducer.DraftBlock.Text).text.toString())
    }

    @Test
    fun `HTTP 错误载体解析出状态码`() {
        val reducer = MessageEventReducer()
        val carrier = ApiJson.encodeToString(
            HttpCarrier.serializer(),
            HttpCarrier(status = 401, type = "authentication_error", message = "bad key"),
        )
        reducer.onEvent(SseEvent.Unknown("__http_error__", carrier))
        val snap = reducer.snapshot()
        assertNotNull(snap.failure)
        assertEquals(401, snap.failure!!.httpStatus)
        assertEquals("authentication_error", snap.failure!!.errorType)
    }

    @Test
    fun `心跳与未知事件不破坏状态`() {
        val reducer = MessageEventReducer()
        feed(
            reducer,
            """{"type":"ping"}""",
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_stop"}""",
        )
        assertTrue(reducer.snapshot().isComplete)
    }
}

@kotlinx.serialization.Serializable
private data class HttpCarrier(
    val status: Int?,
    val type: String?,
    val message: String?,
)
