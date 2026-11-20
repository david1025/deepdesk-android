package com.dsh.deepseek.feature.chat.engine

import com.dsh.deepseek.core.model.ApiMessage
import com.dsh.deepseek.core.model.ChatMessage
import com.dsh.deepseek.core.model.FileRefBlock
import com.dsh.deepseek.core.model.MessageRequest
import com.dsh.deepseek.core.model.ReplyBlock
import com.dsh.deepseek.core.model.SafetyCheck
import com.dsh.deepseek.core.model.TextBlock
import com.dsh.deepseek.core.model.ThinkingConfig
import com.dsh.deepseek.core.model.TitleConfig
import com.dsh.deepseek.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 统一请求构造器（§5.2）：模型、开关、附件引用、分支历史、审核与标题规则
 * 全部由这里生成，保证全应用口径一致。
 */
object RequestFactory {

    const val MODEL = "deepseek-flash"
    const val SYSTEM_PROMPT =
        "你是由 DeepSeek 模型提供能力的 AI 助手。本应用是独立第三方客户端。"

    data class Options(
        val thinkingEnabled: Boolean,
        val webSearchEnabled: Boolean,
        val readUrlEnabled: Boolean,
        /** 第一轮对话为 true（F10 自动标题） */
        val titleEnabled: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 历史 assistant 消息仅回传正文文本（thinking 不回传） */
    private fun textOf(messageId: String, blocksByMessageId: Map<String, List<ReplyBlock>>): String =
        blocksByMessageId[messageId].orEmpty()
            .filterIsInstance<ReplyBlock.Text>()
            .joinToString("\n") { it.text }

    /** 用户消息 content：无附件时为纯字符串；带附件时为 [文本块 + file 引用块] 数组 */
    private fun userContent(text: String, fileIds: List<String>) =
        if (fileIds.isEmpty()) {
            JsonPrimitive(text)
        } else {
            buildJsonArray {
                if (text.isNotBlank()) {
                    add(json.encodeToJsonElement(TextBlock.serializer(), TextBlock(text = text)))
                }
                fileIds.forEach { id ->
                    add(json.encodeToJsonElement(FileRefBlock.serializer(), FileRefBlock(fileId = id)))
                }
            }
        }

    fun build(
        history: List<ChatMessage>,
        blocksByMessageId: Map<String, List<ReplyBlock>>,
        attachmentsByMessageId: Map<String, List<String>>,
        newMessageText: String,
        newMessageFileIds: List<String>,
        options: Options,
        /** false = 历史已包含末尾用户消息（重新生成场景），不追加新消息 */
        appendUserMessage: Boolean = true,
    ): MessageRequest {
        val messages = buildList {
            history.forEach { msg ->
                val fileIds = attachmentsByMessageId[msg.id].orEmpty()
                if (msg.role == "user") {
                    add(ApiMessage("user", userContent(textOf(msg.id, blocksByMessageId), fileIds)))
                } else {
                    val text = textOf(msg.id, blocksByMessageId)
                    if (text.isNotBlank()) add(ApiMessage.assistant(text))
                }
            }
            if (appendUserMessage) {
                add(ApiMessage("user", userContent(newMessageText, newMessageFileIds)))
            }
        }

        val tools = buildList {
            if (options.webSearchEnabled) add(ToolSpec.WEB_SEARCH)
            if (options.readUrlEnabled) add(ToolSpec.READ_URL)
        }

        return MessageRequest(
            model = MODEL,
            system = SYSTEM_PROMPT,
            messages = messages,
            thinking = if (options.thinkingEnabled) ThinkingConfig.ENABLED else ThinkingConfig.DISABLED,
            tools = tools.ifEmpty { null },
            safetyCheck = SafetyCheck.HIGH,
            title = if (options.titleEnabled) TitleConfig(true) else null,
            stream = true,
        )
    }

    /** 用户消息中的 URL 是否存在（读链接由服务端工具触发） */
    fun containsUrl(text: String): Boolean =
        Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+").containsMatchIn(text)
}
