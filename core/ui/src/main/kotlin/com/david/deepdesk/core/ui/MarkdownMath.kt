package com.david.deepdesk.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.EntityConverter
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.codeSpanStyle
import com.mikepenz.markdown.utils.linkTextSpanStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

internal fun astSliceContainsMath(content: String, node: ASTNode): Boolean {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end).contains(MATH_START)
}

@Composable
internal fun MathAwareHeader(
    model: MarkdownComponentModel,
    style: TextStyle,
    childType: IElementType,
) {
    if (astSliceContainsMath(model.content, model.node)) {
        MathMarkdownText(model.content, model.node, style, contentChildType = childType)
    } else {
        MarkdownHeader(model.content, model.node, style, childType)
    }
}

/**
 * 引用条自己调 [MarkdownParagraph]，段落组件覆盖不到。有公式时在这里换行内绘制。
 */
@Composable
internal fun MathAwareBlockQuote(content: String, node: ASTNode, style: TextStyle) {
    val blockQuoteColor = LocalMarkdownColors.current.text
    val blockQuoteThickness = LocalMarkdownDimens.current.blockQuoteThickness
    val blockQuote = LocalMarkdownPadding.current.blockQuote
    val blockQuoteText = LocalMarkdownPadding.current.blockQuoteText
    val blockQuoteBar = LocalMarkdownPadding.current.blockQuoteBar

    Column(
        modifier = Modifier
            .drawBehind {
                drawLine(
                    color = blockQuoteColor,
                    strokeWidth = blockQuoteThickness.toPx(),
                    start = Offset(
                        blockQuoteBar.calculateStartPadding(LayoutDirection.Ltr).toPx(),
                        blockQuoteBar.calculateTopPadding().toPx(),
                    ),
                    end = Offset(
                        blockQuoteBar.calculateStartPadding(LayoutDirection.Ltr).toPx(),
                        size.height - blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                )
            }
            .padding(blockQuote),
    ) {
        val quote = node.findChildOfType(MarkdownElementTypes.PARAGRAPH)
        if (quote != null) {
            val paraMod = Modifier.padding(blockQuoteText)
            if (astSliceContainsMath(content, quote)) {
                MathMarkdownText(content, quote, style, paraMod)
            } else {
                MarkdownParagraph(content, quote, style = style, modifier = paraMod)
            }
        }
        val nested = node.findChildOfType(MarkdownElementTypes.BLOCK_QUOTE)
        if (nested != null) {
            if (quote != null) Spacer(Modifier.height(8.dp))
            if (astSliceContainsMath(content, nested)) {
                MathAwareBlockQuote(content, nested, style)
            } else {
                MarkdownBlockQuote(content, nested, style)
            }
        }
    }
}

@Composable
internal fun MathMarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) {
    val child = contentChildType?.let { node.findChildOfType(it) } ?: node
    val linkStyle = LocalMarkdownTypography.current.linkTextSpanStyle
    val codeStyle = LocalMarkdownTypography.current.codeSpanStyle
    var seq = 0
    val annotator = markdownAnnotator { raw, childNode ->
        if (childNode.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
        val start = childNode.startOffset.coerceIn(0, raw.length)
        val end = childNode.endOffset.coerceIn(start, raw.length)
        val slice = raw.substring(start, end)
        if (!slice.contains(MATH_START)) return@markdownAnnotator false
        appendMathRuns(slice) { "lx${seq++}" }
        true
    }
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, child, linkStyle, codeStyle, annotator)
        pop()
    }
    MathAnnotatedText(styled, style, modifier, maxLines, softWrap)
}

@Composable
internal fun InlineMathLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    softWrap: Boolean = false,
) {
    var seq = 0
    val styled = buildAnnotatedString {
        appendMathRuns(text) { "lx${seq++}" }
    }
    MathAnnotatedText(
        text = styled,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
        ),
        modifier = modifier,
        maxLines = maxLines,
        softWrap = softWrap,
    )
}

private fun AnnotatedString.Builder.appendMathRuns(raw: String, id: () -> String) {
    val plain = EntityConverter.replaceEntities(raw, processEntities = false, processEscapes = true)
    for (run in splitMathRuns(plain)) {
        when (run) {
            is MathRun.Text -> append(run.text)
            is MathRun.Formula -> appendInlineContent(id(), run.latex)
        }
    }
}

@Composable
private fun MathAnnotatedText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) {
    val color = if (style.color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color
    val fontSize = if (style.fontSize == TextUnit.Unspecified) 16.sp else style.fontSize
    val annotations = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
    val inline = LinkedHashMap<String, InlineTextContent>(annotations.size)
    for (ann in annotations) {
        val latex = text.subSequence(ann.start, ann.end).text
        key(ann.item) {
            inline[ann.item] = latexInlineContent(latex, fontSize, color)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val uriHandler = LocalUriHandler.current
    val hasUrl = text.getStringAnnotations(MARKDOWN_TAG_URL, 0, text.length).any()
    val clickable = if (hasUrl) {
        val referenceLinkHandler = LocalReferenceLinkHandler.current
        modifier.pointerInput(text) {
            awaitEachGesture {
                val pointer = awaitFirstDown()
                val found = layout?.let { result ->
                    val position = result.getOffsetForPosition(pointer.position)
                    text.getStringAnnotations(MARKDOWN_TAG_URL, position, position)
                        .reversed()
                        .firstOrNull()
                        ?.let { referenceLinkHandler.find(it.item) }
                }
                if (found != null) {
                    pointer.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        runCatching { uriHandler.openUri(found) }
                    }
                }
            }
        }
    } else {
        modifier
    }
    BasicText(
        text = text,
        modifier = clickable,
        style = style.copy(color = color, fontSize = fontSize),
        onTextLayout = { layout = it },
        overflow = TextOverflow.Clip,
        softWrap = softWrap,
        maxLines = maxLines,
        inlineContent = inline,
    )
}

@Composable
private fun latexInlineContent(latex: String, fontSize: TextUnit, color: Color): InlineTextContent {
    val context = LocalContext.current
    val density = LocalDensity.current
    val argb = color.toArgb()
    val textSizePx = with(density) { fontSize.toPx() }
    val bitmap = remember(latex, textSizePx, argb) {
        renderLatexBitmap(
            context = context,
            latex = latex,
            textSizePx = textSizePx,
            color = argb,
            paddingPx = 2,
            display = false,
        )
    }
    val width: TextUnit
    val height: TextUnit
    if (bitmap != null) {
        width = with(density) { bitmap.width.toSp() }
        height = with(density) { bitmap.height.toSp() }
    } else {
        width = (fontSize.value * (latex.length + 2) * 0.55f).sp
        height = fontSize * 1.4f
    }
    return InlineTextContent(
        Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = latex,
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = latexFallback(latex, display = false),
                color = color,
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
