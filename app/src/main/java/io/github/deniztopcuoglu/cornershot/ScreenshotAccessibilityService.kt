package io.github.deniztopcuoglu.cornershot

import androidx.core.content.ContextCompat
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenshotAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var worker: ExecutorService
    private var receiverRegistered = false
    private var connected = false
    private var captureInProgress = false
    private var selectionActivityLaunched = false
    private var floatingView: FloatingCaptureView? = null
    private var captureTimeout: Runnable? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                InternalActions.HIDE_OVERLAY -> removeOverlay()
                InternalActions.SHOW_OVERLAY -> refreshOverlay()
                InternalActions.CAPTURE_FINISHED -> {
                    captureInProgress = false
                    selectionActivityLaunched = false
                    clearCaptureTimeout()
                    AppPreferences.finishCaptureWorkflow(this@ScreenshotAccessibilityService)
                    refreshOverlay()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CornerShot-image-io").apply { isDaemon = true }
        }
        val filter = IntentFilter().apply {
            addAction(InternalActions.HIDE_OVERLAY)
            addAction(InternalActions.SHOW_OVERLAY)
            addAction(InternalActions.CAPTURE_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
        ContextCompat.registerReceiver(
            this,
            actionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        }
        receiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        if (!captureInProgress && AppPreferences.captureWorkflowStarted(this) && !AppPreferences.selectionLaunched(this)) {
            AppPreferences.finishCaptureWorkflow(this)
        }
        ScreenshotStore.cleanupStaleCache(this)
        refreshOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = refreshOverlay()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingView?.cancelTouchState()
        refreshOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (captureInProgress && !selectionActivityLaunched) resetPreSelectionCapture()
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (captureInProgress && !selectionActivityLaunched) resetPreSelectionCapture()
        clearCaptureTimeout()
        floatingView?.let { view ->
            view.onDragPositionChanged = null
            view.onDragReleased = null
            view.onDragCancelled = null
        }
        removeOverlay()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(actionReceiver) }
            receiverRegistered = false
        }
        if (::worker.isInitialized) worker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshOverlay() {
        if (!connected || !AppPreferences.captureButtonEnabled(this) || AppPreferences.captureWorkflowActive(this)) {
            removeOverlay()
            return
        }
        val configuredSizeDp = AppPreferences.captureButtonSizeDp(this)
        val view = floatingView ?: FloatingCaptureView(this).also { button ->
            button.setOnClickListener { beginCapture() }
            button.onDragPositionChanged = { left, top -> updateOverlayPosition(button, left, top) }
            button.onDragReleased = { position ->
                if (floatingView === button) {
                    AppPreferences.setNormalizedOverlayPosition(this, position)
                }
            }
            button.onDragCancelled = {
                if (floatingView === button) refreshOverlay()
            }
            floatingView = button
        }
        if (view.updateVisibleDiameterDp(configuredSizeDp)) view.cancelTouchState()
        val params = view.windowLayoutParams(AppPreferences.normalizedOverlayPosition(this))
        try {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
            else windowManager.addView(view, params)
        } catch (_: WindowManager.BadTokenException) {
            removeOverlay()
        } catch (_: IllegalArgumentException) {
            removeOverlay()
        } catch (_: IllegalStateException) {
            removeOverlay()
        }
    }

    private fun removeOverlay() {
        val view = floatingView ?: return
        if (!view.isAttachedToWindow) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // The window may have detached during a service or display transition.
        } catch (_: IllegalStateException) {
            // A concurrent window teardown already removed it.
        }
    }

    private fun updateOverlayPosition(view: FloatingCaptureView, left: Int, top: Int) {
        if (!connected || captureInProgress || view !== floatingView || !view.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(view, view.draggedWindowLayoutParams(left, top))
        } catch (_: WindowManager.BadTokenException) {
            view.cancelTouchState()
            refreshOverlay()
        } catch (_: IllegalArgumentException) {
            view.cancelTouchState()
            refreshOverlay()
        } catch (_: IllegalStateException) {
            view.cancelTouchState()
            refreshOverlay()
        }
    }

    private fun beginCapture() {
        if (captureInProgress || !connected) return
        if (AppPreferences.captureWorkflowActive(this)) return
        captureInProgress = true
        selectionActivityLaunched = false
        AppPreferences.beginCaptureWorkflow(this)
        clearCaptureTimeout()
        captureTimeout = Runnable {
            if (captureInProgress) failCapture(getString(R.string.capture_failed))
        }.also { mainHandler.postDelayed(it, CAPTURE_TIMEOUT_MS) }
        removeOverlay()

        // Two vsync callbacks let WindowManager commit and render the removed overlay before capture.
        val choreographer = Choreographer.getInstance()
        fun waitForFrame(framesLeft: Int) {
            choreographer.postFrameCallback {
                if (framesLeft > 1) waitForFrame(framesLeft - 1) else requestScreenshot()
            }
        }
        waitForFrame(2)
    }

    private fun requestScreenshot() {
        if (!captureInProgress) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    processScreenshot(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    failCapture(getString(R.string.capture_failed))
                }
            })
        } catch (_: RuntimeException) {
            failCapture(getString(R.string.capture_failed))
        }
    }

    private fun processScreenshot(screenshot: ScreenshotResult) {
        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
        val colorSpace: ColorSpace = screenshot.colorSpace
        try {
            worker.execute {
                var bufferOpen = true
                var copiedBitmap: Bitmap? = null
                var wrappedBitmap: Bitmap? = null
                var sourceFile: File? = null
                try {
                    wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        ?: error("Could not wrap screenshot buffer")
                    val bitmapCopy = wrappedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        ?: error("Could not copy screenshot bitmap")
                    copiedBitmap = bitmapCopy
                    wrappedBitmap.recycle()
                    wrappedBitmap = null
                    hardwareBuffer.close()
                    bufferOpen = false

                    val file = ScreenshotStore.createSourceFile(this)
                    sourceFile = file
                    ScreenshotStore.writeSource(bitmapCopy, file)
                    val sourceUri = ScreenshotStore.sourceUri(this, file)
                    bitmapCopy.recycle()
                    copiedBitmap = null
                    mainHandler.post {
                        if (!captureInProgress || !connected) {
                            file.delete()
                            return@post
                        }
                        try {
                            val launch = Intent(this, SelectionActivity::class.java).apply {
                                putExtra(InternalActions.EXTRA_SOURCE_URI, sourceUri.toString())
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            selectionActivityLaunched = true
                            AppPreferences.markSelectionLaunched(this)
                            startActivity(launch)
                            clearCaptureTimeout()
                        } catch (_: RuntimeException) {
                            file.delete()
                            failCapture(getString(R.string.capture_failed))
                        }
                    }
                } catch (_: Throwable) {
                    sourceFile?.delete()
                    mainHandler.post { failCapture(getString(R.string.capture_failed)) }
                } finally {
                    wrappedBitmap?.let { runCatching { it.recycle() } }
                    copiedBitmap?.let { runCatching { it.recycle() } }
                    if (bufferOpen) runCatching { hardwareBuffer.close() }
                }
            }
        } catch (_: RuntimeException) {
            runCatching { hardwareBuffer.close() }
            failCapture(getString(R.string.capture_failed))
        }
    }

    private fun failCapture(message: String) {
        if (!captureInProgress) return
        captureInProgress = false
        selectionActivityLaunched = false
        clearCaptureTimeout()
        AppPreferences.finishCaptureWorkflow(this)
        refreshOverlay()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resetPreSelectionCapture() {
        captureInProgress = false
        selectionActivityLaunched = false
        clearCaptureTimeout()
        AppPreferences.finishCaptureWorkflow(this)
    }

    private fun clearCaptureTimeout() {
        captureTimeout?.let(mainHandler::removeCallbacks)
        captureTimeout = null
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 60_000L
    }
}
