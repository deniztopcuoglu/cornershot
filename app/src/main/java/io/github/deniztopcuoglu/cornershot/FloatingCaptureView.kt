package io.github.deniztopcuoglu.cornershot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal class FloatingCaptureView(context: Context) : View(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val screenPosition = IntArray(2)

    private var activePointerId = INVALID_POINTER
    private var downTime = 0L
    private var downRawX = 0f
    private var downRawY = 0f
    private var originLeft = 0
    private var originTop = 0
    private var currentLeft = 0
    private var currentTop = 0
    private var gestureBounds: UsableOverlayBounds? = null
    private var tapCancelled = false
    private var dragging = false
    private var visibleDiameterDp = ButtonSizeGeometry.DEFAULT_VISIBLE_DP

    internal var onDragPositionChanged: ((left: Int, top: Int) -> Unit)? = null
    internal var onDragReleased: ((NormalizedOverlayPosition) -> Unit)? = null
    internal var onDragCancelled: (() -> Unit)? = null

    private val longPressRunnable = Runnable {
        if (activePointerId != INVALID_POINTER && !tapCancelled && !dragging) beginDragMode()
    }

    init {
        updateCircleBackground()
        alpha = RESTING_ALPHA
        contentDescription = context.getString(R.string.capture_content_description)
        isClickable = true
        isFocusable = false
        isHapticFeedbackEnabled = true
        elevation = dp(5).toFloat()
    }

    @SuppressLint("RtlHardcoded")
    fun windowLayoutParams(position: NormalizedOverlayPosition): WindowManager.LayoutParams {
        val bounds = dragBounds()
        val pixelPosition = OverlayPositionGeometry.denormalize(position, bounds)
        val targetSize = touchTargetSizePx()
        return createLayoutParams(
            targetSize,
            Gravity.TOP or Gravity.LEFT,
            pixelPosition.x,
            pixelPosition.y
        )
    }

    @SuppressLint("RtlHardcoded")
    fun draggedWindowLayoutParams(left: Int, top: Int): WindowManager.LayoutParams {
        val bounds = dragBounds()
        val position = OverlayPositionGeometry.clamp(OverlayPosition(left, top), bounds)
        val targetSize = touchTargetSizePx()
        return createLayoutParams(targetSize, Gravity.TOP or Gravity.LEFT, position.x, position.y)
    }

    /** Updates the visible circle while leaving normalized position to be reapplied by the service. */
    internal fun updateVisibleDiameterDp(sizeDp: Int): Boolean {
        val sanitized = ButtonSizeGeometry.sanitizeVisibleSizeDp(sizeDp)
        if (visibleDiameterDp == sanitized) return false
        visibleDiameterDp = sanitized
        updateCircleBackground()
        return true
    }

    internal fun cancelTouchState() {
        clearTouchState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return beginTouch(event)
            MotionEvent.ACTION_MOVE -> {
                updateTouchPosition(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (finishTouch(event)) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch(restorePosition = true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelTouch(restorePosition = true)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    cancelTouch(restorePosition = true)
                }
                return true
            }
        }
        return activePointerId != INVALID_POINTER
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        clearTouchState()
        super.onDetachedFromWindow()
    }

    private fun beginTouch(event: MotionEvent): Boolean {
        clearTouchState()
        val bounds = runCatching { dragBounds() }.getOrNull() ?: return false
        activePointerId = event.getPointerId(0)
        downTime = event.eventTime
        downRawX = event.getRawX(0)
        downRawY = event.getRawY(0)
        getLocationOnScreen(screenPosition)
        val initial = OverlayPositionGeometry.clamp(OverlayPosition(screenPosition[0], screenPosition[1]), bounds)
        originLeft = initial.x
        originTop = initial.y
        currentLeft = initial.x
        currentTop = initial.y
        gestureBounds = bounds
        tapCancelled = false
        dragging = false
        postDelayed(longPressRunnable, longPressTimeout)
        return true
    }

    private fun updateTouchPosition(event: MotionEvent) {
        if (activePointerId == INVALID_POINTER) return
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelTouch(restorePosition = true)
            return
        }

        val rawX = event.getRawX(pointerIndex)
        val rawY = event.getRawY(pointerIndex)
        if (!dragging && !tapCancelled && event.eventTime - downTime >= longPressTimeout) beginDragMode()

        val deltaX = rawX - downRawX
        val deltaY = rawY - downRawY
        if (!dragging && !tapCancelled && deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
            tapCancelled = true
            removeCallbacks(longPressRunnable)
        }

        if (dragging) moveToPointer(rawX, rawY)
    }

    private fun finishTouch(event: MotionEvent): Boolean {
        if (activePointerId == INVALID_POINTER) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelTouch(restorePosition = true)
            return false
        }

        val elapsed = event.eventTime - downTime
        if (!dragging && !tapCancelled && elapsed >= longPressTimeout) beginDragMode()

        val rawX = event.getRawX(pointerIndex)
        val rawY = event.getRawY(pointerIndex)
        val deltaX = rawX - downRawX
        val deltaY = rawY - downRawY
        if (!dragging && !tapCancelled && deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
            tapCancelled = true
            removeCallbacks(longPressRunnable)
        }
        if (dragging) moveToPointer(rawX, rawY)

        val wasDragging = dragging
        val shouldCapture = !wasDragging && !tapCancelled
        val normalizedPosition = if (wasDragging) {
            val finalBounds = runCatching { dragBounds() }.getOrNull() ?: gestureBounds
            finalBounds?.let { bounds ->
                val position = OverlayPositionGeometry.clamp(OverlayPosition(currentLeft, currentTop), bounds)
                if (position.x != currentLeft || position.y != currentTop) {
                    currentLeft = position.x
                    currentTop = position.y
                    onDragPositionChanged?.invoke(currentLeft, currentTop)
                }
                OverlayPositionGeometry.normalize(position, bounds)
            }
        } else {
            null
        }
        clearTouchState()
        if (normalizedPosition != null) onDragReleased?.invoke(normalizedPosition)
        return shouldCapture
    }

    private fun moveToPointer(rawX: Float, rawY: Float) {
        val bounds = gestureBounds ?: return
        val position = OverlayPositionGeometry.clamp(
            OverlayPosition(
                (originLeft + rawX - downRawX).roundToInt(),
                (originTop + rawY - downRawY).roundToInt()
            ),
            bounds
        )
        if (position.x == currentLeft && position.y == currentTop) return
        currentLeft = position.x
        currentTop = position.y
        onDragPositionChanged?.invoke(currentLeft, currentTop)
    }

    private fun beginDragMode() {
        if (activePointerId == INVALID_POINTER || tapCancelled || dragging) return
        removeCallbacks(longPressRunnable)
        dragging = true
        alpha = DRAG_ALPHA
    }

    private fun cancelTouch(restorePosition: Boolean) {
        val hadDrag = dragging
        clearTouchState()
        if (hadDrag && restorePosition) onDragCancelled?.invoke()
    }

    private fun clearTouchState() {
        removeCallbacks(longPressRunnable)
        activePointerId = INVALID_POINTER
        gestureBounds = null
        tapCancelled = false
        dragging = false
        alpha = RESTING_ALPHA
    }

    private fun dragBounds(): UsableOverlayBounds {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = runCatching { windowManager.currentWindowMetrics }.getOrNull()
        val screenWidth = (metrics?.bounds?.width() ?: resources.displayMetrics.widthPixels).coerceAtLeast(0)
        val screenHeight = (metrics?.bounds?.height() ?: resources.displayMetrics.heightPixels).coerceAtLeast(0)
        val insets = metrics?.windowInsets
        val bars = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val cutout = insets?.getInsets(WindowInsets.Type.displayCutout())
        val gestures = insets?.getInsets(WindowInsets.Type.systemGestures())
        val safeLeft = maxOf(bars?.left ?: 0, cutout?.left ?: 0, gestures?.left ?: 0)
        val safeTop = maxOf(bars?.top ?: 0, cutout?.top ?: 0, gestures?.top ?: 0)
        val safeRight = maxOf(bars?.right ?: 0, cutout?.right ?: 0, gestures?.right ?: 0)
        val safeBottom = maxOf(bars?.bottom ?: 0, cutout?.bottom ?: 0, gestures?.bottom ?: 0)
        val minMarginX = maxOf(dp(16), safeLeft + dp(8))
        val maxMarginX = maxOf(dp(16), safeRight + dp(8))
        val minMarginY = maxOf(dp(48), safeTop + dp(8))
        val maxMarginY = maxOf(dp(48), safeBottom + dp(8))
        val targetSize = touchTargetSizePx()
        return OverlayPositionGeometry.usableBounds(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            targetWidth = targetSize,
            targetHeight = targetSize,
            leftMargin = minMarginX,
            topMargin = minMarginY,
            rightMargin = maxMarginX,
            bottomMargin = maxMarginY
        )
    }

    private fun touchTargetSizePx(): Int = dp(ButtonSizeGeometry.touchTargetSizeDp(visibleDiameterDp))

    private fun updateCircleBackground() {
        val targetDp = ButtonSizeGeometry.touchTargetSizeDp(visibleDiameterDp)
        val insetDp = (targetDp - visibleDiameterDp) / 2
        val circle = requireNotNull(ContextCompat.getDrawable(context, R.drawable.floating_button_background))
        background = InsetDrawable(circle, dp(insetDp))
        requestLayout()
        invalidate()
    }

    private fun createLayoutParams(size: Int, gravity: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            title = "CornerShot capture button"
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val RESTING_ALPHA = 0.72f
        private const val DRAG_ALPHA = 0.9f
        private const val INVALID_POINTER = -1
    }
}
