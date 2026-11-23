package com.david.deepdesk.feature.chat.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import com.david.deepdesk.core.model.Citation
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.SearchHit
import com.david.deepdesk.core.model.ServerToolKind
import com.david.deepdesk.core.ui.DeepSeekColors
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.LocalDeepSeekDark
import com.david.deepdesk.core.ui.MarkdownText
import com.david.deepdesk.core.ui.openHttpUrl
import com.david.deepdesk.core.ui.R as UiR

/**
 * 回复块渲染（A3）：思考折叠、工具过程、来源卡片。
 * 思考块可折叠；公式与 Mermaid 由 MarkdownText 渲染。
 */
@Composable
fun ReplyBlocks(
    blocks: List<ReplyBlock>,
    streaming: Boolean,
    autoCollapseThinking: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var index = 0
        while (index < blocks.size) {
            val block = blocks[index]
            val run = blocks.drop(index).takeWhile { it.isReasoningBlock() }
            if (run.any { it is ReplyBlock.Thinking }) {
                ThinkingGroup(run, streaming, autoCollapseThinking)
                index += run.size
            } else {
                BlockContent(block)
                index++
            }
        }
    }
}

@Composable
private fun BlockContent(block: ReplyBlock) {
    when (block) {
        is ReplyBlock.Thinking -> ThinkingGroup(listOf(block), streaming = false, autoCollapse = true)
        is ReplyBlock.ServerToolUse -> ServerToolUseBlock(block)
        is ReplyBlock.WebSearchResult -> WebSearchResultBlock(block)
        is ReplyBlock.WebFetchResult -> WebFetchResultBlock(block)
        is ReplyBlock.Text -> TextBlock(block)
        is ReplyBlock.Raw -> Unit
    }
}

// ------------------------------ 思考块（F03） ------------------------------

@Composable
private fun ThinkingGroup(
    blocks: List<ReplyBlock>,
    streaming: Boolean,
    autoCollapse: Boolean,
) {
    val thinking = streaming && blocks.filterIsInstance<ReplyBlock.Thinking>().any { it.running }
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: if (thinking) true else !autoCollapse
    val durationLabel = thinkingDurationMs(blocks)?.let { ms ->
        val seconds = ((ms + 500L) / 1000L).coerceAtLeast(1L)
        stringResource(UiR.string.thinking_done_seconds, seconds.toInt())
    } ?: stringResource(UiR.string.thinking_done)
    val traceColor = if (LocalDeepSeekDark.current) DeepSeekColors.ThinkingDark else DeepSeekColors.ThinkingLight
    val pieces = remember(blocks) { tracePieces(blocks) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { userExpanded = !expanded },
        ) {
            Text(
                text = if (thinking) stringResource(UiR.string.thinking_running) else durationLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = traceColor,
            )
            if (thinking) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
            DsIcon(
                id = if (expanded) UiR.drawable.ic_chevron_down_outline_20 else UiR.drawable.ic_chevron_right_outline_20,
                contentDescription = stringResource(if (expanded) UiR.string.collapse else UiR.string.expand),
                tint = traceColor,
                modifier = Modifier.size(20.dp),
            )
        }
        // 收起时只留标题。搜索卡片和阅读链接都在展开后的时间线里，不铺在正文前面。
        if (expanded && pieces.isNotEmpty()) {
            ThinkingTrace(pieces, traceColor)
        } else {
            pieces.filterIsInstance<TracePiece.SearchFailed>().distinct().forEach { failure ->
                SearchFailureRow(failure.code)
            }
        }
    }
}

