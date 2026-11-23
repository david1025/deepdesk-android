package com.david.deepdesk.feature.chat.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.compose.AsyncImage
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.feature.attachments.ImageCrop
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 聊天图片预览。缩略图和全屏在同一个 [androidx.compose.animation.SharedTransitionLayout] 里，
 * 用共享元素从原位置展开，返回时收回原处。不另开窗口，避免把聊天页顶上去。
 */
@Stable
class ImagePreviewController {
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set
    val attachment: Attachment? get() = attachments.getOrNull(index)
    val path: String? get() = attachment?.localPath
    var expanded by mutableStateOf(false)
        private set

    fun open(attachment: Attachment) = open(listOf(attachment), attachment)

    fun open(images: List<Attachment>, selected: Attachment) {
        val index = images.indexOfFirst { it.id == selected.id && it.localPath != null }
        if (index == -1) return
        attachments = images
        this.index = index
        expanded = true
    }

    fun select(page: Int) {
        if (page in attachments.indices) index = page
    }

    fun close() {
        expanded = false
    }

    fun clear() {
        attachments = emptyList()
        index = 0
        expanded = false
    }
}

val LocalImagePreview = staticCompositionLocalOf<ImagePreviewController?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

private val ContainerEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
private val ContainerBounds = BoundsTransform { _, _ ->
    tween(durationMillis = 340, easing = ContainerEasing)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPreviewImage(path: String): Modifier {
    val preview = LocalImagePreview.current ?: return this
    val scope = LocalSharedTransitionScope.current ?: return this
    return with(scope) {
        this@sharedPreviewImage.sharedElementWithCallerManagedVisibility(
            sharedContentState = rememberSharedContentState(key = path),
            visible = !(preview.path == path && preview.expanded),
            boundsTransform = ContainerBounds,
            placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
            renderInOverlayDuringTransition = true,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
internal fun AttachmentThumbnail(
    path: String,
    description: String,
    maxEdge: Dp,
    square: Boolean = false,
    onClick: () -> Unit,
) {
    var ratio by remember(path) { mutableStateOf(1f) }
    val fitted = if (square) DpSize(maxEdge, maxEdge) else fitImage(maxEdge, maxEdge, ratio)
    AsyncImage(
        model = File(path),
        contentDescription = description,
        contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
        onSuccess = { if (!square) ratio = it.painter.intrinsicSize.imageRatio() },
        modifier = Modifier
            .size(fitted.width, fitted.height)
            .sharedPreviewImage(path)
            .clip(RoundedCornerShape(10.dp))
            .transparencyBackground(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ImageExpandPreview(
    preview: ImagePreviewController,
    onDownload: (Attachment) -> Unit,
    onUseCrop: suspend (Attachment, ImageCrop) -> Boolean,
) {
    val attachment = preview.attachment ?: return
    val path = attachment.localPath ?: return
    val scope = LocalSharedTransitionScope.current ?: return
    var cropping by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(PreviewZoom()) }
    var transforming by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val images = preview.attachments
    val pager = rememberPagerState(initialPage = preview.index) { images.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect(preview::select)
    }
    fun closePreview() {
        cropping = false
        zoom = PreviewZoom()
        preview.close()
    }
    HidePreviewStatusBar(preview.expanded)
    BackHandler(enabled = preview.expanded && !cropping, onBack = ::closePreview)
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(preview.expanded) {
        scrim.animateTo(
            targetValue = if (preview.expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 340, easing = ContainerEasing),
        )
    }
    with(scope) {
        LaunchedEffect(preview.expanded) {
            if (preview.expanded) return@LaunchedEffect
            // 全屏节点要留到收回动画结束。提前拆掉就没有「回到原处」。
            delay(360)
            if (scope.isTransitionActive) {
                withTimeoutOrNull(800) {
                    snapshotFlow { scope.isTransitionActive }.first { !it }
                }
            }
            if (!preview.expanded) preview.clear()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .background(Color.Black.copy(alpha = scrim.value))
                .pointerInput(preview.expanded, cropping, images.size, zoom.isZoomed, transforming) {
                    if (!preview.expanded || cropping || images.size > 1 || zoom.isZoomed || transforming) return@pointerInput
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onHorizontalDrag = { _, delta -> distance += delta },
                        onDragEnd = { if (abs(distance) >= 56.dp.toPx()) closePreview() },
                    )
                },
        ) {
            if (!cropping) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = preview.expanded && images.size > 1 && !zoom.isZoomed && !transforming,
                    key = { images[it].id },
                ) { page ->
                    val imagePath = images[page].localPath ?: return@HorizontalPager
                    val state = rememberSharedContentState(key = imagePath)
                    var ratio by remember(imagePath) { mutableStateOf(1f) }
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val fitted = fitImage(maxWidth, maxHeight, ratio)
                        val imageSize = with(density) { Size(fitted.width.toPx(), fitted.height.toPx()) }
                        val activePage = page == pager.currentPage
                        Box(
                            Modifier.fillMaxSize().previewImageGestures(
                                active = preview.expanded && activePage,
                                imageSize = imageSize,
                                zoom = zoom,
                                onZoom = { zoom = it },
                                onTouchTransform = { transforming = it },
                                onTap = ::closePreview,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = File(imagePath),
                                contentDescription = images[page].displayName,
                                contentScale = ContentScale.Fit,
                                onSuccess = { ratio = it.painter.intrinsicSize.imageRatio() },
                                modifier = Modifier.size(fitted.width, fitted.height)
                                    .then(
                                        if (page == preview.index) Modifier.sharedElementWithCallerManagedVisibility(
                                            sharedContentState = state,
                                            visible = preview.expanded,
                                            boundsTransform = ContainerBounds,
                                            placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                                            renderInOverlayDuringTransition = false,
                                        ) else Modifier,
                                    )
                                    .graphicsLayer {
                                        if (activePage) {
                                            scaleX = zoom.scale
                                            scaleY = zoom.scale
                                            translationX = zoom.offset.x
                                            translationY = zoom.offset.y
                                        }
                                    }
                                    .transparencyBackground(Color(0xFF303238), Color(0xFF3D4047)),
                            )
                        }
                    }
                }
                if (preview.expanded) {
                    if (images.size > 1) {
                        Text(
                            stringResource(UiR.string.image_preview_position, preview.index + 1, images.size),
                            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp)
                                .background(Color(0xE6262C38), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            color = Color.White,
                        )
                    }
                    Row(
                        Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreviewCropButton(onClick = { cropping = true })
                        PreviewActionButton(onClick = { onDownload(attachment) }) {
                            DsIcon(UiR.drawable.ic_download_outline_20, stringResource(UiR.string.image_download))
                        }
                    }
                }
            }
        }
    }
    if (cropping) {
        ImageRegionEditor(
            file = File(path),
            onDismiss = { cropping = false },
            onBack = ::closePreview,
            onUseCrop = { crop ->
                onUseCrop(attachment, crop).also { applied ->
                    if (applied) {
                        cropping = false
                        preview.clear()
                    }
                }
            },
        )
    }
}

@Composable
private fun PreviewCropButton(onClick: () -> Unit) {
    val label = stringResource(UiR.string.image_crop)
    PreviewActionButton(onClick, Modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.size(20.dp)) {
            val start = Offset(size.width * .23f, size.height * .23f)
            val end = Offset(size.width * .77f, size.height * .77f)
            val stroke = 1.5.dp.toPx()
            drawRect(Color.White, start, Size(end.x - start.x, end.y - start.y), style = Stroke(stroke))
            drawLine(Color.White, Offset(start.x, 0f), start, stroke)
            drawLine(Color.White, end, Offset(end.x, size.height), stroke)
        }
    }
}

@Composable
private fun PreviewActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color(0xE6262C38), contentColor = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun HidePreviewStatusBar(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = (view.context as? Activity)?.window
        if (!active || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val statusBar = WindowInsetsCompat.Type.statusBars()
        val statusBarWasVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(statusBar) != false
        // Keep Android's navigation gestures active in the edge-to-edge viewer.
        controller.hide(statusBar)
        onDispose {
            if (statusBarWasVisible) controller.show(statusBar)
        }
    }
}

private fun Size.imageRatio(): Float {
    if (this == Size.Unspecified) return 1f
    return (width / height).takeIf { it.isFinite() && it > 0f } ?: 1f
}

private fun fitImage(maxWidth: Dp, maxHeight: Dp, ratio: Float): DpSize {
    val width = minOf(maxWidth, maxHeight * ratio)
    return DpSize(width, width / ratio)
}

private fun Modifier.transparencyBackground(base: Color, alternate: Color) = drawWithCache {
    val side = 10.dp.toPx()
    val checks = Path()
    for (row in 0 until ceil(size.height / side).toInt()) {
        for (column in (row % 2) until ceil(size.width / side).toInt() step 2) {
            val left = column * side
            val top = row * side
            checks.addRect(Rect(left, top, minOf(left + side, size.width), minOf(top + side, size.height)))
        }
    }
    onDrawBehind {
        drawRect(base)
        drawPath(checks, alternate)
    }
}
