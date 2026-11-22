package com.david.deepdesk.feature.chat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.ReplyStatus
import java.io.File
import com.david.deepdesk.core.ui.DeepSeekColors
import com.david.deepdesk.core.ui.LocalDeepSeekDark
import com.david.deepdesk.core.ui.LocalRenderedSelection
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.core.ui.RenderedSelectionController
import com.david.deepdesk.feature.chat.UiMessage

/**
 * 单条消息渲染：用户靠右气泡、助手靠左全宽（对齐官方 Demo 交互）。
 * 支持长按复制、分享、编辑提问、重新生成、切换分支（F09）。
 */
@Composable
fun MessageRow(
    item: UiMessage,
    streaming: Boolean,
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onRequestEdit: (String) -> Unit,
    onToggleTts: () -> Unit,
    onCopied: () -> Unit = {},
    autoCollapseThinking: Boolean = true,
    onContinue: (() -> Unit)? = null,
    onUserFold: (Boolean) -> Unit = {},
) {
    if (item.message.role == "user") {
        UserMessageRow(item, onRequestEdit, onCopied, onUserFold)
    } else {
        AssistantMessageRow(
            item, streaming, isSpeaking, onRegenerate, onToggleTts, onCopied,
            autoCollapseThinking, onContinue,
        )
    }
}

