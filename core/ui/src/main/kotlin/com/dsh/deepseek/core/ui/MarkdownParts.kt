package com.dsh.deepseek.core.ui

/**
 * 把聊天 Markdown 拆成可分别渲染的片段：
 * mermaid 围栏 → 图；`$$`/`$` 公式 → JLatexMath；其余走 Markdown。
 * 代码围栏与行内 code 内的 `$` 不解析为公式。
 */
sealed interface MarkdownPart {
    data class Markdown(val text: String) : MarkdownPart
    data class Latex(val latex: String, val display: Boolean) : MarkdownPart
    data class Mermaid(val source: String) : MarkdownPart
}

object MarkdownParts {

    fun split(content: String): List<MarkdownPart> {
        if (content.isEmpty()) return emptyList()
        return merge(
            splitFences(content).flatMap { part ->
                if (part is MarkdownPart.Markdown) splitMath(part.text) else listOf(part)
            },
        )
    }

    private fun merge(parts: List<MarkdownPart>): List<MarkdownPart> {
        val out = mutableListOf<MarkdownPart>()
        for (p in parts) {
            if (p is MarkdownPart.Markdown && p.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (p is MarkdownPart.Markdown && last is MarkdownPart.Markdown) {
                out[out.lastIndex] = MarkdownPart.Markdown(last.text + p.text)
            } else {
                out += p
            }
        }
        return out
    }

    private data class FenceOpen(
        val bodyStart: Int,
        val marker: Char,
        val count: Int,
        val info: String,
    )

    private fun splitFences(content: String): List<MarkdownPart> {
        val parts = mutableListOf<MarkdownPart>()
        val sb = StringBuilder()
        var i = 0
        val n = content.length
        fun flush() {
            if (sb.isNotEmpty()) {
                parts += MarkdownPart.Markdown(sb.toString())
                sb.clear()
            }
        }
        fun atLineStart(idx: Int) = idx == 0 || content[idx - 1] == '\n'
        while (i < n) {
            if (atLineStart(i)) {
                val open = parseFenceOpen(content, i)
                if (open != null) {
                    val close = findFenceClose(content, open.bodyStart, open.marker, open.count)
                    if (close != null) {
                        val (bodyEnd, afterClose) = close
                        val lang = open.info.trim().substringBefore(' ')
                        if (lang.equals("mermaid", ignoreCase = true)) {
                            flush()
                            parts += MarkdownPart.Mermaid(content.substring(open.bodyStart, bodyEnd).trim())
                        } else {
                            sb.append(content, i, afterClose)
                        }
                        i = afterClose
                        continue
                    }
                }
            }
            sb.append(content[i])
            i++
        }
        flush()
        return parts
    }

    private fun parseFenceOpen(content: String, lineStart: Int): FenceOpen? {
        var i = lineStart
        var indent = 0
        while (i < content.length && (content[i] == ' ' || content[i] == '\t')) {
            indent++
            if (indent > 3) return null
            i++
        }
        if (i >= content.length) return null
        val marker = content[i]
        if (marker != '`' && marker != '~') return null
        var count = 0
        while (i < content.length && content[i] == marker) {
            count++
            i++
        }
        if (count < 3) return null
        val infoStart = i
        while (i < content.length && content[i] != '\n') {
            if (marker == '`' && content[i] == '`') return null
            i++
        }
        val info = content.substring(infoStart, i)
        val bodyStart = if (i < content.length && content[i] == '\n') i + 1 else i
        return FenceOpen(bodyStart, marker, count, info)
    }

    private fun findFenceClose(content: String, from: Int, marker: Char, count: Int): Pair<Int, Int>? {
        var i = from
        while (i <= content.length) {
            val atStart = i == from || (i > 0 && i <= content.length && content[i - 1] == '\n')
            if (atStart && i < content.length) {
                var j = i
                var indent = 0
                while (j < content.length && (content[j] == ' ' || content[j] == '\t')) {
                    indent++
                    if (indent > 3) break
                    j++
                }
                if (indent <= 3) {
                    var c = 0
                    while (j < content.length && content[j] == marker) {
                        c++
                        j++
                    }
                    if (c >= count) {
                        while (j < content.length && (content[j] == ' ' || content[j] == '\t')) j++
                        if (j >= content.length || content[j] == '\n') {
                            val after = if (j < content.length) j + 1 else j
                            return i to after
                        }
                    }
                }
            }
            if (i >= content.length) break
            i++
        }
        return null
    }

    private fun splitMath(text: String): List<MarkdownPart> {
        val parts = mutableListOf<MarkdownPart>()
        val sb = StringBuilder()
        var i = 0
        val n = text.length
        fun flush() {
            if (sb.isNotEmpty()) {
                parts += MarkdownPart.Markdown(sb.toString())
                sb.clear()
            }
        }
        fun atLineStart(idx: Int) = idx == 0 || text[idx - 1] == '\n'
        while (i < n) {
            if (atLineStart(i)) {
                val open = parseFenceOpen(text, i)
                if (open != null) {
                    val close = findFenceClose(text, open.bodyStart, open.marker, open.count)
                    val end = close?.second ?: n
                    sb.append(text, i, end)
                    i = end
                    continue
                }
            }
            if (text[i] == '`') {
                val run = countChar(text, i, '`')
                val close = findInlineCodeClose(text, i + run, run)
                val end = close ?: (i + run)
                sb.append(text, i, end)
                i = end
                continue
            }
            if (text.startsWith("$$", i) && (i == 0 || text[i - 1] != '\\')) {
                val end = text.indexOf("$$", i + 2)
                if (end >= 0) {
                    flush()
                    parts += MarkdownPart.Latex(text.substring(i + 2, end).trim(), display = true)
                    i = end + 2
                    continue
                }
            }
            // `$1.50` 是价格，不是公式。跟在美元符号后的数字留在正文里，避免把表格从这里截断。
            if (text[i] == '$' &&
                (i == 0 || text[i - 1] != '\\') &&
                (i + 1 >= n || text[i + 1] != '$') &&
                i + 1 < n &&
                !text[i + 1].isWhitespace() &&
                !text[i + 1].isDigit()
            ) {
                var j = i + 1
                var found = -1
                while (j < n && text[j] != '\n') {
                    if (text[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (text[j] == '$' && !text[j - 1].isWhitespace()) {
                        found = j
                        break
                    }
                    j++
                }
                if (found > i) {
                    flush()
                    parts += MarkdownPart.Latex(text.substring(i + 1, found).trim(), display = false)
                    i = found + 1
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        flush()
        return parts
    }

    private fun countChar(text: String, start: Int, c: Char): Int {
        var i = start
        while (i < text.length && text[i] == c) i++
        return i - start
    }

    private fun findInlineCodeClose(text: String, from: Int, ticks: Int): Int? {
        var i = from
        while (i < text.length) {
            if (text[i] == '`') {
                val run = countChar(text, i, '`')
                if (run == ticks) return i + run
                i += run
            } else {
                i++
            }
        }
        return null
    }
}
