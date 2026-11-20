package com.dsh.deepseek.core.ui

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPartsTest {

    @Test
    fun splitsDisplayAndInlineLatex() {
        val parts = MarkdownParts.split("面积 \$\$E=mc^2\$\$ 与 \$a^2\$ 结束")
        assertEquals(5, parts.size)
        assertEquals(MarkdownPart.Markdown("面积 "), parts[0])
        assertEquals(MarkdownPart.Latex("E=mc^2", display = true), parts[1])
        assertEquals(MarkdownPart.Markdown(" 与 "), parts[2])
        assertEquals(MarkdownPart.Latex("a^2", display = false), parts[3])
        assertEquals(MarkdownPart.Markdown(" 结束"), parts[4])
    }

    @Test
    fun extractsMermaidFence() {
        val src = """
            前言
            ```mermaid
            graph TD
              A-->B
            ```
            后记
        """.trimIndent()
        val parts = MarkdownParts.split(src)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is MarkdownPart.Markdown)
        val mermaid = parts[1] as MarkdownPart.Mermaid
        assertTrue(mermaid.source.contains("graph TD"))
        assertTrue(parts[2] is MarkdownPart.Markdown)
        assertTrue((parts[2] as MarkdownPart.Markdown).text.contains("后记"))
    }

    @Test
    fun skipsMathInsideCodeFenceAndInlineCode() {
        val parts = MarkdownParts.split("见 `\$x^2\$` 与\n```\n$ a $\n```\n")
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MarkdownPart.Markdown)
    }

    @Test
    fun currencyInTableStaysMarkdown() {
        val src = """
            | 价格维度 | **Qwen4-Max** | **Kimi K3.1 Pro** | 差异 |
            |:---|:---|:---|:---|
            | **输入（每百万Token）** | **${'$'}1.50**（约 ¥10.9） | **${'$'}2.00** | Qwen低 **25%** |
            | **输出（每百万Token）** | **${'$'}4.50**（约 ¥32.6） | **${'$'}10.00** | Qwen低 **55%** |
        """.trimIndent()
        val parts = MarkdownParts.split(src)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MarkdownPart.Markdown)
        val rows = parseMarkdownTable(src)
        assertEquals(3, rows.size)
        assertEquals("\$1.50（约 ¥10.9）", rows[1][1].text)
        assertEquals("\$2.00", rows[1][2].text)
        assertTrue(rows[1][1].bold)
        assertEquals("输入（每百万Token）", rows[1][0].text)
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(src)
        val tables = mutableListOf<org.intellij.markdown.ast.ASTNode>()
        fun walk(node: org.intellij.markdown.ast.ASTNode) {
            if (node.type == GFMElementTypes.TABLE) tables += node
            node.children.forEach { walk(it) }
        }
        walk(tree)
        assertEquals(1, tables.size)
        val slice = src.substring(tables[0].startOffset, tables[0].endOffset)
        assertTrue(slice.contains("\$1.50"))
        assertTrue(slice.contains("\$10.00"))
    }

    @Test
    fun plainMarkdownUnchanged() {
        val text = "你好 **世界**"
        val parts = MarkdownParts.split(text)
        assertEquals(listOf(MarkdownPart.Markdown(text)), parts)
    }

    @Test
    fun jsStringEscapesHtml() {
        val encoded = toJsString("</script>\n\"\\")
        assertTrue(encoded.startsWith("\""))
        assertTrue(encoded.endsWith("\""))
        assertTrue("\\u003c" in encoded)
        assertTrue("\\n" in encoded)
        assertTrue("\\\"" in encoded)
    }
}
