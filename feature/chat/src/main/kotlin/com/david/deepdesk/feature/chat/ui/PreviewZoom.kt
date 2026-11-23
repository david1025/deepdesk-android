package com.david.deepdesk.feature.chat.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput

internal data class PreviewZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val isZoomed: Boolean get() = scale > 1f

    fun transform(
        zoom: Float,
        pan: Offset,
        centroid: Offset,
        viewport: Size,
        image: Size,
    ): PreviewZoom {
        val target = (scale * zoom).coerceIn(1f, 5f)
        if (target == 1f) return PreviewZoom()

        val center = Offset(viewport.width / 2, viewport.height / 2)
        val pivot = centroid - center
        val translated = pivot + (offset - pivot) * (target / scale) + pan
        val xLimit = ((image.width * target - viewport.width) / 2).coerceAtLeast(0f)
        val yLimit = ((image.height * target - viewport.height) / 2).coerceAtLeast(0f)
        return PreviewZoom(target, Offset(
            translated.x.coerceIn(-xLimit, xLimit),
            translated.y.coerceIn(-yLimit, yLimit),
        ))
    }
}

@Composable
internal fun Modifier.previewImageGestures(
    active: Boolean,
    imageSize: Size,
    zoom: PreviewZoom,
    onZoom: (PreviewZoom) -> Unit,
    onTouchTransform: (Boolean) -> Unit,
    onTap: () -> Unit,
): Modifier {
    val currentZoom by rememberUpdatedState(zoom)
    val currentImageSize by rememberUpdatedState(imageSize)
    val setZoom by rememberUpdatedState(onZoom)
    val setTransforming by rememberUpdatedState(onTouchTransform)
    val tap by rememberUpdatedState(onTap)
    return pointerInput(active) {
        if (!active) return@pointerInput
        try {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val viewport = Size(size.width.toFloat(), size.height.toFloat())
                var accumulatedPan = Offset.Zero
                var ownsGesture = false
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    val pinch = pressed > 1
                    if (!ownsGesture) {
                        accumulatedPan += pan
                        ownsGesture = pinch || (currentZoom.isZoomed &&
                            accumulatedPan.getDistance() > viewConfiguration.touchSlop)
                        if (ownsGesture) setTransforming(true)
                    }
                    if (ownsGesture && pressed > 0) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (centroid != Offset.Unspecified) {
                            setZoom(currentZoom.transform(event.calculateZoom(), pan, centroid, viewport, currentImageSize))
                        }
                        event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                    }
                } while (pressed > 0)
                if (ownsGesture) setTransforming(false)
            }
        } finally {
            setTransforming(false)
        }
    }.pointerInput(active) {
        if (!active) return@pointerInput
        detectTapGestures(
            onTap = { tap() },
            onDoubleTap = { point ->
                val viewport = Size(size.width.toFloat(), size.height.toFloat())
                setZoom(
                    if (currentZoom.isZoomed) PreviewZoom()
                    else currentZoom.transform(2.5f, Offset.Zero, point, viewport, currentImageSize),
                )
            },
        )
    }
}