@Composable
private fun UserMessageRow(
    item: UiMessage,
    onRequestEdit: (String) -> Unit,
    onCopied: () -> Unit,
    onFold: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val imagePreview = LocalImagePreview.current
    val text = item.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    val dark = LocalDeepSeekDark.current
    val bubble = if (dark) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight
    // 展开状态跟消息走。宽度还没量到、或者条目滚出屏幕再回来，都不能自己收起。
    var expanded by rememberSaveable(item.message.id) { mutableStateOf(false) }
    var selecting by remember(item.message.id) { mutableStateOf(false) }
    var bubbleWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    val padPx = with(density) { 24.dp.roundToPx() }
    val maxBubblePx = with(density) { 320.dp.roundToPx() }
    val textWidthPx = (min(bubbleWidth, maxBubblePx) - padPx).coerceAtLeast(0)
    val freshFold = when {
        text.isBlank() -> FoldMeasure.None
        textWidthPx <= 0 -> FoldMeasure.Unknown
        else -> {
            val constraints = Constraints(maxWidth = textWidthPx)
            val collapsed = measurer.measure(
                text = text,
                style = textStyle,
                overflow = TextOverflow.Clip,
                maxLines = UserMessageFoldLines,
                constraints = constraints,
            )
            if (!collapsed.hasVisualOverflow) {
                FoldMeasure.None
            } else {
                val full = measurer.measure(
                    text = text,
                    style = textStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    constraints = constraints,
                )
                FoldMeasure.Heights(collapsed.size.height, full.size.height)
            }
        }
    }
    var latchedFold by remember(item.message.id, text) { mutableStateOf<FoldMeasure>(FoldMeasure.Unknown) }
    LaunchedEffect(freshFold) {
        if (freshFold != FoldMeasure.Unknown) latchedFold = freshFold
    }
    val foldMeasure = if (freshFold != FoldMeasure.Unknown) freshFold else latchedFold
    val foldHeights = (foldMeasure as? FoldMeasure.Heights)?.let { it.collapsed to it.full }
    val canFold = foldHeights != null
    // 只有展开才放开高度。选择文本、宽度暂时量不到，都不许把气泡撑开再弹回收起。
    val extraPx = if (canFold && expanded) with(density) { 26.dp.roundToPx() } else 0
    val targetHeightPx = foldHeights?.let { (collapsedPx, fullPx) ->
        (if (expanded) fullPx else collapsedPx) + extraPx
    }
    val heightAnim = remember(item.message.id, text) { Animatable(Float.NaN) }
    LaunchedEffect(targetHeightPx) {
        val target = targetHeightPx?.toFloat() ?: return@LaunchedEffect
        if (heightAnim.value.isNaN()) heightAnim.snapTo(target)
        else heightAnim.animateTo(target, tween(300, easing = FastOutSlowInEasing))
    }
    val clipReady = canFold && !heightAnim.value.isNaN()
    var selStart by remember(item.message.id, text) { mutableIntStateOf(0) }
    var selEnd by remember(item.message.id, text) { mutableIntStateOf(0) }
    var textLayout by remember(item.message.id, text) { mutableStateOf<TextLayoutResult?>(null) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var pressLocal by remember { mutableStateOf(Offset.Zero) }
    val copyLabel = stringResource(UiR.string.copy)
    val selectTextLabel = stringResource(UiR.string.select_text)
    val modifyLabel = stringResource(UiR.string.modify)
    val shareLabel = stringResource(UiR.string.share)
    val selectAllLabel = stringResource(UiR.string.select_all)
    BackHandler(enabled = selecting) { selecting = false }

    val images = item.attachments.filter { it.isPreviewableImage() }
    val files = item.attachments.filterNot { it.isPreviewableImage() }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (files.isNotEmpty()) {
            UserFileCards(files)
        }
        if (text.isBlank() && images.isEmpty() && !item.message.edited) return@Column
        Box(Modifier.onGloballyPositioned { boxOrigin = it.positionInWindow() }) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = bubble,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .onSizeChanged { bubbleWidth = it.width },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (item.message.edited) {
                        Text(
                            stringResource(UiR.string.edited_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    UserAttachments(images, onOpenImage = { imagePreview?.open(it) })
                    if (text.isNotBlank()) {
                        Column(
                            modifier = if (clipReady) {
                                Modifier
                                    .height(with(density) { heightAnim.value.toDp() })
                                    .clipToBounds()
                            } else {
                                Modifier
                            },
                        ) {
                        Text(
                            text = text,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (clipReady || expanded || (canFold.not() && bubbleWidth > 0)) {
                                Int.MAX_VALUE
                            } else {
                                UserMessageFoldLines
                            },
                            overflow = TextOverflow.Clip,
                            onTextLayout = { textLayout = it },
                            modifier = Modifier
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .onGloballyPositioned { textOrigin = it.positionInWindow() }
                                .drawBehind {
                                    val layout = textLayout ?: return@drawBehind
                                    if (!selecting) return@drawBehind
                                    val rect = selectionRect(layout, selStart, selEnd)
                                    if (rect == androidx.compose.ui.geometry.Rect.Zero) return@drawBehind
                                    drawPath(
                                        layout.getPathForRange(
                                            minOf(selStart, selEnd).coerceIn(0, text.length),
                                            maxOf(selStart, selEnd).coerceIn(0, text.length),
                                        ),
                                        SelectionHighlightColor,
                                    )
                                }
                                .pointerInput(item.message.id, selecting, expanded, showMenu, text) {
                                    detectTapGestures(
                                        onTap = {
                                            if (selecting) selecting = false
                                            else if (!showMenu && text.isNotBlank()) onRequestEdit(text)
                                        },
                                        onLongPress = { local ->
                                            pressLocal = local
                                            selecting = false
                                            showMenu = true
                                        },
                                    )
                                },
                        )
                        if (canFold && expanded) {
                            Spacer(Modifier.height(26.dp))
                        }
                        }
                    }
                }
            }
            if (canFold) {
                UserMessageFoldButton(
                    expanded = expanded,
                    dark = dark,
                    onClick = {
                        val expanding = !expanded
                        selecting = false
                        expanded = expanding
                        onFold(expanding)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp)
                        .zIndex(5f),
                )
            }
            val layout = textLayout
            if (selecting && layout != null && text.isNotBlank()) {
                val textInBox = textOrigin - boxOrigin
                SelectionDragLayer(
                    textInBox = textInBox,
                    layout = layout,
                    textLength = text.length,
                    start = selStart,
                    end = selEnd,
                    onStart = { selStart = it },
                    onEnd = { selEnd = it },
                    onDismiss = { selecting = false },
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(3f),
                )
                val startAnchor = selectionHandleAnchor(layout, selStart, isStart = true)
                val endAnchor = selectionHandleAnchor(layout, selEnd, isStart = false)
                SelectionHandleMark(
                    anchorInParent = textInBox + startAnchor.hotspot,
                    isLeft = startAnchor.isLeft,
                    modifier = Modifier.zIndex(4f),
                )
                SelectionHandleMark(
                    anchorInParent = textInBox + endAnchor.hotspot,
                    isLeft = endAnchor.isLeft,
                    modifier = Modifier.zIndex(4f),
                )
                val bounds = selectionRect(layout, selStart, selEnd)
                val handlePx = with(density) { SelectionHandleSize.toPx() }
                SelectionActionBar(
                    centerXInWindow = textOrigin.x + bounds.center.x,
                    bottomInWindow = textOrigin.y + selectionChromeBottom(layout, selStart, selEnd, handlePx),
                    onCopy = {
                        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
                        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
                        if (b > a) {
                            clipboard.setText(AnnotatedString(text.substring(a, b)))
                            onCopied()
                        }
                    },
                    onSelectAll = {
                        selStart = 0
                        selEnd = text.length
                        expanded = true
                    },
                    copyLabel = copyLabel,
                    selectAllLabel = selectAllLabel,
                    copyIcon = UiR.drawable.ic_copy_outline,
                    selectAllIcon = UiR.drawable.ic_select_all_outline_20,
                )
            }
            if (showMenu) {
                val actions = buildList {
                    add(
                        MessageAction(UiR.drawable.ic_copy_outline, copyLabel) {
                            clipboard.setText(AnnotatedString(text))
                            onCopied()
                            showMenu = false
                        },
                    )
                    if (text.isNotBlank()) {
                        add(
                            MessageAction(UiR.drawable.ic_select_outline, selectTextLabel) {
                                showMenu = false
                                val range = if (expanded) {
                                    val offset = textLayout?.getOffsetForPosition(pressLocal) ?: 0
                                    paragraphRangeAt(text, offset)
                                } else {
                                    firstParagraphRange(text)
                                }
                                if (range.second > range.first) {
                                    selStart = range.first
                                    selEnd = range.second
                                    selecting = true
                                }
                            },
                        )
                    }
                    add(
                        MessageAction(UiR.drawable.ic_edit_outline_20, modifyLabel) {
                            showMenu = false
                            if (text.isNotBlank()) onRequestEdit(text)
                        },
                    )
                    add(
                        MessageAction(UiR.drawable.ic_share_outline_20, shareLabel) {
                            shareText(context, text)
                            showMenu = false
                        },
                    )
                }
                MessageActionPopup(
                    pressInWindow = textOrigin + pressLocal,
                    actions = actions,
                    onDismiss = { showMenu = false },
                )
            }
        }
    }
}

