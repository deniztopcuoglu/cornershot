package io.github.deniztopcuoglu.cornershot.geometry

import io.github.deniztopcuoglu.cornershot.CaptureCorner
import io.github.deniztopcuoglu.cornershot.NormalizedOverlayPosition
import io.github.deniztopcuoglu.cornershot.OverlayPosition
import io.github.deniztopcuoglu.cornershot.OverlayPositionGeometry
import io.github.deniztopcuoglu.cornershot.UsableOverlayBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionGeometryTest {
    @Test fun clampsInteractiveTargetInsideUsableBounds() {
        val bounds = UsableOverlayBounds(minX = 24, maxX = 352, minY = 48, maxY = 720)
        assertEquals(OverlayPosition(24, 720), OverlayPositionGeometry.clamp(OverlayPosition(-10, 900), bounds))
    }

    @Test fun normalizesDropPositionRelativeToUsableBounds() {
        val bounds = UsableOverlayBounds(minX = 40, maxX = 440, minY = 80, maxY = 680)
        val position = OverlayPosition(320, 320)
        assertEquals(NormalizedOverlayPosition(0.7f, 0.4f), OverlayPositionGeometry.normalize(position, bounds))
    }

    @Test fun preservesRelativePositionAcrossDisplaySizes() {
        val portraitBounds = UsableOverlayBounds(minX = 24, maxX = 576, minY = 48, maxY = 1048)
        val landscapeBounds = UsableOverlayBounds(minX = 36, maxX = 1736, minY = 32, maxY = 760)
        val normalized = NormalizedOverlayPosition(0.7f, 0.4f)
        val portraitPosition = OverlayPositionGeometry.denormalize(normalized, portraitBounds)
        assertEquals(0.7f, OverlayPositionGeometry.normalize(portraitPosition, portraitBounds).x, 0.001f)
        assertEquals(0.4f, OverlayPositionGeometry.normalize(portraitPosition, portraitBounds).y, 0.001f)
        assertEquals(1226, OverlayPositionGeometry.denormalize(normalized, landscapeBounds).x)
        assertEquals(323, OverlayPositionGeometry.denormalize(normalized, landscapeBounds).y)
    }

    @Test fun clampsAndNormalizesAllCornerPresets() {
        val bounds = UsableOverlayBounds(minX = 10, maxX = 90, minY = 20, maxY = 180)
        assertEquals(OverlayPosition(10, 20), OverlayPositionGeometry.denormalize(OverlayPositionGeometry.preset(CaptureCorner.TOP_LEFT), bounds))
        assertEquals(OverlayPosition(90, 20), OverlayPositionGeometry.denormalize(OverlayPositionGeometry.preset(CaptureCorner.TOP_RIGHT), bounds))
        assertEquals(OverlayPosition(10, 180), OverlayPositionGeometry.denormalize(OverlayPositionGeometry.preset(CaptureCorner.BOTTOM_LEFT), bounds))
        assertEquals(OverlayPosition(90, 180), OverlayPositionGeometry.denormalize(OverlayPositionGeometry.preset(CaptureCorner.BOTTOM_RIGHT), bounds))
    }
}
