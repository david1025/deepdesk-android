package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.ChatMessage
import com.david.deepdesk.core.model.ChatModels
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.ReplyStatus
import com.david.deepdesk.core.model.SseEvent
import com.david.deepdesk.core.network.ApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebToolHistoryTest {
    @Test
    fun `completed tool reply is sent in order with switches off`() {
        val thinking = """{"type":"thinking","thinking":"plan","signature":"thinking-signature"}"""
        val search = """{"type":"server_tool_use","id":"search-1","name":"deepseek_web_search","input":{"action":"search","queries":["one","two"]},"kind":"deep_search","caller":{"type":"direct"}}"""
        val searchResult = """{"type":"web_search_tool_result","tool_use_id":"search-1","kind":"deep_search","content":[{"type":"web_search_result","url":"https://example.com","encrypted_content":"opaque-search-result"}]}"""
        val read = """{"type":"server_tool_use","id":"read-1","name":"deepseek_read_url","input":{"action":"read_link","url":"https://example.com/article"},"kind":"read_link","caller":{"type":"direct"}}"""
        val page = """{"type":"web_fetch_tool_result","tool_use_id":"read-1","kind":"read_link","signature":"opaque-page-signature","content":{"type":"web_fetch_result","url":"https://example.com/article","content":{"type":"document","source":{"type":"text","media_type":"text/plain","data":""},"title":"Article"}}}"""
        val citation = """{"type":"web_search_result_location","url":"https://example.com/article","encrypted_index":"","cited_text":"Answer"}"""
        val reducer = MessageEventReducer()
        reducer.onEvent(SseEvent.ContentBlockStart(0, Json.parseToJsonElement("""{"type":"thinking","thinking":"","signature":""}""")))
        reducer.onEvent(SseEvent.ContentBlockDelta(0, Json.parseToJsonElement("""{"type":"thinking_delta","thinking":"plan"}""")))
        reducer.onEvent(SseEvent.ContentBlockDelta(0, Json.parseToJsonElement("""{"type":"signature_delta","signature":"thinking-signature"}""")))
        reducer.onEvent(SseEvent.ContentBlockStop(0))
        reducer.onEvent(SseEvent.ContentBlockStart(1, Json.parseToJsonElement(search.replace("""{"action":"search","queries":["one","two"]}""", "{}"))))
        reducer.onEvent(SseEvent.ContentBlockDelta(1, Json.parseToJsonElement("""{"type":"input_json_delta","partial_json":"{\"action\":\"search\","}""")))
        reducer.onEvent(SseEvent.ContentBlockDelta(1, Json.parseToJsonElement("""{"type":"input_json_delta","partial_json":"\"queries\":[\"one\",\"two\"]}"}""")))
        reducer.onEvent(SseEvent.ContentBlockStop(1))
        listOf(searchResult, read, page).forEachIndexed { position, block ->
            val index = position + 2
            reducer.onEvent(SseEvent.ContentBlockStart(index, Json.parseToJsonElement(block)))
            reducer.onEvent(SseEvent.ContentBlockStop(index))
        }
        reducer.onEvent(SseEvent.ContentBlockStart(5, Json.parseToJsonElement("""{"type":"text","text":""}""")))
        reducer.onEvent(SseEvent.ContentBlockDelta(5, Json.parseToJsonElement("""{"type":"text_delta","text":"Answer"}""")))
        reducer.onEvent(SseEvent.ContentBlockDelta(5, Json.parseToJsonElement("""{"type":"citations_delta","citation":$citation}""")))
        reducer.onEvent(SseEvent.ContentBlockStop(5))
        reducer.onEvent(SseEvent.MessageStop())

        val stored = requireNotNull(reducer.completedWebToolHistory())
        val oldPage = ReplyBlock.WebFetchResult("read-1", url = "https://example.com/article")
        assertEquals(listOf(oldPage.copy(title = "Article")), WebToolHistory.displayBlocks(listOf(oldPage, stored)))
        val history = listOf(ReplyBlock.Text("Answer"), ReplyBlock.Raw(stored.type, stored.rawJson))
        val request = followUp(history)
        assertNull(request.tools)
        val expected = Json.parseToJsonElement(
            """[$thinking,$search,$searchResult,$read,$page,{"type":"text","text":"Answer","citations":[$citation]}]""",
        )
        assertEquals(expected, request.messages[1].content)
        assertEquals(JsonPrimitive("Quote the first sentence"), request.messages[2].content)
        assertEquals(JsonPrimitive("Answer"), followUp(history, ChatModels.PRO).messages[1].content)
        assertEquals(JsonPrimitive("Answer"), followUp(history, status = ReplyStatus.STOPPED).messages[1].content)
        assertEquals(JsonPrimitive("Answer"), followUp(listOf(ReplyBlock.Text("Answer"))).messages[1].content)
    }

    @Test
    fun `partial or unpaired tool stream is never replayed`() {
        val reducer = MessageEventReducer()
        reducer.onEvent(
            ApiJson.decodeFromString(
                SseEvent.serializer(),
                """{"type":"content_block_start","index":0,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"missing","kind":"read_link","signature":"partial","content":{}}}""",
            ),
        )
        assertNull(reducer.completedWebToolHistory())
        reducer.onEvent(SseEvent.ContentBlockStop(0))
        reducer.onEvent(SseEvent.MessageStop())
        assertNull(reducer.completedWebToolHistory())
    }

    private fun followUp(
        blocks: List<ReplyBlock>,
        model: String = ChatModels.FLASH,
        status: ReplyStatus = ReplyStatus.DONE,
    ) = RequestFactory.build(
        history = listOf(
            ChatMessage("user", "session", "user"),
            ChatMessage("assistant", "session", "assistant", parentId = "user", status = status),
        ),
        blocksByMessageId = mapOf(
            "user" to listOf(ReplyBlock.Text("Search, then read https://example.com/article")),
            "assistant" to blocks,
        ),
        attachmentsByMessageId = emptyMap(),
        newMessageText = "Quote the first sentence",
        newMessageFileIds = emptyList(),
        options = RequestFactory.Options(false, false, false, false, model),
    )
}
