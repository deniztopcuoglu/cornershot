package io.github.deniztopcuoglu.cornershot

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager

internal class FloatingCaptureView(context: Context) : View(context) {
    init {
        setBackgroundResource(R.drawable.floating_button_background)
        alpha = 0.72f
        contentDescription = context.getString(R.string.capture_content_description)
        isClickable = true
        isFocusable = false
        isHapticFeedbackEnabled = true
        elevation = dp(5).toFloat()
    }

    fun windowLayoutParams(corner: CaptureCorner): WindowManager.LayoutParams {
        // Keep a 48dp hit target; the inset background renders as a 36dp circle (75% of 48dp).
        val width = dp(48)
        val gravity = when (corner) {
            CaptureCorner.TOP_LEFT -> Gravity.TOP or Gravity.LEFT
            CaptureCorner.TOP_RIGHT -> Gravity.TOP or Gravity.RIGHT
            CaptureCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.LEFT
            CaptureCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
        }
        val insets = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.currentWindowMetrics.windowInsets
        }.getOrNull()
        val bars = insets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        val cutout = insets?.getInsets(android.view.WindowInsets.Type.displayCutout())
        val gestures = insets?.getInsets(android.view.WindowInsets.Type.systemGestures())
        val safeLeft = maxOf(bars?.left ?: 0, cutout?.left ?: 0, gestures?.left ?: 0)
        val safeTop = maxOf(bars?.top ?: 0, cutout?.top ?: 0, gestures?.top ?: 0)
        val safeRight = maxOf(bars?.right ?: 0, cutout?.right ?: 0, gestures?.right ?: 0)
        val safeBottom = maxOf(bars?.bottom ?: 0, cutout?.bottom ?: 0, gestures?.bottom ?: 0)
        val marginX = when (corner) {
            CaptureCorner.TOP_LEFT, CaptureCorner.BOTTOM_LEFT -> maxOf(dp(16), safeLeft + dp(8))
            CaptureCorner.TOP_RIGHT, CaptureCorner.BOTTOM_RIGHT -> maxOf(dp(16), safeRight + dp(8))
        }
        val marginY = when (corner) {
            CaptureCorner.TOP_LEFT, CaptureCorner.TOP_RIGHT -> maxOf(dp(48), safeTop + dp(8))
            CaptureCorner.BOTTOM_LEFT, CaptureCorner.BOTTOM_RIGHT -> maxOf(dp(48), safeBottom + dp(8))
        }
        return WindowManager.LayoutParams(
            width,
            width,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = marginX
            y = marginY
            title = "CornerShot capture button"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
