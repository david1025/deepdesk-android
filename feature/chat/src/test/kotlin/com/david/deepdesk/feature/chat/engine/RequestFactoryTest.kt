package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.ApiMessage
import com.david.deepdesk.core.model.ChatMessage
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.network.ApiJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求构造回归：模型/系统提示/开关/附件引用/标题轮次（§5.2）。
 */
class RequestFactoryTest {

    private fun userMsg(id: String, text: String, parent: String? = null) = ChatMessage(
        id = id, sessionId = "s", role = "user", parentId = parent, createdAt = 1,
    )

    private fun assistantMsg(id: String, parent: String) = ChatMessage(
        id = id, sessionId = "s", role = "assistant", parentId = parent, createdAt = 2,
    )

    @Test
    fun `基础消息序列包含系统提示与模型`() {
        val history = listOf(
            userMsg("m1", "你好"),
            assistantMsg("m2", parent = "m1"),
        )
        val req = RequestFactory.build(
            history = history,
            blocksByMessageId = mapOf(
                "m1" to listOf(ReplyBlock.Text("你好")),
                "m2" to listOf(ReplyBlock.Text("你好！有什么可以帮你？")),
            ),
            attachmentsByMessageId = emptyMap(),
            newMessageText = "继续",
            newMessageFileIds = emptyList(),
            options = RequestFactory.Options(
                thinkingEnabled = false,
                webSearchEnabled = false,
                readUrlEnabled = false,
                titleEnabled = false,
            ),
        )
        assertEquals(RequestFactory.MODEL, req.model)
        assertEquals(RequestFactory.SYSTEM_PROMPT, req.system)
        // 历史 2 条 + 新消息 1 条
        assertEquals(3, req.messages.size)
        assertEquals("你好", req.messages[0].content.jsonPrimitive.content)
        assertEquals("你好！有什么可以帮你？", req.messages[1].content.jsonPrimitive.content)
        assertEquals("继续", req.messages[2].content.jsonPrimitive.content)
        assertNull(req.tools)
        assertTrue(req.safetyCheck!!.level == "high")
        assertNull(req.title)
    }

    @Test
    fun `带附件的用户消息为块数组`() {
        val history = listOf(userMsg("m1", "看图"))
        val req = RequestFactory.build(
            history = history,
            blocksByMessageId = mapOf("m1" to listOf(ReplyBlock.Text("看图"))),
            attachmentsByMessageId = mapOf("m1" to listOf("file-api-1", "file-api-2")),
            newMessageText = "",
            newMessageFileIds = emptyList(),
            options = RequestFactory.Options(false, false, false, false),
        )
        val arr = req.messages[0].content.jsonArray
        // 文本块 + 2 个 file 引用块
        assertEquals(3, arr.size)
        assertEquals("text", arr[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("file", arr[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("file-api-1", arr[1].jsonObject["file_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `图片附件走 image source file_id`() {
        val req = RequestFactory.build(
            history = emptyList(),
            blocksByMessageId = emptyMap(),
            attachmentsByMessageId = emptyMap(),
            newMessageText = "这是什么",
            newMessageFileIds = listOf("file-api-img", "file-api-pdf"),
            imageFileIds = setOf("file-api-img"),
            options = RequestFactory.Options(false, false, false, false),
        )
        val arr = req.messages[0].content.jsonArray
        assertEquals("text", arr[0].jsonObject["type"]?.jsonPrimitive?.content)
        val image = arr[1].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        val source = image["source"]!!.jsonObject
        assertEquals("file", source["type"]?.jsonPrimitive?.content)
        assertEquals("file-api-img", source["file_id"]?.jsonPrimitive?.content)
        assertEquals("file", arr[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("file-api-pdf", arr[2].jsonObject["file_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具开关生成 tools 数组`() {
        val req = RequestFactory.build(
            history = emptyList(),
            blocksByMessageId = emptyMap(),
            attachmentsByMessageId = emptyMap(),
            newMessageText = "搜索今天的新闻",
            newMessageFileIds = emptyList(),
            options = RequestFactory.Options(
                thinkingEnabled = true,
                webSearchEnabled = true,
                readUrlEnabled = true,
                titleEnabled = true,
            ),
        )
        assertTrue(req.thinking!!.type == "enabled")
        val tools = req.tools!!
        assertEquals(2, tools.size)
        assertEquals("deepseek_web_search", tools[0].name)
        assertEquals("deepseek_read_url", tools[1].name)
        assertTrue(req.title!!.enabled)
        assertTrue(req.stream)
    }

    @Test
    fun `重新生成场景不追加空用户消息`() {
        val history = listOf(
            userMsg("m1", "问题"),
            assistantMsg("m2", parent = "m1"),
        )
        val req = RequestFactory.build(
            history = listOf(userMsg("m1", "问题")),
            blocksByMessageId = mapOf("m1" to listOf(ReplyBlock.Text("问题"))),
            attachmentsByMessageId = emptyMap(),
            newMessageText = "",
            newMessageFileIds = emptyList(),
            options = RequestFactory.Options(false, false, false, false),
            appendUserMessage = false,
        )
        assertEquals(1, req.messages.size)
    }

    @Test
    fun `继续生成把已写出的正文作为 assistant 前缀`() {
        val history = listOf(userMsg("m1", "讲个故事"))
        val req = RequestFactory.build(
            history = history,
            blocksByMessageId = mapOf("m1" to listOf(ReplyBlock.Text("讲个故事"))),
            attachmentsByMessageId = emptyMap(),
            newMessageText = "",
            newMessageFileIds = emptyList(),
            options = RequestFactory.Options(false, false, false, false),
            appendUserMessage = false,
            assistantPrefill = "从前有座山",
        )
        assertEquals(2, req.messages.size)
        assertEquals("user", req.messages[0].role)
        assertEquals("assistant", req.messages[1].role)
        assertEquals("从前有座山", req.messages[1].content.jsonPrimitive.content)
        assertTrue(req.thinking!!.type == "disabled")
    }

    @Test
    fun `继续生成拼接不重复已有正文`() {
        val prefix = listOf(
            ReplyBlock.Thinking("想了一下"),
            ReplyBlock.Text("从前有座山"),
        )
        val continued = mergeContinuation(prefix, listOf(ReplyBlock.Text("，山上有座庙")))
        assertEquals(2, continued.size)
        assertTrue(continued[0] is ReplyBlock.Thinking)
        assertEquals("从前有座山，山上有座庙", (continued[1] as ReplyBlock.Text).text)

        val echoed = mergeContinuation(prefix, listOf(ReplyBlock.Text("从前有座山，山上有座庙")))
        assertEquals(2, echoed.size)
        assertTrue(echoed[0] is ReplyBlock.Thinking)
        assertEquals("从前有座山，山上有座庙", (echoed[1] as ReplyBlock.Text).text)

        val fresh = mergeContinuation(prefix, listOf(ReplyBlock.Text("完全另一段")))
        assertEquals("从前有座山完全另一段", (fresh.last() as ReplyBlock.Text).text)
    }

    @Test
    fun `URL 检测`() {
        assertTrue(RequestFactory.containsUrl("看看 https://example.com/a?b=1 说了什么"))
        assertFalse(RequestFactory.containsUrl("普通消息，没有链接"))
    }
}