/** 官方按 8 行测量收起高度，再用 300ms 把气泡高度收到这个值。 */
@Composable
private fun SelectionDragLayer(
    textInBox: Offset,
    layout: TextLayoutResult,
    textLength: Int,
    start: Int,
    end: Int,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startState = rememberUpdatedState(start)
    val endState = rememberUpdatedState(end)
    val layoutState = rememberUpdatedState(layout)
    val originState = rememberUpdatedState(textInBox)
    val lengthState = rememberUpdatedState(textLength)
    Box(
        modifier.pointerInput(Unit) {
            val touch = SelectionHandleTouch.toPx()
            val touch2 = touch * touch
            val radius = SelectionHandleSize.toPx() / 2f
            fun hit(parentPos: Offset): Int? {
                val textPos = parentPos - originState.value
                val layoutNow = layoutState.value
                val startC = selectionHandleGrab(
                    selectionHandleAnchor(layoutNow, startState.value, isStart = true),
                    radius,
                )
                val endC = selectionHandleGrab(
                    selectionHandleAnchor(layoutNow, endState.value, isStart = false),
                    radius,
                )
                val ds = squaredDistance(textPos, startC)
                val de = squaredDistance(textPos, endC)
                return when {
                    ds <= touch2 && ds <= de -> -1
                    de <= touch2 -> 1
                    else -> null
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val which = hit(down.position)
                if (which == null) {
                    val up = waitForUpOrCancellation()
                    if (up != null) onDismiss()
                    return@awaitEachGesture
                }
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val textPos = change.position - originState.value
                    val offset = layoutState.value.getOffsetForPosition(textPos)
                    val length = lengthState.value
                    if (which == -1) {
                        val endNow = endState.value
                        onStart(offset.coerceIn(0, (endNow - 1).coerceAtLeast(0)))
                    } else {
                        val startNow = startState.value
                        onEnd(offset.coerceIn((startNow + 1).coerceAtMost(length), length))
                    }
                }
            }
        },
    )
}

