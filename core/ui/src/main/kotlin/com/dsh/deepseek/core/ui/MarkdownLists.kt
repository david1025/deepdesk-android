package com.dsh.deepseek.core.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 库默认列表是 Row，正文没有 weight，会按整行宽度排版，圆点再占一截，行尾被屏幕裁掉。
 * 官方 MarkdownUnorderedList 把圆点单独占宽，正文用剩余宽度换行。
 * handleElement 是库的 internal，这里把列表项正文重新交给 Markdown，嵌套列表和加粗仍走同一套组件。
 */
@Composable
internal fun ColumnScope.ChatUnorderedList(model: MarkdownComponentModel) {
    val handler = LocalBulletListHandler.current
    MarkdownListItems(model.content, model.node, model.typography.bullet) { index, child ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = handler.transform(MarkdownTokenTypes.LIST_BULLET, "•", index),
                style = model.typography.bullet,
                color = markerColor(model.typography.bullet.color),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 6.dp),
            )
            ListItemBody(model.content, child)
        }
    }
}

@Composable
internal fun ColumnScope.ChatOrderedList(model: MarkdownComponentModel) {
    val handler = LocalOrderedListHandler.current
    MarkdownListItems(model.content, model.node, model.typography.ordered) { index, child ->
        val number = remember(model.content, child.startOffset, child.endOffset, index) {
            orderedMarker(model.content, child, index)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = handler.transform(MarkdownTokenTypes.LIST_NUMBER, number, index),
                style = model.typography.ordered,
                color = markerColor(model.typography.ordered.color),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .widthIn(min = 20.dp),
            )
            ListItemBody(model.content, child)
        }
    }
}

@Composable
private fun RowScope.ListItemBody(content: String, child: ASTNode) {
    val body = remember(content, child.startOffset, child.endOffset) {
        listItemMarkdown(content, child)
    }
    if (body.isBlank()) {
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        return
    }
    // weight 只约束这一层。内层 Markdown 再按剩余宽度排，避免圆点占位后正文仍按整行宽度排版。
    BoxWithConstraints(Modifier.weight(1f)) {
        val width = if (maxWidth == Dp.Infinity || maxWidth == Dp.Unspecified) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(maxWidth)
        }
        Markdown(
            content = body,
            colors = LocalMarkdownColors.current,
            typography = LocalMarkdownTypography.current,
            padding = markdownPadding(block = 0.dp, list = 0.dp, listItemBottom = 2.dp),
            dimens = LocalMarkdownDimens.current,
            components = LocalMarkdownComponents.current,
            modifier = width,
        )
    }
}

@Composable
private fun markerColor(styleColor: Color): Color =
    if (styleColor == Color.Unspecified) LocalMarkdownColors.current.text else styleColor

private fun orderedMarker(content: String, node: ASTNode, index: Int): String {
    val raw = slice(content, node).lineSequence().firstOrNull().orEmpty()
    return Regex("""^\s*(\d+[.)])""").find(raw)?.groupValues?.getOrNull(1) ?: "${index + 1}."
}

internal fun listItemMarkdown(content: String, node: ASTNode): String {
    val lines = slice(content, node).replace("\r\n", "\n").removeSuffix("\n").lines()
    if (lines.isEmpty()) return ""
    val first = lines.first().replaceFirst(Regex("""^\s*(?:[-*+]|\d+[.)])\s+"""), "")
    val rest = lines.drop(1)
    val trim = rest.filter { it.isNotBlank() }.minOfOrNull { line ->
        line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) 0 else it }
    } ?: 0
    return buildString {
        append(first)
        rest.forEach { line ->
            append('\n')
            append(if (line.isBlank()) "" else line.drop(trim.coerceAtMost(line.length)))
        }
    }.trim()
}

private fun slice(content: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}
