package com.david.deepdesk.feature.chat.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewZoomTest {
    private val viewport = Size(400f, 600f)
    private val image = Size(400f, 200f)

    @Test
    fun zoomKeepsTheTouchedPointInPlaceWhereTheImageCanPan() {
        val zoom = PreviewZoom().transform(2f, Offset.Zero, Offset(300f, 300f), viewport, image)
        assertEquals(PreviewZoom(2f, Offset(-100f, 0f)), zoom)
    }

    @Test
    fun panningStopsAtTheImageEdgesAndFitClearsTranslation() {
        val zoom = PreviewZoom(4f).transform(1f, Offset(800f, -800f), Offset.Zero, viewport, image)
        assertEquals(PreviewZoom(4f, Offset(600f, -100f)), zoom)
        assertEquals(PreviewZoom(), zoom.transform(0.25f, Offset.Zero, Offset.Zero, viewport, image))
    }
}
