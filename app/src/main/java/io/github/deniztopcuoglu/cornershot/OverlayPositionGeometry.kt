package io.github.deniztopcuoglu.cornershot

import kotlin.math.roundToInt

internal data class OverlayPosition(val x: Int, val y: Int)

internal data class UsableOverlayBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
)

internal data class NormalizedOverlayPosition(val x: Float, val y: Float)

/** Pure geometry for free overlay placement and persistence across display sizes. */
internal object OverlayPositionGeometry {
    /** Computes the valid top-left range for a target of the current size. */
    fun usableBounds(
        screenWidth: Int,
        screenHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        leftMargin: Int,
        topMargin: Int,
        rightMargin: Int,
        bottomMargin: Int
    ): UsableOverlayBounds {
        val furthestX = (screenWidth - targetWidth).coerceAtLeast(0)
        val furthestY = (screenHeight - targetHeight).coerceAtLeast(0)
        val minX = leftMargin.coerceIn(0, furthestX)
        val minY = topMargin.coerceIn(0, furthestY)
        val maxX = (screenWidth - rightMargin - targetWidth).coerceIn(minX, furthestX)
        val maxY = (screenHeight - bottomMargin - targetHeight).coerceIn(minY, furthestY)
        return UsableOverlayBounds(minX, maxX, minY, maxY)
    }

    fun clamp(position: OverlayPosition, bounds: UsableOverlayBounds): OverlayPosition = OverlayPosition(
        x = position.x.coerceIn(bounds.minX, bounds.maxX),
        y = position.y.coerceIn(bounds.minY, bounds.maxY)
    )

    fun normalize(position: OverlayPosition, bounds: UsableOverlayBounds): NormalizedOverlayPosition {
        val clamped = clamp(position, bounds)
        val width = bounds.maxX - bounds.minX
        val height = bounds.maxY - bounds.minY
        return NormalizedOverlayPosition(
            x = if (width == 0) 0f else (clamped.x - bounds.minX).toFloat() / width,
            y = if (height == 0) 0f else (clamped.y - bounds.minY).toFloat() / height
        )
    }

    fun denormalize(position: NormalizedOverlayPosition, bounds: UsableOverlayBounds): OverlayPosition {
        val x = position.x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val y = position.y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        return clamp(
            OverlayPosition(
                x = bounds.minX + ((bounds.maxX - bounds.minX) * x).roundToInt(),
                y = bounds.minY + ((bounds.maxY - bounds.minY) * y).roundToInt()
            ),
            bounds
        )
    }

    fun preset(corner: CaptureCorner): NormalizedOverlayPosition = when (corner) {
        CaptureCorner.TOP_LEFT -> NormalizedOverlayPosition(0f, 0f)
        CaptureCorner.TOP_RIGHT -> NormalizedOverlayPosition(1f, 0f)
        CaptureCorner.BOTTOM_LEFT -> NormalizedOverlayPosition(0f, 1f)
        CaptureCorner.BOTTOM_RIGHT -> NormalizedOverlayPosition(1f, 1f)
    }

    fun clampNormalized(position: NormalizedOverlayPosition): NormalizedOverlayPosition =
        NormalizedOverlayPosition(
            x = position.x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            y = position.y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        )

}
