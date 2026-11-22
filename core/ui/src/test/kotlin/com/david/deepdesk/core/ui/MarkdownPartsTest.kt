package com.david.deepdesk.core.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPartsTest {

    @Test
    fun splitsDisplayAndInlineLatex() {
        val parts = MarkdownParts.split("面积 \$\$E=mc^2\$\$ 与 \$a^2\$ 结束")
        assertEquals(3, parts.size)
        assertEquals(MarkdownPart.Markdown("面积 "), parts[0])
        assertEquals(MarkdownPart.Latex("E=mc^2", display = true), parts[1])
        val tail = parts[2] as MarkdownPart.Markdown
        assertTrue(tail.text.startsWith(" 与 "))
        assertTrue(tail.text.contains(MATH_START))
        assertFalse(tail.text.contains("a^2"))
        assertEquals(" 与 \$a^2\$ 结束", decodeInlineMath(tail.text))
    }

    @Test
    fun parenInlineStaysInParagraph() {
        val parts = MarkdownParts.split("公式 \\(E=mc^2\\) 结束")
        assertEquals(1, parts.size)
        val text = (parts[0] as MarkdownPart.Markdown).text
        assertFalse(text.contains("\\("))
        assertEquals("公式 \$E=mc^2\$ 结束", decodeInlineMath(text))
    }

    @Test
    fun bracketDisplayIsBlock() {
        val parts = MarkdownParts.split("前\\[a^2\\]后")
        assertEquals(3, parts.size)
        assertEquals(MarkdownPart.Markdown("前"), parts[0])
        assertEquals(MarkdownPart.Latex("a^2", display = true), parts[1])
        assertEquals(MarkdownPart.Markdown("后"), parts[2])
    }

    @Test
    fun inlineUnderscoreStaysOpaqueToMarkdown() {
        val parts = MarkdownParts.split("见 \$a_b\$ 完")
        assertEquals(1, parts.size)
        val text = (parts[0] as MarkdownPart.Markdown).text
        assertTrue(text.contains(MATH_START))
        assertFalse(text.contains("a_b"))
        assertFalse(text.contains('_'))
        assertEquals("见 \$a_b\$ 完", decodeInlineMath(text))
        val emph = mutableListOf<ASTNode>()
        val texts = mutableListOf<String>()
        fun walk(node: ASTNode) {
            if (node.type == MarkdownElementTypes.EMPH) emph += node
            if (node.type == MarkdownTokenTypes.TEXT) {
                texts += text.substring(node.startOffset, node.endOffset)
            }
            node.children.forEach { walk(it) }
        }
        walk(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
        assertTrue(emph.isEmpty())
        assertTrue(texts.any { it.contains(MATH_START) })
        assertTrue(texts.none { it.contains('_') })
    }

    @Test
    fun inlineMathInsideTableStaysOnePart() {
        val src = """
            | 式 | 值 |
            |:---|:---|
            | 能量 | ${'$'}E=mc^2${'$'} |
        """.trimIndent()
        val parts = MarkdownParts.split(src)
        assertEquals(1, parts.size)
        val text = (parts[0] as MarkdownPart.Markdown).text
        assertTrue(text.contains(MATH_START))
        val rows = parseMarkdownTable(text)
        assertEquals("能量", rows[1][0].text)
        assertTrue(rows[1][1].text.contains(MATH_START))
        assertEquals("\$E=mc^2\$", decodeInlineMath(rows[1][1].text))
        val csv = markdownTableToCsv(text)
        assertTrue(csv.contains("\$E=mc^2\$"))
        assertFalse(csv.contains(MATH_START))
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
        val text = (parts[0] as MarkdownPart.Markdown).text
        assertFalse(text.contains(MATH_START))
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
        val markdown = parts[0] as MarkdownPart.Markdown
        assertFalse(markdown.text.contains(MATH_START))
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
    fun currencySentenceStaysText() {
        val src = "输入 \$1.50 / 百万 Token，输出 \$10.00，系数仍是公式 \$p_i\$。转义美元 \\\$5 是普通文字。"
        val parts = MarkdownParts.split(src)
        assertEquals(1, parts.size)
        val text = (parts[0] as MarkdownPart.Markdown).text
        assertTrue(text.contains("\$1.50"))
        assertTrue(text.contains("\$10.00"))
        assertTrue(text.contains("百万 Token"))
        assertEquals("输入 \$1.50 / 百万 Token，输出 \$10.00，系数仍是公式 \$p_i\$。转义美元 \\\$5 是普通文字。", decodeInlineMath(text))
    }

    @Test
    fun bracketAlignedAndCasesAreDisplayParts() {
        val aligned = "前\n\\[\n\\begin{aligned}\na&=b\\\\\nc&=d\n\\end{aligned}\n\\]\n后"
        val alignedParts = MarkdownParts.split(aligned)
        assertEquals(3, alignedParts.size)
        assertEquals(MarkdownPart.Latex("\\begin{aligned}\na&=b\\\\\nc&=d\n\\end{aligned}", display = true), alignedParts[1])
        val cases = "\$\$\nf(x)=\\begin{cases}\nx^2 & x\\ge 0\\\\\n-x & x<0\n\\end{cases}\n\$\$"
        val casesParts = MarkdownParts.split(cases)
        assertEquals(1, casesParts.size)
        val latex = casesParts[0] as MarkdownPart.Latex
        assertTrue(latex.display)
        assertTrue(latex.latex.contains("\\begin{cases}"))
    }

    @Test
    fun normalizeTurnsChemistryAndInlineDisplayStyle() {
        assertEquals("\\mathrm{H}_{2}\\mathrm{O}", normalizeLatex("\\ce{H2O}", display = true))
        assertEquals("\\textstyle\\frac{a}{b}", normalizeLatex("\\displaystyle\\frac{a}{b}", display = false))
        assertEquals("\\displaystyle\\frac{a}{b}", normalizeLatex("\\displaystyle\\frac{a}{b}", display = true))
        assertEquals("\$\$E=mc^2\$\$", latexFallback("E=mc^2", display = true))
        assertEquals("\$E=mc^2\$", latexFallback("E=mc^2", display = false))
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
