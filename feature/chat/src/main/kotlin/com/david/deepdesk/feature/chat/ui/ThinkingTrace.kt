package com.david.deepdesk.feature.chat.ui

import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.SearchHit
import com.david.deepdesk.core.model.ServerToolKind

/** 展开思考时按原文顺序穿插的一段。 */
/** 打开过的页面。站名、摘要来自同一轮搜索结果，按网址对上。 */
internal data class BrowsePage(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val siteName: String? = null,
    val siteIcon: String? = null,
    val pageAge: String? = null,
)

/** 点「已搜索到 / 已浏览」后打开的列表。 */
internal sealed interface TraceSheet {
    data class Search(val hits: List<SearchHit>) : TraceSheet
    data class Browsed(val pages: List<BrowsePage>) : TraceSheet
}

internal sealed interface TracePiece {
    data class Narrative(val text: String) : TracePiece
    data class Searched(val hits: List<SearchHit>, val running: Boolean) : TracePiece
    data class Browsed(val pages: List<BrowsePage>, val running: Boolean) : TracePiece
    data class SearchFailed(val code: String) : TracePiece
    data class FetchFailed(val code: String) : TracePiece
}

internal fun ReplyBlock.isReasoningBlock(): Boolean = when (this) {
    is ReplyBlock.Thinking, is ReplyBlock.ServerToolUse, is ReplyBlock.WebSearchResult, is ReplyBlock.WebFetchResult -> true
    else -> false
}

/**
 * 官方把每一段思考正文显示成灰色圆点。没有列表标记的段落补上，已有标记的原样保留。
 */
internal fun narrativeMarkdown(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return trimmed
    return trimmed.split(Regex("""\n\s*\n""")).joinToString("\n\n") { paragraph ->
        val body = paragraph.trim()
        if (body.contains(listMarker)) body else "- ${body.replace("\n", "\n  ")}"
    }
}

private val listMarker = Regex("""(?m)^(\s*([-*+]|\d+[.)])\s+\S)""")

/** 同一轮思考里各段耗时相加，对应官方一条「已思考（用时 N 秒）」。 */
internal fun thinkingDurationMs(blocks: List<ReplyBlock>): Long? {
    val values = blocks.filterIsInstance<ReplyBlock.Thinking>().mapNotNull { it.durationMs }
    return values.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * 把连续的思考正文和工具块收成一条时间线。
 * 搜索结果收成「搜索到 N 个网页」，连续的链接阅读收成「已浏览 N 个页面」，中间的思考正文保持原顺序。
 */
internal fun tracePieces(blocks: List<ReplyBlock>): List<TracePiece> {
    val out = mutableListOf<TracePiece>()
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        if (block is ReplyBlock.Thinking) {
            if (block.text.isNotBlank()) out += TracePiece.Narrative(block.text.trim())
            index++
            continue
        }
        val run = mutableListOf<ReplyBlock>()
        while (index < blocks.size && blocks[index].isToolTrace()) {
            run += blocks[index]
            index++
        }
        if (run.isNotEmpty()) appendToolRun(out, run)
    }
    val hits = out.filterIsInstance<TracePiece.Searched>().flatMap { it.hits }
    if (hits.isEmpty()) return out
    return out.map { piece ->
        if (piece is TracePiece.Browsed && piece.pages.isNotEmpty()) {
            piece.copy(pages = piece.pages.map { it.withSearchHit(hits) })
        } else {
            piece
        }
    }
}

internal fun urlKey(raw: String): String {
    val trimmed = raw.substringBefore('#').substringBefore('?')
    return trimmed.substringAfter("://", trimmed).removePrefix("www.").trimEnd('/')
}

/** 官方搜索行日期骨架是 yMd。有年月日才显示成 2026/11/08，没有就不编。 */
internal fun formatPageAge(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val match = pageAgeDate.find(raw) ?: return null
    val year = match.groupValues[1]
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    return "%s/%02d/%02d".format(year, month, day)
}

private val pageAgeDate = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")

private fun BrowsePage.withSearchHit(hits: List<SearchHit>): BrowsePage {
    val hit = hits.firstOrNull { urlKey(it.url) == urlKey(url) } ?: return this
    return copy(
        title = hit.title ?: title,
        snippet = hit.snippet ?: snippet,
        siteName = hit.siteName ?: siteName,
        siteIcon = hit.siteIcon ?: siteIcon,
        pageAge = hit.pageAge ?: pageAge,
    )
}

private fun ReplyBlock.isToolTrace(): Boolean = when (this) {
    is ReplyBlock.ServerToolUse, is ReplyBlock.WebSearchResult, is ReplyBlock.WebFetchResult -> true
    else -> false
}

private fun appendToolRun(out: MutableList<TracePiece>, run: List<ReplyBlock>) {
    val searches = run.filterIsInstance<ReplyBlock.WebSearchResult>()
    val hits = searches.flatMap { it.hits }
    val searchRunning = run.filterIsInstance<ReplyBlock.ServerToolUse>().any {
        it.running && it.kind != ServerToolKind.WEB_FETCH && it.url == null
    }
    when {
        hits.isNotEmpty() -> out += TracePiece.Searched(hits, running = false)
        searchRunning -> out += TracePiece.Searched(emptyList(), running = true)
    }
    searches.firstNotNullOfOrNull { it.errorCode }?.let { out += TracePiece.SearchFailed(it) }

    val fetches = run.filterIsInstance<ReplyBlock.WebFetchResult>()
    val pages = fetches.mapNotNull { result ->
        val url = result.url?.takeIf { result.errorCode == null } ?: return@mapNotNull null
        BrowsePage(url = url, title = result.title)
    }
    val fetchRunning = run.filterIsInstance<ReplyBlock.ServerToolUse>().any { tool ->
        tool.running && (tool.kind == ServerToolKind.WEB_FETCH || tool.url != null)
    }
    when {
        pages.isNotEmpty() -> out += TracePiece.Browsed(pages, running = false)
        fetchRunning -> out += TracePiece.Browsed(emptyList(), running = true)
        else -> fetches.firstNotNullOfOrNull { it.errorCode }?.let { out += TracePiece.FetchFailed(it) }
    }
}
