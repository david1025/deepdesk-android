package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.ApiMessage
import com.david.deepdesk.core.model.ChatMessage
import com.david.deepdesk.core.model.ChatModels
import com.david.deepdesk.core.model.FileRefBlock
import com.david.deepdesk.core.model.ImageFileBlock
import com.david.deepdesk.core.model.ImageFileSource
import com.david.deepdesk.core.model.MessageRequest
import com.david.deepdesk.core.model.OutputConfig
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.SafetyCheck
import com.david.deepdesk.core.model.TextBlock
import com.david.deepdesk.core.model.ThinkingConfig
import com.david.deepdesk.core.model.TitleConfig
import com.david.deepdesk.core.model.ToolSpec
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

    const val MODEL = ChatModels.FLASH
    const val SYSTEM_PROMPT =
        "你是由 DeepSeek 模型提供能力的 AI 助手。本应用是独立第三方客户端。"

    data class Options(
        val thinkingEnabled: Boolean,
        val webSearchEnabled: Boolean,
        val readUrlEnabled: Boolean,
        /** 第一轮对话为 true（F10 自动标题） */
        val titleEnabled: Boolean,
        /** 默认 Flash。专业模式才换成用户选的模型。 */
        val model: String = MODEL,
        /**
         * 专业模式且深度思考开启时为 low / high / max。
         * null 不写 output_config，请求与未开专业模式时一致。
         */
        val reasoningEffort: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 历史 assistant 消息仅回传正文文本（thinking 不回传） */
    private fun textOf(messageId: String, blocksByMessageId: Map<String, List<ReplyBlock>>): String =
        blocksByMessageId[messageId].orEmpty()
            .filterIsInstance<ReplyBlock.Text>()
            .joinToString("\n") { it.text }

    /** 用户消息 content：无附件时为纯字符串；带附件时为 [文本块 + 图片块或 file 引用块] 数组 */
    private fun userContent(text: String, fileIds: List<String>, imageFileIds: Set<String>) =
        if (fileIds.isEmpty()) {
            JsonPrimitive(text)
        } else {
            buildJsonArray {
                if (text.isNotBlank()) {
                    add(json.encodeToJsonElement(TextBlock.serializer(), TextBlock(text = text)))
                }
                fileIds.forEach { id ->
                    if (id in imageFileIds) {
                        add(
                            json.encodeToJsonElement(
                                ImageFileBlock.serializer(),
                                ImageFileBlock(source = ImageFileSource(fileId = id)),
                            ),
                        )
                    } else {
                        add(json.encodeToJsonElement(FileRefBlock.serializer(), FileRefBlock(fileId = id)))
                    }
                }
            }
        }

    fun build(
        history: List<ChatMessage>,
        blocksByMessageId: Map<String, List<ReplyBlock>>,
        attachmentsByMessageId: Map<String, List<String>>,
        newMessageText: String,
        newMessageFileIds: List<String>,
        /** 这些 file_id 是图片，按 Anthropic image.source.file 发送。 */
        imageFileIds: Set<String> = emptySet(),
        options: Options,
        /** false = 历史已包含末尾用户消息（重新生成场景），不追加新消息 */
        appendUserMessage: Boolean = true,
        /** 继续生成：把已写出的正文作为 assistant 前缀，模型接着写。 */
        assistantPrefill: String? = null,
    ): MessageRequest {
        val messages = buildList {
            history.forEach { msg ->
                val fileIds = attachmentsByMessageId[msg.id].orEmpty()
                if (msg.role == "user") {
                    add(ApiMessage("user", userContent(textOf(msg.id, blocksByMessageId), fileIds, imageFileIds)))
                } else {
                    val text = textOf(msg.id, blocksByMessageId)
                    if (text.isNotBlank()) add(ApiMessage.assistant(text))
                }
            }
            if (appendUserMessage) {
                add(ApiMessage("user", userContent(newMessageText, newMessageFileIds, imageFileIds)))
            }
            if (!assistantPrefill.isNullOrBlank()) {
                add(ApiMessage.assistant(assistantPrefill))
            }
        }

        val tools = buildList {
            if (options.webSearchEnabled) add(ToolSpec.WEB_SEARCH)
            if (options.readUrlEnabled) add(ToolSpec.READ_URL)
        }

        val effort = options.reasoningEffort?.let(ChatModels::sanitizeEffort)
        return MessageRequest(
            model = ChatModels.sanitize(options.model),
            system = SYSTEM_PROMPT,
            messages = messages,
            thinking = if (options.thinkingEnabled) ThinkingConfig.ENABLED else ThinkingConfig.DISABLED,
            tools = tools.ifEmpty { null },
            safetyCheck = SafetyCheck.HIGH,
            title = if (options.titleEnabled) TitleConfig(true) else null,
            stream = true,
            outputConfig = effort?.let { OutputConfig(effort = it) },
        )
    }

    /** 用户消息中的 URL 是否存在（读链接由服务端工具触发） */
    fun containsUrl(text: String): Boolean =
        Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+").containsMatchIn(text)
}
