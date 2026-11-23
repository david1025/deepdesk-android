package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.Citation
import com.david.deepdesk.core.model.SearchHit
import com.david.deepdesk.core.model.ServerToolKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE 块/delta 原始 JSON 的宽松解析。
 * 协议演进或字段缺失时安全降级，不破坏整条流（§12 风险项）。
 */
object BlockParsers {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ------------------------------ content_block_start ------------------------------

    sealed interface ParsedBlock {
        data class Text(val text: String, val citations: List<Citation>) : ParsedBlock
        data class Thinking(val thinking: String, val signature: String?) : ParsedBlock
        data class ServerToolUse(
            val toolUseId: String,
            val name: String,
            val kind: ServerToolKind,
            val inputJson: String?,
            val action: String?,
            val query: String?,
            val url: String?,
        ) : ParsedBlock

        data class WebSearchResult(
            val toolUseId: String,
            val hits: List<SearchHit>,
            val errorCode: String?,
        ) : ParsedBlock

        data class WebFetchResult(
            val toolUseId: String,
            val url: String?,
            val title: String?,
            val errorCode: String?,
        ) : ParsedBlock

        data class Citations(val citations: List<Citation>) : ParsedBlock
        data class Unknown(val type: String, val rawJson: String) : ParsedBlock
    }

    fun parseBlockStart(el: JsonElement): ParsedBlock {
        return try {
            val obj = el.jsonObject
            val type = obj.str("type") ?: return ParsedBlock.Unknown("__unknown__", el.toString())
            when (type) {
                "text" -> ParsedBlock.Text(obj.str("text") ?: "", obj.parseCitationsField("citations"))
                "thinking" -> ParsedBlock.Thinking(obj.str("thinking") ?: "", obj.str("signature"))
                "server_tool_use" -> {
                    val name = obj.str("name") ?: ""
                    val input = obj["input"]
                    ParsedBlock.ServerToolUse(
                        toolUseId = obj.str("id") ?: "",
                        name = name,
                        kind = toolKind(name),
                        inputJson = input?.toString(),
                        action = input?.jsonObjectOrNull()?.str("action"),
                        query = input?.jsonObjectOrNull()?.str("query"),
                        url = input?.jsonObjectOrNull()?.str("url"),
                    )
                }
                "web_search_tool_result" -> {
                    val content = obj["content"]
                    val (hits, errorCode) = parseSearchContent(content)
                    ParsedBlock.WebSearchResult(obj.str("tool_use_id") ?: "", hits, errorCode)
                }
                "web_fetch_tool_result" -> {
                    val content = obj.obj("content")
                    val document = content?.obj("content")
                    ParsedBlock.WebFetchResult(
                        toolUseId = obj.str("tool_use_id") ?: "",
                        url = content?.str("url"),
                        title = document?.pageTitle() ?: document?.obj("document")?.pageTitle(),
                        errorCode = content?.str("error_code") ?: content?.obj("result")?.str("error_code"),
                    )
                }
                "citations" -> ParsedBlock.Citations(obj.parseCitationsField("citations"))
                else -> ParsedBlock.Unknown(type, el.toString())
            }
        } catch (e: Exception) {
            ParsedBlock.Unknown("__malformed__", el.toString().take(2000))
        }
    }

    // ------------------------------ content_block_delta ------------------------------

    sealed interface ParsedDelta {
        data class Text(val text: String) : ParsedDelta
        data class Thinking(val thinking: String) : ParsedDelta
        data class Signature(val signature: String) : ParsedDelta
        data class InputJson(val partialJson: String) : ParsedDelta
        data class ParsedCitation(val citation: com.david.deepdesk.core.model.Citation) : ParsedDelta
        data object Unknown : ParsedDelta
    }

    fun parseDelta(el: JsonElement): ParsedDelta {
        return try {
            val obj = el.jsonObject
            when (obj.str("type")) {
                "text_delta" -> ParsedDelta.Text(obj.str("text") ?: "")
                "thinking_delta" -> ParsedDelta.Thinking(obj.str("thinking") ?: "")
                "signature_delta" -> ParsedDelta.Signature(obj.str("signature") ?: "")
                "input_json_delta" -> ParsedDelta.InputJson(obj.str("partial_json") ?: "")
                "citations_delta" -> {
                    val c = obj["citation"]?.jsonObjectOrNull() ?: return ParsedDelta.Unknown
                    ParsedDelta.ParsedCitation(
                        Citation(
                            url = c.str("url") ?: "",
                            title = c.str("title"),
                            citedText = c.str("cited_text"),
                            encryptedIndex = c.str("encrypted_index"),
                        )
                    )
                }
                else -> ParsedDelta.Unknown
            }
        } catch (e: Exception) {
            ParsedDelta.Unknown
        }
    }

    // ------------------------------ 搜索结果条目 ------------------------------

    private fun parseSearchContent(content: JsonElement?): Pair<List<SearchHit>, String?> {
        if (content == null) return emptyList<SearchHit>() to null
        return when (content) {
            is JsonArray -> {
                val errorCode = content.firstNotNullOfOrNull { item ->
                    val entry = item as? JsonObject ?: return@firstNotNullOfOrNull null
                    if (entry.str("type") == "web_search_tool_result_error") entry.str("error_code") else null
                }
                val hits = content.mapNotNull { item ->
                    runCatching {
                        val o = item.jsonObject
                        if (o.str("type") == "web_search_result") {
                            SearchHit(
                                title = o.str("title"),
                                url = o.str("url") ?: "",
                                snippet = o.str("snippet"),
                                siteName = o.str("site_name"),
                                siteIcon = o.str("site_icon"),
                                pageAge = o.str("page_age"),
                                imageUrl = o.str("image_url"),
                            )
                        } else null
                    }.getOrNull()
                }
                hits to errorCode
            }
            is JsonObject -> {
                val errorCode = content.str("error_code")
                    ?: content.obj("result")?.str("error_code")
                emptyList<SearchHit>() to errorCode
            }
            else -> emptyList<SearchHit>() to null
        }
    }

    // ------------------------------ 工具 ------------------------------

    fun toolKind(name: String): ServerToolKind = when {
        name.contains("web_search") || name == "web_search" -> ServerToolKind.WEB_SEARCH
        name.contains("read_url") || name.contains("fetch") -> ServerToolKind.WEB_FETCH
        else -> ServerToolKind.UNKNOWN
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.pageTitle(): String? =
        (this["title"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonObject.parseCitationsField(key: String): List<Citation> {
        val arr = (this[key] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { item ->
            runCatching {
                val o = item.jsonObject
                Citation(
                    url = o.str("url") ?: "",
                    title = o.str("title"),
                    citedText = o.str("cited_text"),
                    encryptedIndex = o.str("encrypted_index"),
                )
            }.getOrNull()
        }.filter { it.url.isNotBlank() }
    }
}
