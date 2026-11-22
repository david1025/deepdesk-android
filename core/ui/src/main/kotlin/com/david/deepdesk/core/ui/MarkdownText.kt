package com.david.deepdesk.core.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import org.intellij.markdown.MarkdownTokenTypes

/**
 * 聊天正文 Markdown 渲染（§7.4）：
 * Compose 原生渲染（mikepenz multiplatform-markdown-renderer），覆盖 GFM 基础语法、
 * 代码块、表格。行内公式留在句子里，块级 `$$` / `\[...\]` 单独成图，都走 JLatexMath。
 * Mermaid 走隐藏 WebView 出图。正文里的链接走 [openHttpUrl]，只允许 http/https。
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val linkHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) = openHttpUrl(context, uri)
        }
    }
    val parts = remember(content) { MarkdownParts.split(content) }
    val typography = chatMarkdownTypography(textColor, compact)
    val dimens = markdownDimens(
        tableCellWidth = 148.dp,
        tableCellPadding = 10.dp,
        tableCornerSize = 8.dp,
    )
    val components = markdownComponents(
        paragraph = { model ->
            if (astSliceContainsMath(model.content, model.node)) {
                MathMarkdownText(model.content, model.node, model.typography.paragraph)
            } else if (LocalRenderedSelection.current != null) {
                SelectableAstText(model.content, model.node, model.typography.paragraph)
            } else {
                MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
            }
        },
        heading1 = { model -> MathAwareHeader(model, model.typography.h1, MarkdownTokenTypes.ATX_CONTENT) },
        heading2 = { model -> MathAwareHeader(model, model.typography.h2, MarkdownTokenTypes.ATX_CONTENT) },
        heading3 = { model -> MathAwareHeader(model, model.typography.h3, MarkdownTokenTypes.ATX_CONTENT) },
        heading4 = { model -> MathAwareHeader(model, model.typography.h4, MarkdownTokenTypes.ATX_CONTENT) },
        heading5 = { model -> MathAwareHeader(model, model.typography.h5, MarkdownTokenTypes.ATX_CONTENT) },
        heading6 = { model -> MathAwareHeader(model, model.typography.h6, MarkdownTokenTypes.ATX_CONTENT) },
        setextHeading1 = { model -> MathAwareHeader(model, model.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
        setextHeading2 = { model -> MathAwareHeader(model, model.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
        blockQuote = { model ->
            if (astSliceContainsMath(model.content, model.node)) {
                MathAwareBlockQuote(model.content, model.node, model.typography.quote)
            } else {
                MarkdownBlockQuote(model.content, model.node, model.typography.quote)
            }
        },
        unorderedList = { model -> ChatUnorderedList(model) },
        orderedList = { model -> ChatOrderedList(model) },
        table = { model -> ChatMarkdownTable(model) },
    )
    val scopeId = remember { "m${System.identityHashCode(Any())}" }
    CompositionLocalProvider(
        LocalUriHandler provides linkHandler,
        LocalSelectionScope provides scopeId,
    ) {
        val onlyMarkdown = parts.singleOrNull() as? MarkdownPart.Markdown
        if (onlyMarkdown != null) {
            Markdown(
                content = onlyMarkdown.text,
                typography = typography,
                dimens = dimens,
                components = components,
                modifier = modifier,
            )
        } else {
            Column(modifier = modifier) {
                parts.forEachIndexed { index, part ->
                    CompositionLocalProvider(LocalSelectionScope provides "$scopeId:$index") {
                        when (part) {
                            is MarkdownPart.Markdown -> Markdown(
                                content = part.text,
                                typography = typography,
                                dimens = dimens,
                                components = components,
                            )
                            is MarkdownPart.Latex -> LatexBlock(
                                latex = part.latex,
                                display = part.display,
                                modifier = trackRenderedSegment("$scopeId:$index:math", part.latex, null),
                            )
                            is MarkdownPart.Mermaid -> MermaidBlock(part.source)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方正文大约 16sp，二级标题大约 20sp 加粗，一行能放下二十个字左右。
 * 库默认把 h2 映射成 displayMedium，会把「一、先厘清…」撑成三行大标题。
 */
