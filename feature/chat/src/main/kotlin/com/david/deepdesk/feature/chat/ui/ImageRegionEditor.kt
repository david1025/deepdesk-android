package com.david.deepdesk.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.feature.attachments.ImageCrop
import com.david.deepdesk.feature.attachments.ImageRegion
import com.david.deepdesk.feature.attachments.ImageRegionRenderer
import com.david.deepdesk.feature.attachments.ImageRotation
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CropHitHorizontal = 10.dp
private val CropHitVertical = 18.dp

@Composable
internal fun ImageRegionEditor(
    file: File,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onUseCrop: suspend (ImageCrop) -> Boolean,
) {
    var loadFailed by remember(file) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageRegionRenderer.preview(file).asImageBitmap() }.getOrNull()
        }
        loadFailed = value == null
    }
    var region by remember(file) { mutableStateOf(ImageRegion.FULL) }
    var rotation by remember(file) { mutableStateOf(ImageRotation.NONE) }
    var busy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val whiteButton = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.Gray)

    BackHandler { if (!busy) onBack() }
    Surface(Modifier.fillMaxSize().zIndex(2f), color = Color(0xFF0C1017), contentColor = Color.White) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        rotation = rotation.turnLeft()
                        region = region.rotatedLeft()
                        saveFailed = false
                    },
                    enabled = bitmap != null && !busy,
                    colors = whiteButton,
                ) {
                    DsIcon(UiR.drawable.ic_phone_rotate_vertical_outline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(UiR.string.image_crop_rotate))
                }
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image != null) {
                    val imageWidth = if (rotation.swapsAxes) image.height else image.width
                    val imageHeight = if (rotation.swapsAxes) image.width else image.height
                    val aspect = imageWidth.toFloat() / imageHeight
                    val availableWidth = (maxWidth - CropHitHorizontal * 2).coerceAtLeast(0.dp)
                    val availableHeight = (maxHeight - CropHitVertical * 2).coerceAtLeast(0.dp)
                    val fittedWidth = minOf(availableWidth, availableHeight * aspect)
                    CropCanvas(
                        image, rotation, region, busy,
                        onRegionChanged = {
                            region = it
                            saveFailed = false
                        },
                        modifier = Modifier.size(
                            fittedWidth + CropHitHorizontal * 2,
                            fittedWidth / aspect + CropHitVertical * 2,
                        ),
                    )
                } else if (!loadFailed) {
                    CircularProgressIndicator()
                }
            }

            if (loadFailed || saveFailed) {
                Text(
                    stringResource(if (loadFailed) UiR.string.image_region_load_failed else UiR.string.image_region_save_failed),
                    color = Color(0xFFFFB4AB),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF242D3C), contentColor = Color.White,
                    ),
                ) {
                    DsIcon(UiR.drawable.ic_close_outline_20, stringResource(UiR.string.cancel), Modifier.size(22.dp))
                }
                Button(
                    onClick = {
                        busy = true
                        saveFailed = false
                        val crop = ImageCrop(region, rotation)
                        scope.launch {
                            try {
                                if (!onUseCrop(crop)) saveFailed = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = bitmap != null && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(if (busy) UiR.string.image_region_saving else UiR.string.image_crop_ask))
                }
            }
        }
    }
}

