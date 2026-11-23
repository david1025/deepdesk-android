package com.david.deepdesk.feature.attachments

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageRegionTest {
    @Test
    fun cropUsesOutwardPixelBounds() {
        val region = ImageRegion(0.25f, 0.125f, 0.75f, 0.5f)
        assertEquals(ImageBounds(200, 50, 600, 200), region.pixelBounds(800, 400))
        assertEquals(ImageBounds(2, 0, 8, 3), region.pixelBounds(10, 6))
    }

    @Test
    fun cropFollowsSelectedPixelsThroughEachQuarterTurn() {
        val original = ImageRegion(0.25f, 0.125f, 0.75f, 0.5f)
        val expected = original.pixelBounds(800, 400)
        var region = original
        var rotation = ImageRotation.NONE

        repeat(4) {
            rotation = rotation.turnLeft()
            region = region.rotatedLeft()
            assertEquals(expected, ImageCrop(region, rotation).sourceBounds(800, 400))
        }
        assertEquals(ImageRotation.NONE, rotation)
        assertEquals(original, region)
    }

    @Test
    fun selectionAfterRotationMapsToCorrectSourceCorner() {
        val topLeft = ImageRegion(0f, 0f, 0.5f, 0.5f)
        assertEquals(ImageBounds(400, 0, 800, 200), ImageCrop(topLeft, ImageRotation.LEFT).sourceBounds(800, 400))
        assertEquals(ImageBounds(400, 200, 800, 400), ImageCrop(topLeft, ImageRotation.HALF).sourceBounds(800, 400))
        assertEquals(ImageBounds(0, 200, 400, 400), ImageCrop(topLeft, ImageRotation.RIGHT).sourceBounds(800, 400))
    }
}
