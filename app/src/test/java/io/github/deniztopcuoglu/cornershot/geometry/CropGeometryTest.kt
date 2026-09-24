package io.github.deniztopcuoglu.cornershot.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    @Test fun topLeftToBottomRightDrag() {
        assertEquals(IntRect(10, 20, 90, 110), CropGeometry.toSourcePixels(FloatRect(10f, 20f, 90f, 110f), 200, 200))
    }

    @Test fun bottomRightToTopLeftDrag() {
        assertEquals(IntRect(10, 20, 90, 110), CropGeometry.toSourcePixels(FloatRect(90f, 110f, 10f, 20f), 200, 200))
    }

    @Test fun horizontalReverseDrag() {
        assertEquals(IntRect(15, 30, 175, 45), CropGeometry.toSourcePixels(FloatRect(175f, 45f, 15f, 30f), 200, 100))
    }

    @Test fun verticalReverseDrag() {
        assertEquals(IntRect(25, 5, 40, 95), CropGeometry.toSourcePixels(FloatRect(40f, 95f, 25f, 5f), 100, 100))
    }

    @Test fun clampsOutsideBitmapBounds() {
        assertEquals(IntRect(0, 5, 100, 80), CropGeometry.toSourcePixels(FloatRect(-4f, 5f, 130f, 80f), 100, 100))
    }

    @Test fun bitmapAndViewScaleConversionRoundTrips() {
        val fit = requireNotNull(CropGeometry.fitCenter(1600, 900, 800, 600))
        val viewRect = fit.sourceToView(FloatRect(80f, 90f, 700f, 500f))
        val sourceRect = fit.viewToSource(viewRect)
        assertEquals(80f, sourceRect.left, 0.001f)
        assertEquals(90f, sourceRect.top, 0.001f)
        assertEquals(700f, sourceRect.right, 0.001f)
        assertEquals(500f, sourceRect.bottom, 0.001f)
    }

    @Test fun aspectRatioIsFitCenterWithLetterboxing() {
        val fit = requireNotNull(CropGeometry.fitCenter(200, 100, 100, 100))
        assertEquals(0.5f, fit.scale, 0.001f)
        assertEquals(0f, fit.offsetX, 0.001f)
        assertEquals(25f, fit.offsetY, 0.001f)
    }

    @Test fun tinySelectionIsRejected() {
        assertTrue(CropGeometry.isTooSmall(FloatRect(30f, 40f, 45f, 80f)))
        assertFalse(CropGeometry.isTooSmall(FloatRect(30f, 40f, 60f, 70f)))
    }
}