/** 官方选区手柄：圆头挂在行下，直边贴住这一端。左侧圆头在端点左边。 */
@Composable
private fun SelectionHandleMark(
    anchorInParent: Offset,
    isLeft: Boolean,
    modifier: Modifier = Modifier,
) {
    val diameter = SelectionHandleSize
    val color = if (LocalDeepSeekDark.current) SelectionHandleColorDark else SelectionHandleColor
    Box(
        modifier
            .offset {
                val d = diameter.toPx()
                val left = if (isLeft) anchorInParent.x - d else anchorInParent.x
                IntOffset(left.roundToInt(), anchorInParent.y.roundToInt())
            }
            .size(diameter)
            .drawBehind {
                val radius = size.width / 2f
                drawCircle(color, radius = radius, center = Offset(radius, radius))
                val stem = if (isLeft) Offset(radius, 0f) else Offset.Zero
                drawRect(color, topLeft = stem, size = Size(radius, radius))
            },
    )
}

private fun squaredDistance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

private const val UserMessageFoldLines = 8

private sealed interface FoldMeasure {
    data object Unknown : FoldMeasure
    data object None : FoldMeasure
    data class Heights(val collapsed: Int, val full: Int) : FoldMeasure
}

/** 官方按空行分段：`\n` 后只含空格再接一个 `\n`。收起时选择文本只取第一段。 */
private val ParagraphBreak = Regex("\n[ \t\r]*\n")

private fun firstParagraphRange(text: String): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    val gap = ParagraphBreak.find(text)
    val rawEnd = gap?.range?.first ?: text.length
    return trimParagraph(text, 0, rawEnd) ?: (0 to text.length)
}

private fun paragraphRangeAt(text: String, offset: Int): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    val index = offset.coerceIn(0, text.length)
    var cursor = 0
    for (gap in ParagraphBreak.findAll(text)) {
        if (index < gap.range.first) {
            return trimParagraph(text, cursor, gap.range.first) ?: firstParagraphRange(text)
        }
        cursor = gap.range.last + 1
    }
    return trimParagraph(text, cursor, text.length) ?: firstParagraphRange(text)
}

private fun trimParagraph(text: String, start: Int, end: Int): Pair<Int, Int>? {
    var from = start
    var to = end
    while (from < to && text[from].isWhitespace()) from++
    while (to > from && text[to - 1].isWhitespace()) to--
    return if (to > from) from to to else null
}

