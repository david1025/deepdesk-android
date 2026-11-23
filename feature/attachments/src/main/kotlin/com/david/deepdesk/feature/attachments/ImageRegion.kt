package com.david.deepdesk.feature.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** Crop coordinates in the displayed, EXIF-corrected image. */
data class ImageRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && left < right && top < bottom && right <= 1f && bottom <= 1f)
    }

    fun rotatedLeft() = ImageRegion(top, 1f - right, bottom, 1f - left)

    fun pixelBounds(width: Int, height: Int): ImageBounds {
        require(width > 0 && height > 0)
        val x = floor(left * width).toInt().coerceIn(0, width - 1)
        val y = floor(top * height).toInt().coerceIn(0, height - 1)
        return ImageBounds(
            x, y,
            ceil(right * width).toInt().coerceIn(x + 1, width),
            ceil(bottom * height).toInt().coerceIn(y + 1, height),
        )
    }

    companion object {
        val FULL = ImageRegion(0f, 0f, 1f, 1f)
    }
}

data class ImageBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class ImageRotation(val degrees: Float) {
    NONE(0f), LEFT(-90f), HALF(-180f), RIGHT(-270f);

    val swapsAxes: Boolean get() = this == LEFT || this == RIGHT
    fun turnLeft(): ImageRotation = entries[(ordinal + 1) % entries.size]
}

data class ImageCrop(val region: ImageRegion, val rotation: ImageRotation) {
    // Rotate the selected rectangle back into source coordinates so only the cropped pixels need rotation.
    fun sourceBounds(width: Int, height: Int): ImageBounds {
        var source = region
        repeat((ImageRotation.entries.size - rotation.ordinal) % ImageRotation.entries.size) {
            source = source.rotatedLeft()
        }
        return source.pixelBounds(width, height)
    }
}

/** Applies EXIF orientation before cropping and writes a fresh PNG with no source metadata. */
object ImageRegionRenderer {
    fun preview(file: File): Bitmap = decodeUpright(file, maxEdge = 1600)

    internal fun writePng(file: File, crop: ImageCrop, destination: File) {
        val source = decodeUpright(file, maxEdge = 3072)
        try {
            val bounds = crop.sourceBounds(source.width, source.height)
            val rotation = Matrix().apply { setRotate(crop.rotation.degrees) }
            val result = Bitmap.createBitmap(
                source, bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top,
                rotation, false,
            )
            try {
                destination.outputStream().use {
                    if (!result.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("Unable to encode cropped image")
                    }
                }
            } finally {
                if (result !== source) result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun decodeUpright(file: File, maxEdge: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val longestEdge = maxOf(options.outWidth, options.outHeight)
        if (longestEdge <= 0) throw IOException("Unable to decode image")
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (longestEdge.toLong() > maxEdge.toLong() * options.inSampleSize) {
            options.inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Unable to decode image")
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
