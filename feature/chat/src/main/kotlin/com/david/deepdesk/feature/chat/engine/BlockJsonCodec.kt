package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.Citation
import com.david.deepdesk.core.model.SearchHit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 块结构化字段在 Room（rawJson/citationsJson 列）中的编解码。
 * 统一入口，避免各处手写 JSON 拼装。
 */
object BlockJsonCodec {

    // ------------------------------ Citations ------------------------------

    @Serializable
    data class CitationDto(
        val url: String,
        val title: String? = null,
        val cited_text: String? = null,
        val encrypted_index: String? = null,
    )

    fun encodeCitations(citations: List<Citation>, json: Json): String? {
        if (citations.isEmpty()) return null
        val dtos = citations.map { CitationDto(it.url, it.title, it.citedText, it.encryptedIndex) }
        return json.encodeToString(ListSerializer(CitationDto.serializer()), dtos)
    }

    fun decodeCitations(raw: String?, json: Json): List<Citation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CitationDto.serializer()), raw)
                .map { Citation(it.url, it.title, it.cited_text, it.encrypted_index) }
        }.getOrDefault(emptyList())
    }

    // ------------------------------ WebSearchResult ------------------------------

    @Serializable
    data class SearchResultDto(
        val hits: List<HitDto> = emptyList(),
        val error_code: String? = null,
    )

    @Serializable
    data class HitDto(
        val title: String? = null,
        val url: String,
        val snippet: String? = null,
        val site_name: String? = null,
        val site_icon: String? = null,
        val page_age: String? = null,
    )

    fun encodeSearchResult(hits: List<SearchHit>, errorCode: String?, json: Json): String {
        val dto = SearchResultDto(
            hits = hits.map { HitDto(it.title, it.url, it.snippet, it.siteName, it.siteIcon, it.pageAge) },
            error_code = errorCode,
        )
        return json.encodeToString(SearchResultDto.serializer(), dto)
    }

    fun decodeSearchResult(raw: String?, json: Json): Pair<List<SearchHit>, String?> {
        if (raw.isNullOrBlank()) return emptyList<SearchHit>() to null
        return runCatching {
            val dto = json.decodeFromString(SearchResultDto.serializer(), raw)
            dto.hits.map { SearchHit(it.title, it.url, it.snippet, it.site_name, it.site_icon, it.page_age) } to dto.error_code
        }.getOrDefault(emptyList<SearchHit>() to null)
    }

    // ------------------------------ WebFetchResult ------------------------------

    @Serializable
    data class FetchResultDto(
        val url: String? = null,
        val title: String? = null,
        val error_code: String? = null,
    )

    fun encodeFetchResult(url: String?, title: String?, errorCode: String?, json: Json): String =
        json.encodeToString(FetchResultDto.serializer(), FetchResultDto(url, title, errorCode))

    fun decodeFetchResult(raw: String?, json: Json): Triple<String?, String?, String?> {
        if (raw.isNullOrBlank()) return Triple(null, null, null)
        return runCatching {
            val dto = json.decodeFromString(FetchResultDto.serializer(), raw)
            Triple(dto.url, dto.title, dto.error_code)
        }.getOrDefault(Triple(null, null, null))
    }

    // ------------------------------ server_tool_use input ------------------------------

    fun toolInputField(raw: String?, field: String, json: Json): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            (obj[field] as? JsonPrimitive)?.content
        }.getOrNull()
    }
}
