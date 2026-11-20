package com.dsh.deepseek.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.MarkdownTypography
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * 官方 MarkdownOrderedList / MarkdownUnorderedList：序号或圆点单独占宽，正文用剩余宽度。
 * 正文按语法树往下画，不把条目再解析成一篇新的 Markdown。
 * 重新解析会让「1. **加粗**」再次变成有序列表，组合时无限套娃并闪退。
 */
@Composable
internal fun ColumnScope.ChatUnorderedList(model: MarkdownComponentModel) {
    val handler = LocalBulletListHandler.current
    Column(Modifier.fillMaxWidth()) {
        listItems(model.node).forEachIndexed { index, child ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = handler.transform(MarkdownTokenTypes.LIST_BULLET, "•", index),
                    style = model.typography.bullet,
                    color = markerColor(model.typography.bullet.color),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = 6.dp),
                )
                ListItemBody(model.content, child, model.typography)
            }
        }
    }
}

@Composable
internal fun ColumnScope.ChatOrderedList(model: MarkdownComponentModel) {
    val handler = LocalOrderedListHandler.current
    Column(Modifier.fillMaxWidth()) {
        listItems(model.node).forEachIndexed { index, child ->
            val number = remember(model.content, child.startOffset, child.endOffset, index) {
                orderedMarker(model.content, child, index)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
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
                ListItemBody(model.content, child, model.typography)
            }
        }
    }
}

@Composable
private fun RowScope.ListItemBody(
    content: String,
    item: ASTNode,
    typography: MarkdownTypography,
) {
    Column(Modifier.weight(1f)) {
        val components = LocalMarkdownComponents.current
        val scope = this
        item.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.LIST_NUMBER,
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownTokenTypes.EOL,
                -> Unit
                else -> {
                    val childModel = MarkdownComponentModel(content, child, typography)
                    when (child.type) {
                        MarkdownElementTypes.PARAGRAPH -> components.paragraph(scope, childModel)
                        MarkdownElementTypes.ORDERED_LIST -> components.orderedList(scope, childModel)
                        MarkdownElementTypes.UNORDERED_LIST -> components.unorderedList(scope, childModel)
                        MarkdownElementTypes.BLOCK_QUOTE -> components.blockQuote(scope, childModel)
                        MarkdownElementTypes.CODE_FENCE -> components.codeFence(scope, childModel)
                        MarkdownElementTypes.CODE_BLOCK -> components.codeBlock(scope, childModel)
                        MarkdownElementTypes.ATX_1 -> components.heading1(scope, childModel)
                        MarkdownElementTypes.ATX_2 -> components.heading2(scope, childModel)
                        MarkdownElementTypes.ATX_3 -> components.heading3(scope, childModel)
                        MarkdownElementTypes.ATX_4 -> components.heading4(scope, childModel)
                        MarkdownElementTypes.ATX_5 -> components.heading5(scope, childModel)
                        MarkdownElementTypes.ATX_6 -> components.heading6(scope, childModel)
                        MarkdownElementTypes.SETEXT_1 -> components.setextHeading1(scope, childModel)
                        MarkdownElementTypes.SETEXT_2 -> components.setextHeading2(scope, childModel)
                        MarkdownElementTypes.IMAGE -> components.image(scope, childModel)
                        GFMElementTypes.TABLE -> components.table(scope, childModel)
                        else -> components.custom?.invoke(scope, child.type, childModel)
                    }
                }
            }
        }
    }
}

private fun listItems(node: ASTNode): List<ASTNode> =
    node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }

@Composable
private fun markerColor(styleColor: Color): Color =
    if (styleColor == Color.Unspecified) LocalMarkdownColors.current.text else styleColor

private fun orderedMarker(content: String, node: ASTNode, index: Int): String {
    val raw = slice(content, node).lineSequence().firstOrNull().orEmpty()
    return Regex("""^\s*(\d+[.)])""").find(raw)?.groupValues?.getOrNull(1) ?: "${index + 1}."
}

private fun slice(content: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}