@Composable
private fun UserMessageFoldButton(
    expanded: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (dark) Color(0xFF3A3D46) else Color.White
    // 箭头只在绘制里旋转。点击层不要做 graphicsLayer 旋转，展开后命中会偏掉。
    // 也不要在 Initial 里消费按下再等默认的抬起：那次按下已被消费，抬起检测会当成取消，展开和收起都不会触发。
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .size(width = 30.dp, height = 22.dp)
            .shadow(1.dp, shape)
            .clip(shape)
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DsIcon(
            id = UiR.drawable.ic_chevron_down_outline_20,
            contentDescription = stringResource(if (expanded) UiR.string.collapse else UiR.string.expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .drawWithContent {
                    if (expanded) {
                        rotate(180f, pivot = center) {
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        drawContent()
                    }
                },
        )
    }
}

private fun Attachment.isPreviewableImage(): Boolean {
    val path = localPath
    return isImage && path != null && File(path).exists()
}

private fun Attachment.isPdf(): Boolean {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return ext == "pdf" || mimeType.equals("application/pdf", ignoreCase = true)
}

/** 官方用户消息上方的文件卡片：图标、截断文件名、大小。PDF 原件可以没有。 */
@Composable
private fun UserFileCards(files: List<Attachment>) {
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { FileAttachmentCard(it) }
    }
}

