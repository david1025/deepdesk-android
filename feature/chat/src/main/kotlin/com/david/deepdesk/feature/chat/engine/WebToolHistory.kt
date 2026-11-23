package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.ReplyBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Assembles Messages API content independently of the lossy blocks used for display. */
internal class WebToolHistory {
    private val blocks = sortedMapOf<Int, ContentBlock>()

    fun start(index: Int, content: JsonObject) {
        blocks[index] = ContentBlock(content)
    }

    fun delta(index: Int, delta: JsonObject) {
        val block = blocks[index] ?: return
        when (delta.string("type")) {
            "text_delta" -> block.text.append(delta.string("text").orEmpty())
            "thinking_delta" -> block.text.append(delta.string("thinking").orEmpty())
            "signature_delta" -> block.signature.append(delta.string("signature").orEmpty())
            "input_json_delta" -> block.input.append(delta.string("partial_json").orEmpty())
            "citations_delta" -> (delta["citation"] as? JsonObject)?.let(block.citations::add)
        }
    }

    fun stop(index: Int) {
        blocks[index]?.closed = true
    }

    fun toStoredBlock(): ReplyBlock.Raw? {
        val results = blocks.values.filter { it.type in resultTypes }
        if (results.isEmpty() || blocks.values.any { !it.closed }) return null
        val calls = blocks.values.filter { it.type == "server_tool_use" }
        val callIds = calls.mapNotNull { it.initial.string("id")?.takeIf(String::isNotBlank) }
        val resultIds = results.mapNotNull { it.initial.string("tool_use_id")?.takeIf(String::isNotBlank) }
        if (callIds.size != calls.size || resultIds.size != results.size ||
            callIds.sorted() != resultIds.sorted()
        ) return null

        val content = blocks.values.map { it.toJson() ?: return null }
        return ReplyBlock.Raw(BLOCK_TYPE, JsonArray(content).toString())
    }

    private class ContentBlock(val initial: JsonObject) {
        val type = initial.string("type")
        val text = StringBuilder()
        val signature = StringBuilder()
        val input = StringBuilder()
        val citations = mutableListOf<JsonObject>()
        var closed = false

        fun toJson(): JsonObject? {
            val content = initial.toMutableMap()
            when (type) {
                "text" -> {
                    if (text.isNotEmpty()) content["text"] = JsonPrimitive(initial.string("text").orEmpty() + text)
                    if (citations.isNotEmpty()) {
                        content["citations"] = JsonArray((initial["citations"] as? JsonArray).orEmpty() + citations)
                    }
                }
                "thinking" -> {
                    if (text.isNotEmpty()) content["thinking"] = JsonPrimitive(initial.string("thinking").orEmpty() + text)
                }
                "server_tool_use" -> {
                    val toolInput = if (input.isNotEmpty()) parseObject(input.toString()) else initial["input"] as? JsonObject
                    content["input"] = toolInput ?: return null
                }
                in resultTypes -> Unit
                else -> return null
            }
            if (signature.isNotEmpty()) content["signature"] = JsonPrimitive(signature.toString())
            return JsonObject(content)
        }
    }

    companion object {
        private const val BLOCK_TYPE = "__messages_web_tool_history"
        private val resultTypes = setOf("web_search_tool_result", "web_fetch_tool_result")

        fun isStoredBlock(block: ReplyBlock): Boolean = block is ReplyBlock.Raw && block.type == BLOCK_TYPE

        fun displayBlocks(blocks: List<ReplyBlock>): List<ReplyBlock> {
            val display = blocks.filterNot(::isStoredBlock)
            if (display.none { it.needsToolMetadata() }) return display
            val stored = read(blocks) ?: return display
            val titles = mutableMapOf<String, String>()
            val searchErrors = mutableMapOf<String, String>()
            stored.forEach { block ->
                when (val result = BlockParsers.parseBlockStart(block)) {
                    is BlockParsers.ParsedBlock.WebFetchResult ->
                        result.title?.let { titles[result.toolUseId] = it }
                    is BlockParsers.ParsedBlock.WebSearchResult ->
                        result.errorCode?.let { searchErrors[result.toolUseId] = it }
                    else -> Unit
                }
            }
            return display.map { block ->
                when (block) {
                    is ReplyBlock.WebFetchResult -> titles[block.toolUseId]
                        ?.takeIf { block.needsToolMetadata() }?.let { block.copy(title = it) } ?: block
                    is ReplyBlock.WebSearchResult -> searchErrors[block.toolUseId]
                        ?.takeIf { block.needsToolMetadata() }?.let { block.copy(errorCode = it) } ?: block
                    else -> block
                }
            }
        }

        private fun ReplyBlock.needsToolMetadata(): Boolean = when (this) {
            is ReplyBlock.WebFetchResult -> errorCode == null && title.isNullOrBlank()
            is ReplyBlock.WebSearchResult -> errorCode == null && hits.isEmpty()
            else -> false
        }

        fun read(blocks: List<ReplyBlock>): JsonArray? {
            val raw = blocks.lastOrNull(::isStoredBlock) as? ReplyBlock.Raw ?: return null
            return runCatching { BlockParsers.json.parseToJsonElement(raw.rawJson) as? JsonArray }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }

        private fun parseObject(raw: String): JsonObject? =
            runCatching { BlockParsers.json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
