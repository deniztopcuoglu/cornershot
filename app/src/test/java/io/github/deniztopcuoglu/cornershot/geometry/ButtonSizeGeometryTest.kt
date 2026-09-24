package io.github.deniztopcuoglu.cornershot.geometry

import io.github.deniztopcuoglu.cornershot.ButtonSizeGeometry
import io.github.deniztopcuoglu.cornershot.NormalizedOverlayPosition
import io.github.deniztopcuoglu.cornershot.OverlayPosition
import io.github.deniztopcuoglu.cornershot.OverlayPositionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSizeGeometryTest {
    @Test
    fun visibleDiameterUsesAtLeastFortyEightDpTouchTarget() {
        assertEquals(48, ButtonSizeGeometry.touchTargetSizeDp(24))
        assertEquals(48, ButtonSizeGeometry.touchTargetSizeDp(36))
        assertEquals(48, ButtonSizeGeometry.touchTargetSizeDp(48))
        assertEquals(64, ButtonSizeGeometry.touchTargetSizeDp(64))
    }

    @Test
    fun sanitizesSizeIntoRangeAndTwoDpSteps() {
        assertEquals(24, ButtonSizeGeometry.sanitizeVisibleSizeDp(10))
        assertEquals(26, ButtonSizeGeometry.sanitizeVisibleSizeDp(25))
        assertEquals(64, ButtonSizeGeometry.sanitizeVisibleSizeDp(99))
    }

    @Test
    fun boundsAndClampingUseCurrentTargetDimensions() {
        val bounds48 = OverlayPositionGeometry.usableBounds(
            screenWidth = 500,
            screenHeight = 800,
            targetWidth = 48,
            targetHeight = 48,
            leftMargin = 16,
            topMargin = 48,
            rightMargin = 16,
            bottomMargin = 48
        )
        val bounds64 = OverlayPositionGeometry.usableBounds(
            screenWidth = 500,
            screenHeight = 800,
            targetWidth = 64,
            targetHeight = 64,
            leftMargin = 16,
            topMargin = 48,
            rightMargin = 16,
            bottomMargin = 48
        )

        assertEquals(OverlayPosition(436, 704), OverlayPositionGeometry.clamp(OverlayPosition(900, 900), bounds48))
        assertEquals(OverlayPosition(420, 688), OverlayPositionGeometry.clamp(OverlayPosition(900, 900), bounds64))
    }

    @Test
    fun normalizedPositionIsPreservedWhenTargetSizeChanges() {
        val normalized = NormalizedOverlayPosition(0.7f, 0.4f)
        val smallTargetBounds = OverlayPositionGeometry.usableBounds(500, 800, 48, 48, 16, 48, 16, 48)
        val largeTargetBounds = OverlayPositionGeometry.usableBounds(500, 800, 64, 64, 16, 48, 16, 48)

        val resizedPosition = OverlayPositionGeometry.denormalize(normalized, largeTargetBounds)
        val restoredNormalized = OverlayPositionGeometry.normalize(resizedPosition, largeTargetBounds)

        assertEquals(0.7f, restoredNormalized.x, 0.001f)
        assertEquals(0.4f, restoredNormalized.y, 0.001f)
        assertTrue(resizedPosition.x <= smallTargetBounds.maxX)
        assertTrue(resizedPosition.x <= 500 - 64)
        assertTrue(resizedPosition.y <= 800 - 64)
    }
}
