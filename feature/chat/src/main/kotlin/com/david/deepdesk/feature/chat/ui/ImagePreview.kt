package com.david.deepdesk.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.david.deepdesk.core.ui.R as UiR
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聊天图片预览。缩略图和全屏在同一个 [androidx.compose.animation.SharedTransitionLayout] 里，
 * 用共享元素从原位置展开，返回时收回原处。不另开窗口，避免把聊天页顶上去。
 */
@Stable
class ImagePreviewController {
    var path by mutableStateOf<String?>(null)
        private set
    var expanded by mutableStateOf(false)
        private set

    fun open(path: String) {
        this.path = path
        expanded = true
    }

    fun close() {
        expanded = false
    }

    fun clear() {
        path = null
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
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ImageExpandPreview(preview: ImagePreviewController) {
    val path = preview.path ?: return
    val scope = LocalSharedTransitionScope.current ?: return
    BackHandler(onBack = preview::close)
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(preview.expanded) {
        scrim.animateTo(
            targetValue = if (preview.expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 340, easing = ContainerEasing),
        )
    }
    with(scope) {
        val state = rememberSharedContentState(key = path)
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = preview::close,
                ),
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = stringResource(UiR.string.preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .sharedElementWithCallerManagedVisibility(
                        sharedContentState = state,
                        visible = preview.expanded,
                        boundsTransform = ContainerBounds,
                        placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                        renderInOverlayDuringTransition = true,
                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
                    ),
            )
        }
    }
}
