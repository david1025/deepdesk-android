package com.david.deepdesk.feature.chat.ui

import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.SearchHit
import com.david.deepdesk.core.model.ServerToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingTraceTest {

    @Test
    fun `思考正文和搜索浏览按原顺序穿插，耗时相加`() {
        val blocks = listOf(
            ReplyBlock.Thinking("先去搜公开评测。", durationMs = 1_000),
            searchUse(),
            ReplyBlock.WebSearchResult("s1", hits = List(38) { SearchHit(url = "https://example.com/$it") }),
            ReplyBlock.Thinking("打开这几篇。", durationMs = 1_000),
            fetchUse("https://a.example/1"),
            ReplyBlock.WebFetchResult("f1", url = "https://a.example/1", title = "A"),
            ReplyBlock.WebFetchResult("f2", url = "https://b.example/2", title = "B"),
            ReplyBlock.Thinking("整理成对比。"),
            ReplyBlock.Text("正文"),
        )
        val reasoning = blocks.takeWhile { it.isReasoningBlock() }
        assertEquals(8, reasoning.size)
        assertEquals(2_000L, thinkingDurationMs(reasoning))

        val pieces = tracePieces(reasoning)
        assertEquals(5, pieces.size)
        assertEquals("先去搜公开评测。", (pieces[0] as TracePiece.Narrative).text)
        val searched = pieces[1] as TracePiece.Searched
        assertEquals(38, searched.hits.size)
        assertEquals("打开这几篇。", (pieces[2] as TracePiece.Narrative).text)
        val browsed = pieces[3] as TracePiece.Browsed
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), browsed.pages.map { it.url })
        assertEquals("A", browsed.pages[0].title)
        assertEquals("整理成对比。", (pieces[4] as TracePiece.Narrative).text)
        assertTrue(blocks.last() is ReplyBlock.Text)
    }

    @Test
    fun `纯文本思考段显示成圆点`() {
        assertEquals("- 先去搜。", narrativeMarkdown("先去搜。"))
        assertEquals("- 已经是列表", narrativeMarkdown("- 已经是列表"))
    }

    @Test
    fun `浏览页按网址对上同一轮搜索的站名摘要和日期`() {
        val hit = SearchHit(
            title = "Qwen3.8 Max",
            url = "https://www.bench.example/a#section",
            snippet = "定价和跑分",
            siteName = "BenchGecko",
            pageAge = "2026-11-08",
        )
        val pieces = tracePieces(
            listOf(
                ReplyBlock.WebSearchResult("s1", hits = listOf(hit)),
                ReplyBlock.WebFetchResult("f1", url = "https://bench.example/a?q=1", title = "打开时的标题"),
            ),
        )
        val page = (pieces[1] as TracePiece.Browsed).pages.single()
        assertEquals("Qwen3.8 Max", page.title)
        assertEquals("定价和跑分", page.snippet)
        assertEquals("BenchGecko", page.siteName)
        assertEquals("2026/11/08", formatPageAge(page.pageAge))
        assertEquals(null, formatPageAge(null))
        assertEquals(null, formatPageAge("昨天"))
    }

    @Test
    fun `没有结果的进行中搜索只显示进行中`() {
        val pieces = tracePieces(listOf(searchUse(running = true)))
        val searched = pieces.single() as TracePiece.Searched
        assertTrue(searched.running)
        assertTrue(searched.hits.isEmpty())
    }

    private fun searchUse(running: Boolean = false) = ReplyBlock.ServerToolUse(
        toolUseId = "s",
        name = "web_search",
        kind = ServerToolKind.WEB_SEARCH,
        query = "qwen",
        rawJson = "{}",
        running = running,
    )

    private fun fetchUse(url: String) = ReplyBlock.ServerToolUse(
        toolUseId = "f",
        name = "web_fetch",
        kind = ServerToolKind.WEB_FETCH,
        url = url,
        rawJson = "{}",
        running = false,
    )
}
