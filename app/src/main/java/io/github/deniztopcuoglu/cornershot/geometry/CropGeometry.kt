package io.github.deniztopcuoglu.cornershot.geometry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun normalized(): FloatRect = FloatRect(
        min(left, right), min(top, bottom), max(left, right), max(top, bottom)
    )
}

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class BitmapFit(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int
) {
    val imageBounds: FloatRect
        get() = FloatRect(
            offsetX,
            offsetY,
            offsetX + sourceWidth * scale,
            offsetY + sourceHeight * scale
        )

    fun sourceToView(rect: FloatRect): FloatRect = FloatRect(
        offsetX + rect.left * scale,
        offsetY + rect.top * scale,
        offsetX + rect.right * scale,
        offsetY + rect.bottom * scale
    )

    fun viewToSource(rect: FloatRect): FloatRect = FloatRect(
        (rect.left - offsetX) / scale,
        (rect.top - offsetY) / scale,
        (rect.right - offsetX) / scale,
        (rect.bottom - offsetY) / scale
    )
}

/** Pure coordinate conversion used to keep crop pixels aligned with the displayed image. */
object CropGeometry {
    fun fitCenter(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int): BitmapFit? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val scale = min(viewWidth.toFloat() / sourceWidth, viewHeight.toFloat() / sourceHeight)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        return BitmapFit(
            scale = scale,
            offsetX = (viewWidth - drawnWidth) / 2f,
            offsetY = (viewHeight - drawnHeight) / 2f,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
    }

    fun normalizeAndClamp(rect: FloatRect, minX: Float, minY: Float, maxX: Float, maxY: Float): FloatRect {
        val normalized = rect.normalized()
        return FloatRect(
            normalized.left.coerceIn(minX, maxX),
            normalized.top.coerceIn(minY, maxY),
            normalized.right.coerceIn(minX, maxX),
            normalized.bottom.coerceIn(minY, maxY)
        )
    }

    fun toSourcePixels(rect: FloatRect, sourceWidth: Int, sourceHeight: Int): IntRect {
        if (sourceWidth <= 0 || sourceHeight <= 0) return IntRect(0, 0, 0, 0)
        val clamped = normalizeAndClamp(rect, 0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
        val left = floor(clamped.left).toInt().coerceIn(0, sourceWidth)
        val top = floor(clamped.top).toInt().coerceIn(0, sourceHeight)
        val right = ceil(clamped.right).toInt().coerceIn(left, sourceWidth)
        val bottom = ceil(clamped.bottom).toInt().coerceIn(top, sourceHeight)
        return IntRect(left, top, right, bottom)
    }

    fun isTooSmall(rect: FloatRect, minimumDimension: Float = 24f): Boolean {
        val normalized = rect.normalized()
        return normalized.width < minimumDimension || normalized.height < minimumDimension
    }
}