@Composable
private fun chatMarkdownTypography(textColor: Color, compact: Boolean): MarkdownTypography {
    val base = markdownTypography()
    val bodySize = if (compact) 15.sp else 16.sp
    val bodyLine = if (compact) 24.sp else 26.sp
    val h1Size = if (compact) 17.sp else 22.sp
    val h2Size = if (compact) 16.sp else 20.sp
    val h3Size = if (compact) 15.sp else 17.sp
    val body = base.text.copy(fontSize = bodySize, lineHeight = bodyLine, fontWeight = FontWeight.Normal)
    return markdownTypography(
        h1 = sized(base.h1, h1Size, if (compact) 26.sp else 30.sp, FontWeight.Bold),
        h2 = sized(base.h2, h2Size, if (compact) 24.sp else 28.sp, FontWeight.Bold),
        h3 = sized(base.h3, h3Size, bodyLine, FontWeight.SemiBold),
        h4 = sized(base.h4, bodySize, bodyLine, FontWeight.SemiBold),
        h5 = sized(base.h5, bodySize, bodyLine, FontWeight.SemiBold),
        h6 = sized(base.h6, bodySize, bodyLine, FontWeight.SemiBold),
        text = body,
        paragraph = base.paragraph.copy(fontSize = bodySize, lineHeight = bodyLine),
        quote = base.quote.copy(fontSize = bodySize, lineHeight = bodyLine),
        ordered = base.ordered.copy(fontSize = bodySize, lineHeight = bodyLine),
        bullet = base.bullet.copy(fontSize = bodySize, lineHeight = bodyLine),
        list = base.list.copy(fontSize = bodySize, lineHeight = bodyLine),
    ).let { styled ->
        // 正文色跟随主题。思考区传入灰色。库默认样式有时不带 onSurface。
        val color = if (textColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else textColor
        markdownTypography(
            h1 = styled.h1.copy(color = color),
            h2 = styled.h2.copy(color = color),
            h3 = styled.h3.copy(color = color),
            h4 = styled.h4.copy(color = color),
            h5 = styled.h5.copy(color = color),
            h6 = styled.h6.copy(color = color),
            text = styled.text.copy(color = color),
            paragraph = styled.paragraph.copy(color = color),
            quote = styled.quote.copy(color = color),
            ordered = styled.ordered.copy(color = color),
            bullet = styled.bullet.copy(color = color),
            list = styled.list.copy(color = color),
            code = styled.code,
            inlineCode = styled.inlineCode,
            link = if (textColor == Color.Unspecified) styled.link else styled.link.copy(color = color),
        )
    }
}

private fun sized(
    style: androidx.compose.ui.text.TextStyle,
    size: TextUnit,
    line: TextUnit,
    weight: FontWeight,
) = style.copy(fontSize = size, lineHeight = line, fontWeight = weight)

/**
 * 只打开 http/https。拦住 file://、intent:// 等非预期协议（§8）。
 * 有支持 Custom Tabs 的浏览器时走应用内标签页，否则交给系统浏览器。
 */
fun openHttpUrl(context: Context, raw: String) {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return
    val scheme = uri.scheme?.lowercase() ?: return
    if (scheme != "http" && scheme != "https") return
    if (uri.host.isNullOrBlank()) return
    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val customTabsPackage = runCatching { customTabsPackage(context, view) }.getOrNull()
    if (customTabsPackage != null) {
        view.setPackage(customTabsPackage)
        view.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        view.putExtra("android.support.customtabs.extra.SESSION", null as Bundle?)
    }
    runCatching { context.startActivity(view) }
}

private fun customTabsPackage(context: Context, viewIntent: Intent): String? {
    val pm = context.packageManager
    val browsers = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return browsers.firstOrNull { info ->
        val service = Intent("android.support.customtabs.action.CustomTabsService").setPackage(info.activityInfo.packageName)
        pm.resolveService(service, 0) != null
    }?.activityInfo?.packageName
}
