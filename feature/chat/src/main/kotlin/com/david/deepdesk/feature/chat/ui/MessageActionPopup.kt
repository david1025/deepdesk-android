package com.david.deepdesk.feature.chat.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.LocalDeepSeekDark
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class MessageAction(
    @DrawableRes val icon: Int,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 长按浮层。以按下点为中心，贴在窗口里，不挂在气泡角上。 */
@Composable
internal fun MessageActionPopup(
    pressInWindow: Offset,
    actions: List<MessageAction>,
    onDismiss: () -> Unit,
) {
    if (actions.isEmpty()) return
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val provider = remember(pressInWindow, margin) {
        AnchoredPopupPositionProvider(
            centerX = pressInWindow.x.roundToInt(),
            primaryY = pressInWindow.y.roundToInt(),
            centerVertically = true,
            flipAboveY = null,
            marginPx = margin,
        )
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        MessageActionCard(actions)
    }
}

/** 选中文字后的操作条：图标在上，贴在选区下沿，不盖住上方正文。 */
@Composable
internal fun SelectionActionBar(
    centerXInWindow: Float,
    bottomInWindow: Float,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    copyLabel: String,
    selectAllLabel: String,
    @DrawableRes copyIcon: Int,
    @DrawableRes selectAllIcon: Int,
) {
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val provider = remember(centerXInWindow, bottomInWindow, margin, gap) {
        SelectionBarPositionProvider(
            centerX = centerXInWindow.roundToInt(),
            selectionBottom = bottomInWindow.roundToInt(),
            gapPx = gap,
            marginPx = margin,
        )
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {},
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        val dark = LocalDeepSeekDark.current
        val card = if (dark) Color(0xFF2C2C2E) else Color.White
        val ink = if (dark) Color.White else Color(0xFF1C1C1E)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = card,
            shadowElevation = 16.dp,
            tonalElevation = 0.dp,
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                SelectionBarItem(copyIcon, copyLabel, ink, onCopy)
                SelectionBarItem(selectAllIcon, selectAllLabel, ink, onSelectAll)
            }
        }
    }
}

@Composable
private fun MessageActionCard(actions: List<MessageAction>) {
    val dark = LocalDeepSeekDark.current
    val card = if (dark) Color(0xFF2C2C2E) else Color.White
    val ink = if (dark) Color.White else Color(0xFF1C1C1E)
    val divider = if (dark) Color(0xFF3A3A3C) else Color(0xFFE6E6E6)
    val disabled = ink.copy(alpha = 0.35f)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = card,
        shadowElevation = 18.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.width(208.dp)) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(divider),
                    )
                }
                val color = if (action.enabled) ink else disabled
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = action.enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = action.onClick,
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    DsIcon(
                        id = action.icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = action.label,
                        color = color,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBarItem(
    @DrawableRes icon: Int,
    label: String,
    ink: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
    ) {
        DsIcon(
            id = icon,
            contentDescription = label,
            tint = ink,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = ink, fontSize = 13.sp)
    }
}

/** 官方 AppCompat 选区手柄着色：浅色 primary，深色 primary。 */
internal val SelectionHandleColor = Color(0xFF426EFE)
internal val SelectionHandleColorDark = Color(0xFF507BF2)
internal val SelectionHighlightColor = Color(0x334C88F7)
/** 圆头直径，对应 abc_text_select_handle_* 的 22dp 高。 */
internal val SelectionHandleSize = 22.dp
internal val SelectionHandleTouch = 36.dp

/** 尖角贴在选区端点的行底。左侧圆头在端点左边，右侧在右边。 */
internal class SelectionHandleAnchor(
    val hotspot: Offset,
    val isLeft: Boolean,
)

internal fun selectionRect(layout: TextLayoutResult, start: Int, end: Int): Rect {
    val length = layout.layoutInput.text.length
    val a = min(start, end).coerceIn(0, length)
    val b = max(start, end).coerceIn(0, length)
    if (b <= a) return Rect.Zero
    return layout.getPathForRange(a, b).getBounds()
}

/**
 * 官方手柄尖角的位置。右端正好落在下一行行首时，留在上一行行尾。
 * [offset] 可以等于文本长度。
 */
internal fun selectionHandleAnchor(
    layout: TextLayoutResult,
    offset: Int,
    isStart: Boolean,
): SelectionHandleAnchor {
    val length = layout.layoutInput.text.length
    if (length == 0) return SelectionHandleAnchor(Offset.Zero, isLeft = isStart)
    val cursor = offset.coerceIn(0, length)
    val rawLine = layout.getLineForOffset(cursor)
    val atNextLineStart = !isStart && cursor > 0 && rawLine > 0 && cursor == layout.getLineStart(rawLine)
    val line = if (atNextLineStart) rawLine - 1 else rawLine
    val xOffset = if (atNextLineStart) cursor - 1 else cursor
    val x = layout.getHorizontalPosition(xOffset, usePrimaryDirection = true)
        .coerceIn(layout.getLineLeft(line), layout.getLineRight(line))
    val y = layout.getLineBottom(line)
    val probe = if (isStart) cursor.coerceAtMost(length - 1) else (cursor - 1).coerceAtLeast(0)
    val ltr = layout.getBidiRunDirection(probe) == ResolvedTextDirection.Ltr
    return SelectionHandleAnchor(Offset(x, y), isLeft = if (isStart) ltr else !ltr)
}

/** 圆头中心。拖动命中用这个点，[radiusPx] 是圆头半径。 */
internal fun selectionHandleGrab(anchor: SelectionHandleAnchor, radiusPx: Float): Offset {
    val dx = if (anchor.isLeft) -radiusPx else radiusPx
    return anchor.hotspot + Offset(dx, radiusPx)
}

/** 操作条要让开圆头，所以下沿取选区底和手柄底里更低的那个。 */
internal fun selectionChromeBottom(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    handlePx: Float,
): Float {
    val bounds = selectionRect(layout, start, end)
    val startBottom = selectionHandleAnchor(layout, start, isStart = true).hotspot.y + handlePx
    val endBottom = selectionHandleAnchor(layout, end, isStart = false).hotspot.y + handlePx
    return max(bounds.bottom, max(startBottom, endBottom))
}

/** 贴在选区下沿。放不下时停在屏幕底边，不翻到选区上方。 */
private class SelectionBarPositionProvider(
    private val centerX: Int,
    private val selectionBottom: Int,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val x = (centerX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val y = (selectionBottom + gapPx).coerceIn(marginPx, maxY)
        return IntOffset(x, y)
    }
}

private class AnchoredPopupPositionProvider(
    private val centerX: Int,
    private val primaryY: Int,
    private val centerVertically: Boolean,
    private val flipAboveY: Int?,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val x = (centerX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val y = if (centerVertically) {
            (primaryY - popupContentSize.height / 2).coerceIn(marginPx, maxY)
        } else {
            val below = primaryY
            if (below <= maxY) below.coerceAtLeast(marginPx)
            else flipAboveY?.let { (it - popupContentSize.height).coerceIn(marginPx, maxY) } ?: maxY
        }
        return IntOffset(x, y)
    }
}
