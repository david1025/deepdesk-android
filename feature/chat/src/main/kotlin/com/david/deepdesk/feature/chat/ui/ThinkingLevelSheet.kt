package com.david.deepdesk.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.david.deepdesk.core.model.ChatModels
import com.david.deepdesk.core.model.ListedModel
import com.david.deepdesk.core.ui.DeepSeekColors
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.R as UiR
import kotlin.math.roundToInt

private val ThumbColor = Color.White
private val TrackGreen = Color(0xFF34D27A)
private val TrackCyan = Color(0xFF3EC6E6)
private val TrackBlue = Color(0xFF4D81EF)
private val TrackIdle = Color(0xFFE7E7E7)
private val HintColor = Color(0xFF606068)
private val LabelIdle = Color(0xFF8E8E93)
private val DotIdle = Color(0xFFBDBDBD)
private val CardShape = RoundedCornerShape(22.dp)
private val TrackHeight = 22.dp
private val ThumbSize = 28.dp
private val CardShadow = Color.Black.copy(alpha = 0.18f)
private val CardSpot = Color.Black.copy(alpha = 0.28f)

/** 盖住输入区，底边贴着档位芯片。点外面或返回关闭。 */
@Composable
internal fun ThinkingLevelAnchor(
    open: Boolean,
    level: Int,
    modelId: String,
    catalogModels: List<ListedModel>,
    catalogLoading: Boolean,
    catalogError: String?,
    onLevel: (Int) -> Unit,
    onModel: (String) -> Unit,
    onLoadModels: () -> Unit,
    onDismiss: () -> Unit,
    widthOutset: Dp = 0.dp,
) {
    val density = LocalDensity.current
    var anchorLeft by remember { mutableIntStateOf(0) }
    var anchorTop by remember { mutableIntStateOf(0) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) {
        if (open) onLoadModels()
    }
    if (open) {
        BackHandler(onBack = onDismiss)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                anchorLeft = pos.x.roundToInt()
                anchorTop = pos.y.roundToInt()
                anchorWidth = coords.size.width
            },
    ) {
        if (open && anchorWidth > 0) {
            val cardWidth = with(density) { anchorWidth.toDp() } + widthOutset
            val sidePad = with(density) { 16.dp.roundToPx() }
            // Not focusable: a focusable popup takes the text field's window focus and the IME hides.
            Popup(
                popupPositionProvider = WindowOriginPositionProvider,
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            ),
                    )
                    Layout(
                        content = {
                            ThinkingLevelCard(
                                level = level,
                                modelId = modelId,
                                models = catalogModels,
                                loading = catalogLoading,
                                error = catalogError,
                                onLevel = onLevel,
                                onModel = onModel,
                                onRetryModels = onLoadModels,
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 14.dp)
                                    .width(cardWidth)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {},
                                    ),
                            )
                        },
                    ) { measurables, constraints ->
                        val placeable = measurables.first().measure(
                            constraints.copy(minWidth = 0, minHeight = 0),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(
                                anchorLeft - sidePad,
                                (anchorTop - placeable.height).coerceAtLeast(0),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingLevelCard(
    level: Int,
    modelId: String,
    models: List<ListedModel>,
    loading: Boolean,
    error: String?,
    onLevel: (Int) -> Unit,
    onModel: (String) -> Unit,
    onRetryModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = listOf(
        stringResource(UiR.string.thinking_stop_off),
        stringResource(UiR.string.effort_low),
        stringResource(UiR.string.effort_high),
        stringResource(UiR.string.thinking_stop_max),
    )
    val captions = listOf(
        UiR.string.thinking_caption_off,
        UiR.string.thinking_caption_low,
        UiR.string.thinking_caption_high,
        UiR.string.thinking_caption_max,
    )
    val safe = level.coerceIn(0, names.lastIndex)
    val rows = remember(models, modelId) {
        val current = ListedModel(modelId, modelId, ChatModels.supportsVision(modelId))
        val base = models.ifEmpty { listOf(current) }
        if (base.any { ChatModels.canonicalId(it.id) == ChatModels.canonicalId(modelId) }) base
        else listOf(current) + base
    }
    val levelColor = when (safe) {
        0 -> LabelIdle
        3 -> TrackBlue
        else -> TrackGreen
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        modifier = modifier.shadow(
            elevation = 20.dp,
            shape = CardShape,
            clip = false,
            ambientColor = CardShadow,
            spotColor = CardSpot,
        ),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp)) {
            Text(
                stringResource(UiR.string.model_label),
                style = TextStyle(
                    color = HintColor,
                    fontSize = 13.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
            if (loading && rows.isEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = DeepSeekColors.BrandBlue,
                    )
                    Text(
                        stringResource(UiR.string.model_list_loading),
                        style = TextStyle(fontSize = 13.sp, color = HintColor),
                    )
                }
            }
            rows.forEach { model ->
                val selected = ChatModels.canonicalId(model.id) == ChatModels.canonicalId(modelId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onModel(model.id) }
                        .padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            model.name,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (model.vision) {
                            Text(
                                stringResource(UiR.string.model_vision_badge),
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    color = HintColor,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                ),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            DsIcon(
                                UiR.drawable.ic_check_outline_20,
                                contentDescription = null,
                                tint = DeepSeekColors.BrandBlue,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (error != null) {
                Text(
                    stringResource(UiR.string.model_list_retry),
                    style = TextStyle(fontSize = 12.sp, color = DeepSeekColors.BrandBlue),
                    modifier = Modifier
                        .clickable(onClick = onRetryModels)
                        .padding(top = 6.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiR.string.thinking_strength),
                    style = TextStyle(
                        color = HintColor,
                        fontSize = 14.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                )
                Text(
                    names[safe],
                    style = TextStyle(
                        color = levelColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            ThinkingSlider(level = safe, labels = names, onLevel = onLevel)
            Text(
                stringResource(captions[safe]),
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun ThinkingSlider(
    level: Int,
    labels: List<String>,
    onLevel: (Int) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val inactive = if (dark) Color(0xFF3A3D46) else TrackIdle
    val dotIdle = if (dark) Color(0xFF9AA0AB) else DotIdle
    val selectedLabel = MaterialTheme.colorScheme.onSurface
    var widthPx by remember { mutableIntStateOf(0) }
    val onLevelState = rememberUpdatedState(onLevel)
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ThumbSize)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) {
                    val trackH = TrackHeight.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun pick(x: Float) {
                            onLevelState.value(pickStop(x, size.width.toFloat(), trackH))
                        }
                        pick(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pick(change.position.x)
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackH = TrackHeight.toPx()
                val cy = size.height / 2f
                val left = 0f
                val right = size.width
                val thumbX = stopCenterX(level, size.width, trackH)
                val radius = CornerRadius(trackH / 2f, trackH / 2f)
                val top = cy - trackH / 2f
                drawRoundRect(
                    color = inactive,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(trackH), trackH),
                    cornerRadius = radius,
                )
                if (level > 0) {
                    val fill = (thumbX - left).coerceAtLeast(trackH)
                    val colors = if (level >= 3) {
                        listOf(TrackGreen, TrackCyan, TrackBlue)
                    } else {
                        listOf(TrackGreen, TrackGreen)
                    }
                    drawRoundRect(
                        brush = Brush.horizontalGradient(colors, startX = left, endX = left + fill),
                        topLeft = Offset(left, top),
                        size = Size(fill, trackH),
                        cornerRadius = radius,
                    )
                }
                val dotR = 1.6.dp.toPx()
                for (index in 0..3) {
                    if (index == level) continue
                    val onFill = index < level
                    drawCircle(
                        color = if (onFill) Color.White.copy(alpha = 0.85f) else dotIdle,
                        radius = if (onFill && level >= 3) dotR * 0.8f else dotR,
                        center = Offset(stopCenterX(index, size.width, trackH), cy),
                    )
                }
                if (level >= 3) {
                    val specks = listOf(0.22f, 0.38f, 0.55f, 0.72f)
                    specks.forEachIndexed { i, t ->
                        drawCircle(
                            color = Color.White.copy(alpha = if (i % 2 == 0) 0.7f else 0.4f),
                            radius = if (i % 2 == 0) 1.4.dp.toPx() else 1.dp.toPx(),
                            center = Offset(left + (thumbX - left) * t, cy + if (i % 2 == 0) -2.dp.toPx() else 2.5.dp.toPx()),
                        )
                    }
                }
            }
            if (widthPx > 0) {
                val thumbPx = with(density) { ThumbSize.toPx() }
                val trackH = with(density) { TrackHeight.toPx() }
                val center = stopCenterX(level, widthPx.toFloat(), trackH)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((center - thumbPx / 2f).roundToInt(), 0) }
                        .size(ThumbSize)
                        .shadow(
                            3.dp,
                            CircleShape,
                            clip = false,
                            ambientColor = Color.Black.copy(0.10f),
                            spotColor = Color.Black.copy(0.18f),
                        )
                        .background(ThumbColor, CircleShape)
                        .border(0.5.dp, Color(0x1A000000), CircleShape),
                )
            }
        }
        if (widthPx > 0) {
            val trackH = with(density) { TrackHeight.toPx() }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(18.dp),
            ) {
                labels.forEachIndexed { index, name ->
                    key(index) {
                    var labelW by remember { mutableIntStateOf(0) }
                    val stop = stopCenterX(index, widthPx.toFloat(), trackH)
                    Text(
                        name,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (index == level) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == level) selectedLabel else LabelIdle,
                            textAlign = TextAlign.Center,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        modifier = Modifier
                            .onSizeChanged { labelW = it.width }
                            .offset {
                                val x = if (labelW == 0) {
                                    stop.roundToInt()
                                } else {
                                    (stop - labelW / 2f).roundToInt().coerceIn(0, (widthPx - labelW).coerceAtLeast(0))
                                }
                                IntOffset(x, 0)
                            }
                            .clickable { onLevel(index) },
                    )
                    }
                }
            }
            }
        }
    }
}

private fun stopCenterX(index: Int, width: Float, trackH: Float): Float {
    val inset = trackH / 2f
    val span = (width - inset * 2f).coerceAtLeast(0f)
    return inset + span * (index / 3f)
}

private fun pickStop(x: Float, width: Float, trackH: Float): Int {
    val inset = trackH / 2f
    val span = (width - inset * 2f).coerceAtLeast(1f)
    return ((x - inset) / span * 3f).roundToInt().coerceIn(0, 3)
}

private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}


