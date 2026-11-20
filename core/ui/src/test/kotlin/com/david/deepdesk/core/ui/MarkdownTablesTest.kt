package com.david.deepdesk.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTablesTest {

    @Test
    fun `分隔行丢掉，加粗格子去掉星号`() {
        val markdown = """
            | 维度 | Qwen3.8-Max | Qwen4-Max |
            |---|---|---|
            | 成本 | 26.58元 | **11.16元** |
        """.trimIndent()
        val rows = parseMarkdownTable(markdown)
        assertEquals(2, rows.size)
        assertEquals(listOf("维度", "Qwen3.8-Max", "Qwen4-Max"), rows[0].map { it.text })
        assertFalse(rows[0][1].bold)
        assertEquals("11.16元", rows[1][2].text)
        assertTrue(rows[1][2].bold)
        assertFalse(rows[1][1].bold)
        assertEquals("维度,Qwen3.8-Max,Qwen4-Max\n成本,26.58元,11.16元", markdownTableToCsv(markdown))
    }
}
