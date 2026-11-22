package com.david.deepdesk.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal val LocalSelectionScope = staticCompositionLocalOf { "" }

/** 助手回复选区。正文保持原样渲染，公式不会被换成源码。 */
val LocalRenderedSelection = staticCompositionLocalOf<RenderedSelectionController?> { null }

private val RenderedSelectionColor = Color(0x334C88F7)

data class RenderedSegment(
    val key: String,
    val copyText: String,
    val top: Float,
    val bottom: Float,
    val origin: Offset,
    val size: IntSize,
    val layout: TextLayoutResult?,
)

class RenderedSelectionController {
    private val segments = LinkedHashMap<String, RenderedSegment>()

    var active by mutableStateOf(false)
    var selectAll by mutableStateOf(false)
    var selectedKey by mutableStateOf<String?>(null)
    var rangeStart by mutableIntStateOf(0)
    var rangeEnd by mutableIntStateOf(0)
    var handleLayout by mutableStateOf<TextLayoutResult?>(null)
    var handleOrigin by mutableStateOf(Offset.Zero)
    var handleSize by mutableStateOf(IntSize.Zero)
    var handleText by mutableStateOf("")

    fun consider(segment: RenderedSegment) {
        segments[segment.key] = segment
        if (!active || selectAll || selectedKey != segment.key) return
        if (handleOrigin != segment.origin) handleOrigin = segment.origin
        if (handleSize != segment.size) handleSize = segment.size
        if (segment.layout != null && handleLayout !== segment.layout) handleLayout = segment.layout
        if (handleText != segment.copyText) handleText = segment.copyText
    }

    fun updateLayout(key: String, layout: TextLayoutResult) {
        val prev = segments[key] ?: return
        if (prev.layout === layout) return
        segments[key] = prev.copy(layout = layout)
        if (active && !selectAll && selectedKey == key && handleLayout !== layout) {
            handleLayout = layout
        }
    }

    fun remove(key: String) {
        segments.remove(key)
    }

    fun pick(windowY: Float): RenderedSegment? {
        val values = segments.values.filter { it.copyText.isNotEmpty() }
        if (values.isEmpty()) return null
        val hit = values
            .filter { windowY >= it.top && windowY <= it.bottom }
            .minByOrNull { it.bottom - it.top }
        if (hit != null) return hit
        return values.minByOrNull { abs((it.top + it.bottom) / 2f - windowY) }
    }

    fun select(segment: RenderedSegment) {
        if (segment.copyText.isEmpty()) return
        selectedKey = segment.key
        selectAll = false
        rangeStart = 0
        rangeEnd = segment.copyText.length
        handleLayout = segment.layout
        handleOrigin = segment.origin
        handleSize = segment.size
        handleText = segment.copyText
        active = true
    }

    fun selectEverything() {
        selectAll = true
        selectedKey = null
        handleLayout = null
        active = true
    }

    fun dismiss() {
        active = false
        selectAll = false
        selectedKey = null
        handleLayout = null
    }
}

@Composable
internal fun trackRenderedSegment(
    key: String,
    copyText: String,
    layout: TextLayoutResult?,
): Modifier {
    val controller = LocalRenderedSelection.current ?: return Modifier
    val active = controller.active
    val selectAll = controller.selectAll
    val selected = active && (selectAll || controller.selectedKey == key)
    val start = controller.rangeStart
    val end = controller.rangeEnd
    val layoutState = rememberUpdatedState(layout)
    val textState = rememberUpdatedState(copyText)
    DisposableEffect(key) {
        onDispose { controller.remove(key) }
    }
    SideEffect {
        val current = layoutState.value
        if (current != null) controller.updateLayout(key, current)
    }
    val highlight = if (layout != null && selected && copyText.isNotEmpty()) {
        if (selectAll) 0 to copyText.length else start to end
    } else {
        null
    }
    return Modifier
        .onGloballyPositioned { coords ->
            val origin = coords.positionInWindow()
            controller.consider(
                RenderedSegment(
                    key = key,
                    copyText = textState.value,
                    top = origin.y,
                    bottom = origin.y + coords.size.height,
                    origin = origin,
                    size = coords.size,
                    layout = layoutState.value,
                ),
            )
        }
        .then(
            if (layout != null) {
                Modifier.drawBehind {
                    val range = highlight ?: return@drawBehind
                    val length = copyText.length
                    val from = range.first.coerceIn(0, length)
                    val to = range.second.coerceIn(from, length)
                    if (to <= from) return@drawBehind
                    drawPath(layout.getPathForRange(from, to), RenderedSelectionColor)
                }
            } else if (selected) {
                Modifier.drawWithContent {
                    drawContent()
                    drawRect(RenderedSelectionColor)
                }
            } else {
                Modifier
            },
        )
}