/** 展开后的思考：左侧灰线，正文按 Markdown 渲染，搜索和浏览状态按原文插在中间。 */
@Composable
private fun ThinkingTrace(pieces: List<TracePiece>, color: Color) {
    var sheet by remember { mutableStateOf<TraceSheet?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .drawBehind {
                val x = 1.dp.toPx()
                drawLine(
                    color = color.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is TracePiece.Narrative -> MarkdownText(
                    content = narrativeMarkdown(piece.text),
                    textColor = color,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                is TracePiece.Searched -> TraceStatusRow(
                    icon = UiR.drawable.ic_search_outline_16,
                    label = if (piece.hits.isEmpty()) {
                        stringResource(UiR.string.searching_web)
                    } else {
                        stringResource(UiR.string.searched_pages, piece.hits.size)
                    },
                    pages = piece.hits.map { it.url to it.siteIcon },
                    color = color,
                    onClick = if (piece.hits.isEmpty()) null else {
                        { sheet = TraceSheet.Search(piece.hits) }
                    },
                )
                is TracePiece.Browsed -> TraceStatusRow(
                    icon = UiR.drawable.ic_page_outline_16,
                    label = if (piece.pages.isEmpty()) {
                        stringResource(UiR.string.reading_page)
                    } else {
                        stringResource(UiR.string.browsed_pages, piece.pages.size)
                    },
                    pages = piece.pages.map { it.url to it.siteIcon },
                    color = color,
                    onClick = if (piece.pages.isEmpty()) null else {
                        { sheet = TraceSheet.Browsed(piece.pages) }
                    },
                )
                is TracePiece.SearchFailed -> SearchFailureRow(piece.code)
                is TracePiece.FetchFailed -> ToolErrorRow(stringResource(UiR.string.fetch_failed, piece.code))
            }
        }
    }
    // 弹窗是另一个窗口，导航栏高度在里面经常是 0，侧滑返回也交给后面的页面。间距和返回都在这里接。
    val bottomInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    BackHandler(enabled = sheet != null) { sheet = null }
    sheet?.let { current ->
        TraceResultSheet(current, bottomInset = bottomInset, onDismiss = { sheet = null })
    }
}

@Composable
private fun TraceStatusRow(
    icon: Int,
    label: String,
    pages: List<Pair<String, String?>>,
    color: Color,
    onClick: (() -> Unit)?,
) {
    val marks = pages.distinctBy { pageHost(it.first) }.take(4)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    ) {
        DsIcon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (marks.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                marks.forEachIndexed { index, (url, siteIcon) ->
                    Box(Modifier.zIndex((marks.size - index).toFloat())) {
                        SiteMark(url, siteIcon)
                    }
                }
            }
        }
    }
}

private fun pageHost(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.").ifBlank { url }

@Composable
internal fun SiteMark(url: String, icon: String?, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val host = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")
    }
    val letter = host.firstOrNull()?.uppercaseChar()?.toString() ?: "·"
    val bg = SiteMarkColors[(host.hashCode() and Int.MAX_VALUE) % SiteMarkColors.size]
    val model = icon?.takeIf { it.isNotBlank() }
        ?: host.takeIf { it.isNotEmpty() }?.let { "https://$it/favicon.ico" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = Color.White,
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

private val SiteMarkColors = listOf(
    Color(0xFF5B8DEF),
    Color(0xFFE25B45),
    Color(0xFF3AA76D),
    Color(0xFF8A6AD6),
    Color(0xFFD4920A),
)

/**
 * 点「已搜索到 N 个网页」或「已浏览 N 个页面」后的底部列表。
 * 标题分别是「搜索结果」「已浏览搜索结果」，一行是站点图标、站名、日期，下面是标题和摘要。
 */
@Composable
private fun TraceResultSheet(sheet: TraceSheet, bottomInset: Dp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = when (sheet) {
        is TraceSheet.Search -> stringResource(UiR.string.search_results)
        is TraceSheet.Browsed -> stringResource(UiR.string.browsed_results)
    }
    val rows = when (sheet) {
        is TraceSheet.Search -> sheet.hits.map { hit ->
            ResultLine(hit.url, hit.title, hit.snippet, hit.siteName, hit.siteIcon, hit.pageAge, hit.imageUrl)
        }
        is TraceSheet.Browsed -> sheet.pages.map { page ->
            ResultLine(page.url, page.title, page.snippet, page.siteName, page.siteIcon, page.pageAge)
        }
    }
    val scrim = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().dismissOnEdgeSwipe(onDismiss)) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.08f)
                    .clickable(interactionSource = scrim, indication = null, onClick = onDismiss),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize().padding(bottom = bottomInset + 16.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp),
                        ) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                Surface(
                                    onClick = onDismiss,
                                    shape = CircleShape,
                                    color = if (LocalDeepSeekDark.current) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        DsIcon(
                                            UiR.drawable.ic_close_outline_16,
                                            contentDescription = stringResource(UiR.string.markdown_table_close),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(rows) { _, line ->
                            ResultLineRow(line) { openHttpUrl(context, line.url) }
                        }
                    }
                }
            }
        }
    }
}

/** 系统侧滑被弹窗吃掉时，从左右边缘向内滑一段同样关闭。 */
private fun Modifier.dismissOnEdgeSwipe(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    val edgePx = 32.dp.toPx()
    val triggerPx = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val fromLeft = down.position.x <= edgePx
        val fromRight = down.position.x >= size.width - edgePx
        if (!fromLeft && !fromRight) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            val inward = if (fromLeft) dx else -dx
            if (inward > triggerPx && inward > abs(dy)) {
                change.consume()
                onDismiss()
                return@awaitEachGesture
            }
            if (abs(dy) > triggerPx && abs(dy) > abs(dx)) return@awaitEachGesture
        }
    }
}