@Composable
private fun CropCanvas(
    image: ImageBitmap,
    rotation: ImageRotation,
    region: ImageRegion,
    busy: Boolean,
    onRegionChanged: (ImageRegion) -> Unit,
    modifier: Modifier,
) {
    val currentRegion = rememberUpdatedState(region)
    val changeRegion = rememberUpdatedState(onRegionChanged)
    val description = stringResource(UiR.string.image_region_canvas)
    Box(
        modifier.pointerInput(rotation, busy) {
            if (busy) return@pointerInput
            val touchRadius = 26.dp.toPx()
            val padding = IntOffset(CropHitHorizontal.roundToPx(), CropHitVertical.roundToPx())
            awaitEachGesture {
                val down = awaitFirstDown()
                val picture = IntSize(size.width - padding.x * 2, size.height - padding.y * 2)
                if (picture.width <= 0 || picture.height <= 0) return@awaitEachGesture
                var dragRegion = currentRegion.value
                val start = down.position - Offset(padding.x.toFloat(), padding.y.toFloat())
                val handle = dragRegion.handleAt(start, picture, touchRadius) ?: return@awaitEachGesture
                down.consume()
                drag(down.id) { change ->
                    val delta = change.positionChange()
                    change.consume()
                    dragRegion = handle.drag(dragRegion, delta.x / picture.width, delta.y / picture.height)
                    changeRegion.value(dragRegion)
                }
            }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize().padding(horizontal = CropHitHorizontal, vertical = CropHitVertical)
                .semantics { contentDescription = description },
        ) {
            val width = (if (rotation.swapsAxes) size.height else size.width).roundToInt()
            val height = (if (rotation.swapsAxes) size.width else size.height).roundToInt()
            if (width <= 0 || height <= 0) return@Canvas
            rotate(rotation.degrees) {
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                    dstSize = IntSize(width, height),
                )
            }
            val left = region.left * size.width
            val top = region.top * size.height
            val right = region.right * size.width
            val bottom = region.bottom * size.height
            val shade = Color.Black.copy(alpha = 0.60f)
            drawRect(shade, size = Size(size.width, top))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
            drawRect(
                Color.White, Offset(left, top), Size(right - left, bottom - top),
                style = Stroke(1.dp.toPx()),
            )
            val handle = 4.dp.toPx()
            val handleX = minOf(20.dp.toPx(), (right - left) / 4)
            val handleY = minOf(20.dp.toPx(), (bottom - top) / 4)
            for ((x, dx) in listOf(left to handleX, right to -handleX)) {
                for ((y, dy) in listOf(top to handleY, bottom to -handleY)) {
                    val corner = Offset(x, y)
                    drawLine(Color.White, corner, Offset(x + dx, y), handle, cap = StrokeCap.Square)
                    drawLine(Color.White, corner, Offset(x, y + dy), handle, cap = StrokeCap.Square)
                }
            }
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            for (y in listOf(top, bottom)) {
                drawLine(Color.White, Offset(centerX - handleX / 2, y), Offset(centerX + handleX / 2, y), handle, StrokeCap.Square)
            }
            for (x in listOf(left, right)) {
                drawLine(Color.White, Offset(x, centerY - handleY / 2), Offset(x, centerY + handleY / 2), handle, StrokeCap.Square)
            }
        }
    }
}

private data class CropHandle(val horizontal: Int, val vertical: Int) {
    fun drag(region: ImageRegion, dx: Float, dy: Float): ImageRegion {
        if (horizontal == 0 && vertical == 0) {
            val x = dx.coerceIn(-region.left, 1f - region.right)
            val y = dy.coerceIn(-region.top, 1f - region.bottom)
            return ImageRegion(region.left + x, region.top + y, region.right + x, region.bottom + y)
        }
        val minimum = 0.04f
        return ImageRegion(
            if (horizontal == -1) (region.left + dx).coerceIn(0f, (region.right - minimum).coerceAtLeast(0f)) else region.left,
            if (vertical == -1) (region.top + dy).coerceIn(0f, (region.bottom - minimum).coerceAtLeast(0f)) else region.top,
            if (horizontal == 1) (region.right + dx).coerceIn((region.left + minimum).coerceAtMost(1f), 1f) else region.right,
            if (vertical == 1) (region.bottom + dy).coerceIn((region.top + minimum).coerceAtMost(1f), 1f) else region.bottom,
        )
    }
}

private fun ImageRegion.handleAt(point: Offset, size: IntSize, radius: Float): CropHandle? {
    val x = point.x
    val y = point.y
    val left = left * size.width
    val right = right * size.width
    val top = top * size.height
    val bottom = bottom * size.height
    if (x !in (left - radius)..(right + radius) || y !in (top - radius)..(bottom + radius)) return null
    return CropHandle(edge(x, left, right, radius), edge(y, top, bottom, radius))
}

private fun edge(coordinate: Float, start: Float, end: Float, radius: Float): Int {
    val fromStart = abs(coordinate - start)
    val fromEnd = abs(coordinate - end)
    return when {
        minOf(fromStart, fromEnd) > radius -> 0
        fromStart <= fromEnd -> -1
        else -> 1
    }
}
