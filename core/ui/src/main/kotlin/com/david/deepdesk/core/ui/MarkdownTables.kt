package com.david.deepdesk.core.ui

internal data class MarkdownTableCell(val text: String, val bold: Boolean)

/**
 * 把 GFM 表格切成格子。分隔行丢掉，`**` / `__` 记成加粗，显示时去掉标记。
 * 官方格子只画纯文本，链接留可见文字。
 */
internal fun parseMarkdownTable(markdown: String): List<List<MarkdownTableCell>> {
    return markdown.lines()
        .map { it.trim() }
        .filter { it.contains('|') }
        .map { splitTableRow(it) }
        .filterNot { cells -> cells.isNotEmpty() && cells.all { it.matches(TABLE_SEPARATOR) } }
        .map { row -> row.map { markdownCell(it) } }
}

internal fun markdownTableToCsv(markdown: String): String {
    val rows = parseMarkdownTable(markdown)
    return rows.joinToString("\n") { row ->
        row.joinToString(",") { cell ->
            val escaped = decodeInlineMath(cell.text).replace("\"", "\"\"")
            if (escaped.any { ch -> ch == ',' || ch == '"' || ch == '\n' }) "\"$escaped\"" else escaped
        }
    }
}

internal fun splitTableRow(line: String): List<String> {
    val body = line.trim().removePrefix("|").removeSuffix("|")
    return body.split('|').map { it.trim() }
}

internal val TABLE_SEPARATOR = Regex(""":?-{3,}:?""")

private val boldSpan = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
private val italicSpan = Regex("""(?<!\*)\*(.+?)\*(?!\*)""")
private val linkSpan = Regex("""\[(.+?)]\([^)]*\)""")
private val breakTag = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

private fun markdownCell(raw: String): MarkdownTableCell {
    val bold = raw.contains("**") || raw.contains("__")
    val text = raw
        .replace(boldSpan) { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
        .replace(italicSpan) { it.groupValues[1] }
        .replace(linkSpan) { it.groupValues[1] }
        .replace("`", "")
        .replace(breakTag, " ")
        .trim()
    return MarkdownTableCell(text, bold)
}