private data class ResultLine(
    val url: String,
    val title: String?,
    val snippet: String?,
    val siteName: String?,
    val siteIcon: String?,
    val pageAge: String?,
    val imageUrl: String? = null,
)

@Composable
private fun ResultLineRow(line: ResultLine, onClick: () -> Unit) {
    val host = remember(line.url) {
        runCatching { Uri.parse(line.url).host }.getOrNull().orEmpty().removePrefix("www.").ifBlank { line.url }
    }
    val site = line.siteName?.takeIf { it.isNotBlank() } ?: host
    val headline = line.title?.takeIf { it.isNotBlank() } ?: site
    val date = formatPageAge(line.pageAge)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SiteMark(line.url, line.siteIcon, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = site,
                color = muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (date != null) {
                Spacer(Modifier.width(8.dp))
                Text(date, color = muted, fontSize = 13.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = headline,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SearchResultImage(line.imageUrl, Modifier.padding(top = 6.dp))
        val snippet = line.snippet?.trim().orEmpty()
        if (snippet.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = snippet,
                color = muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------ server_tool_use（F04/F05） ------------------------------

@Composable
private fun ServerToolUseBlock(block: ReplyBlock.ServerToolUse) {
    val label = when {
        block.kind == ServerToolKind.WEB_SEARCH && block.query != null ->
            stringResource(UiR.string.searching_query, block.query.orEmpty())
        block.kind == ServerToolKind.WEB_SEARCH && block.action == "find_in_page" ->
            stringResource(UiR.string.find_in_page)
        block.kind == ServerToolKind.WEB_FETCH || block.url != null ->
            stringResource(UiR.string.reading_link, block.url.orEmpty())
        else -> when (block.kind) {
            ServerToolKind.WEB_SEARCH -> stringResource(UiR.string.searching_web)
            ServerToolKind.WEB_FETCH -> stringResource(UiR.string.reading_page)
            ServerToolKind.UNKNOWN -> stringResource(UiR.string.using_tool, block.name)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (block.running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
        } else {
            DsIcon(
                UiR.drawable.ic_search_outline_16,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------ 搜索结果来源卡片（F04） ------------------------------

@Composable
private fun WebSearchResultBlock(block: ReplyBlock.WebSearchResult) {
    block.errorCode?.let { SearchFailureRow(it) }
    if (block.hits.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 胶囊来源数量（对齐官方 App「N 个网页」）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                stringResource(UiR.string.read_pages, block.hits.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            block.hits.take(8).forEach { hit ->
                SourceCard(hit)
            }
        }
    }
}

@Composable
private fun SearchFailureRow(code: String) {
    val message = when (code) {
        "unavailable" -> UiR.string.search_unavailable
        "too_many_requests" -> UiR.string.search_rate_limited
        else -> UiR.string.search_failed
    }
    ToolErrorRow(stringResource(message))
}

@Composable
private fun WebFetchResultBlock(block: ReplyBlock.WebFetchResult) {
    if (block.errorCode != null) {
        ToolErrorRow(stringResource(UiR.string.fetch_failed, block.errorCode.orEmpty()))
        return
    }
    val url = block.url ?: return
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        DsIcon(
            UiR.drawable.ic_exit_out_regular,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = block.title ?: url,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { com.david.deepdesk.core.ui.openHttpUrl(context, url) },
        )
    }
}

@Composable
private fun SourceCard(hit: SearchHit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.width(220.dp),
    ) {
        Column(
            Modifier
                .clickable { com.david.deepdesk.core.ui.openHttpUrl(context, hit.url) }
                .padding(10.dp),
        ) {
            SearchResultImage(hit.imageUrl, Modifier.padding(bottom = 6.dp))
            Text(
                text = hit.siteName ?: hit.url.substringAfter("://").substringBefore("/"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = hit.title ?: hit.url,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            hit.snippet?.let {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchResultImage(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) return
    val uri = remember(url) { Uri.parse(url) }
    if (uri.scheme?.lowercase() !in listOf("https", "http") || uri.host.isNullOrBlank()) return

    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun ToolErrorRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ------------------------------ 正文 + 引用（F04） ------------------------------

@Composable
private fun TextBlock(block: ReplyBlock.Text) {
    Column {
        MarkdownText(content = block.text, modifier = Modifier.fillMaxWidth())
        if (block.citations.isNotEmpty()) {
            HorizontalDivider(
                Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            CitationsRow(block.citations)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CitationsRow(citations: List<Citation>) {
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        citations.distinctBy { it.url }.forEachIndexed { index, citation ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { com.david.deepdesk.core.ui.openHttpUrl(context, citation.url) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = citation.title ?: citation.url.substringAfter("://").substringBefore("/"),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
        }
    }
}