@Composable
private fun FileAttachmentCard(att: Attachment) {
    val pdf = att.isPdf()
    Row(
        modifier = Modifier
            .width(188.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (pdf) {
                Icon(
                    painter = painterResource(UiR.drawable.ic_file_pdf),
                    contentDescription = stringResource(UiR.string.file_pdf),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    painter = painterResource(UiR.drawable.ic_file_generic),
                    contentDescription = att.displayName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = att.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = formatAttachmentSize(att.sizeBytes)
            if (size.isNotEmpty()) {
                Text(
                    text = size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> trimSize(mb / 1024.0) + "GB"
        mb >= 1 -> trimSize(mb) + "MB"
        bytes >= 1024 -> trimSize(bytes / 1024.0) + "KB"
        else -> "${bytes}B"
    }
}

private fun trimSize(value: Double): String {
    val pattern = if (value >= 10.0) "%.1f" else "%.2f"
    return String.format(java.util.Locale.US, pattern, value)
}

@Composable
private fun UserAttachments(
    attachments: List<Attachment>,
    onOpenImage: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        attachments.forEach { att ->
            val path = att.localPath ?: return@forEach
            AsyncImage(
                model = File(path),
                contentDescription = att.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .sharedPreviewImage(path)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenImage(path) },
            )
        }
    }
}

@Composable
private fun AssistantMessageRow(
    item: UiMessage,
    streaming: Boolean,
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onToggleTts: () -> Unit,
    onCopied: () -> Unit,
    autoCollapseThinking: Boolean,
    onContinue: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val text = item.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    var showMenu by remember { mutableStateOf(false) }
    val selection = remember(item.message.id) { RenderedSelectionController() }
    var columnOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressLocal by remember { mutableStateOf(Offset.Zero) }
    val copyLabel = stringResource(UiR.string.copy)
    val selectTextLabel = stringResource(UiR.string.select_text)
    val regenerateLabel = stringResource(UiR.string.regenerate)
    val readAloudLabel = stringResource(UiR.string.read_aloud)
    val shareLabel = stringResource(UiR.string.share)
    val selectAllLabel = stringResource(UiR.string.select_all)
    BackHandler(enabled = selection.active) { selection.dismiss() }
    LaunchedEffect(text) { selection.dismiss() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.onGloballyPositioned { origin = it.positionInWindow(); boxOrigin = it.positionInWindow() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { columnOrigin = it.positionInWindow() }
                    .pointerInput(item.message.id) {
                        detectTapGestures(
                            onTap = { if (selection.active) selection.dismiss() },
                            onLongPress = { local ->
                                selection.dismiss()
                                pressLocal = local
                                showMenu = true
                            },
                        )
                    },
            ) {
                CompositionLocalProvider(LocalRenderedSelection provides selection) {
                    ReplyBlocks(
                        blocks = item.blocks,
                        streaming = streaming,
                        autoCollapseThinking = autoCollapseThinking,
                    )
                }
            }
            if (selection.active) {
                val layout = selection.handleLayout
                val handleText = selection.handleText
                if (!selection.selectAll && layout != null && handleText.isNotEmpty()) {
                    val textInBox = selection.handleOrigin - boxOrigin
                    SelectionDragLayer(
                        textInBox = textInBox,
                        layout = layout,
                        textLength = handleText.length,
                        start = selection.rangeStart,
                        end = selection.rangeEnd,
                        onStart = { selection.rangeStart = it },
                        onEnd = { selection.rangeEnd = it },
                        onDismiss = { selection.dismiss() },
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(3f),
                    )
                    val startAnchor = selectionHandleAnchor(layout, selection.rangeStart, isStart = true)
                    val endAnchor = selectionHandleAnchor(layout, selection.rangeEnd, isStart = false)
                    SelectionHandleMark(
                        anchorInParent = textInBox + startAnchor.hotspot,
                        isLeft = startAnchor.isLeft,
                        modifier = Modifier.zIndex(4f),
                    )
                    SelectionHandleMark(
                        anchorInParent = textInBox + endAnchor.hotspot,
                        isLeft = endAnchor.isLeft,
                        modifier = Modifier.zIndex(4f),
                    )
                } else {
                    Box(
                        Modifier
                            .matchParentSize()
                            .zIndex(3f)
                            .pointerInput(selection.selectedKey, selection.selectAll) {
                                detectTapGestures(onTap = { selection.dismiss() })
                            },
                    )
                    if (!selection.selectAll && selection.handleSize != IntSize.Zero) {
                        val textInBox = selection.handleOrigin - boxOrigin
                        val height = selection.handleSize.height.toFloat()
                        val width = selection.handleSize.width.toFloat()
                        SelectionHandleMark(
                            anchorInParent = textInBox + Offset(0f, height),
                            isLeft = true,
                            modifier = Modifier.zIndex(4f),
                        )
                        SelectionHandleMark(
                            anchorInParent = textInBox + Offset(width, height),
                            isLeft = false,
                            modifier = Modifier.zIndex(4f),
                        )
                    }
                }
                val handlePx = with(LocalDensity.current) { SelectionHandleSize.toPx() }
                val bounds = if (!selection.selectAll && layout != null && handleText.isNotEmpty()) {
                    selectionRect(layout, selection.rangeStart, selection.rangeEnd)
                } else {
                    null
                }
                val centerX: Float
                val bottomY: Float
                when {
                    bounds != null && layout != null -> {
                        centerX = selection.handleOrigin.x + bounds.center.x
                        bottomY = selection.handleOrigin.y + selectionChromeBottom(
                            layout,
                            selection.rangeStart,
                            selection.rangeEnd,
                            handlePx,
                        )
                    }
                    !selection.selectAll && selection.handleSize != IntSize.Zero -> {
                        centerX = selection.handleOrigin.x + selection.handleSize.width / 2f
                        bottomY = selection.handleOrigin.y + selection.handleSize.height + handlePx
                    }
                    else -> {
                        centerX = origin.x + pressLocal.x
                        bottomY = origin.y + pressLocal.y
                    }
                }
                SelectionActionBar(
                    centerXInWindow = centerX,
                    bottomInWindow = bottomY,
                    onCopy = {
                        val copied = if (selection.selectAll) {
                            text
                        } else {
                            val source = selection.handleText
                            val a = minOf(selection.rangeStart, selection.rangeEnd).coerceIn(0, source.length)
                            val b = maxOf(selection.rangeStart, selection.rangeEnd).coerceIn(a, source.length)
                            if (b > a) source.substring(a, b) else ""
                        }
                        if (copied.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(copied))
                            onCopied()
                        }
                    },
                    onSelectAll = { selection.selectEverything() },
                    copyLabel = copyLabel,
                    selectAllLabel = selectAllLabel,
                    copyIcon = UiR.drawable.ic_copy_outline,
                    selectAllIcon = UiR.drawable.ic_select_all_outline_20,
                )
            }
            if (showMenu) {
                val actions = buildList {
                    add(
                        MessageAction(UiR.drawable.ic_copy_outline, copyLabel) {
                            clipboard.setText(AnnotatedString(text))
                            onCopied()
                            showMenu = false
                        },
                    )
                    if (text.isNotBlank()) {
                        add(
                            MessageAction(UiR.drawable.ic_select_outline, selectTextLabel) {
                                showMenu = false
                                val segment = selection.pick(columnOrigin.y + pressLocal.y)
                                if (segment != null) selection.select(segment)
                            },
                        )
                    }
                    if (!streaming) {
                        add(
                            MessageAction(UiR.drawable.ic_regenerate_outline_24, regenerateLabel) {
                                showMenu = false
                                onRegenerate()
                            },
                        )
                        if (text.isNotBlank()) {
                            add(
                                MessageAction(UiR.drawable.ic_play_media_outline_20, readAloudLabel) {
                                    showMenu = false
                                    onToggleTts()
                                },
                            )
                        }
                    }
                    add(
                        MessageAction(UiR.drawable.ic_share_outline_20, shareLabel) {
                            shareText(context, text)
                            showMenu = false
                        },
                    )
                }
                MessageActionPopup(
                    pressInWindow = origin + pressLocal,
                    actions = actions,
                    onDismiss = { showMenu = false },
                )
            }
        }

        when {
            streaming && item.blocks.isEmpty() -> TypingIndicator()
            item.message.status == ReplyStatus.REFUSED -> RefusalCard()
            item.message.status == ReplyStatus.INTERRUPTED -> StatusBanner(
                stringResource(UiR.string.reply_interrupted), retryable = true, onRetry = onRegenerate,
            )
            item.message.status == ReplyStatus.ERROR -> StatusBanner(
                stringResource(UiR.string.generation_failed), retryable = true, onRetry = onRegenerate,
            )
            !streaming && item.message.status == ReplyStatus.STOPPED -> StoppedContinueRow(onContinue)
        }

        if (!streaming) {
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                MessageActionIcon(
                    icon = UiR.drawable.ic_copy_outline,
                    contentDescription = stringResource(UiR.string.copy),
                    tint = iconTint,
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                    },
                )
                MessageActionIcon(
                    icon = if (isSpeaking) UiR.drawable.ic_stop_speech_outline_20 else UiR.drawable.ic_play_media_outline_20,
                    contentDescription = stringResource(if (isSpeaking) UiR.string.stop_speech else UiR.string.read_aloud),
                    tint = if (isSpeaking) DeepSeekColors.BrandBlue else iconTint,
                    onClick = onToggleTts,
                )
                MessageActionIcon(
                    icon = UiR.drawable.ic_share_outline_20,
                    contentDescription = stringResource(UiR.string.share),
                    tint = iconTint,
                    onClick = { shareText(context, text) },
                )
                Spacer(Modifier.weight(1f))
                MessageActionIcon(
                    icon = UiR.drawable.ic_regenerate_outline_24,
                    contentDescription = stringResource(UiR.string.regenerate),
                    tint = iconTint,
                    onClick = onRegenerate,
                )
            }
        }
    }
}

@Composable
private fun MessageActionIcon(
    @DrawableRes icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .requiredSize(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            DsIcon(
                id = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "…",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RefusalCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(UiR.string.content_refused),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun StoppedContinueRow(onContinue: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(
            text = stringResource(UiR.string.stopped_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DsIcon(
            UiR.drawable.ic_chevron_right_outline_20,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.weight(1f))
        if (onContinue != null) {
            Surface(
                onClick = onContinue,
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = stringResource(UiR.string.continue_generate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
fun StatusBanner(message: String, retryable: Boolean, onRetry: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (retryable && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(UiR.string.retry), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(UiR.string.share_chooser)))
    }
}
