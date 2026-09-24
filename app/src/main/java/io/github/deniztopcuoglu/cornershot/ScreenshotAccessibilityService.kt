package io.github.deniztopcuoglu.cornershot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private enum class CaptureState {
    IDLE,
    CAPTURE_PREPARING,
    CAPTURE_IN_PROGRESS,
    RECOVERING,
    SELECTION_ACTIVE
}

class ScreenshotAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var worker: ExecutorService
    private var receiverRegistered = false
    private var connected = false
    private var captureState = CaptureState.IDLE
    private var captureGeneration = 0
    private var captureStartedAt = 0L
    private var pendingCaptureBarrier: Runnable? = null
    private var logNextOverlayRestore = false
    private var floatingView: FloatingCaptureView? = null
    private var captureTimeout: Runnable? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                InternalActions.HIDE_OVERLAY -> removeOverlay()
                InternalActions.SHOW_OVERLAY -> refreshOverlay()
                InternalActions.CAPTURE_FINISHED -> {
                    if (captureState == CaptureState.SELECTION_ACTIVE ||
                        (captureState == CaptureState.IDLE && AppPreferences.selectionLaunched(this@ScreenshotAccessibilityService))
                    ) {
                        finishSelectionWorkflow()
                    }
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
        if (captureState == CaptureState.IDLE && AppPreferences.captureWorkflowStarted(this)) {
            if (!AppPreferences.captureWorkflowActive(this)) {
                captureState = CaptureState.IDLE
            } else if (AppPreferences.selectionLaunched(this)) {
                captureState = CaptureState.SELECTION_ACTIVE
            } else {
                captureState = CaptureState.RECOVERING
                scheduleOrphanedCaptureRecovery()
            }
        } else if (captureState == CaptureState.RECOVERING) {
            scheduleOrphanedCaptureRecovery()
        }
        ScreenshotStore.cleanupStaleCache(this)
        refreshOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = refreshOverlay()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingView?.cancelTouchState()
        if (captureState == CaptureState.CAPTURE_PREPARING) {
            pendingCaptureBarrier?.let(mainHandler::removeCallbacks)
            pendingCaptureBarrier = null
            captureGeneration += 1
            logCapture("display configuration changed; restarting removal barrier")
            prepareCaptureOverlay(captureGeneration)
        } else {
            refreshOverlay()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (captureState == CaptureState.CAPTURE_PREPARING) {
            resetPreSelectionCapture()
        } else if (captureState == CaptureState.CAPTURE_IN_PROGRESS) {
            captureState = CaptureState.RECOVERING
            clearCaptureTimeout()
            scheduleOrphanedCaptureRecovery()
        }
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (captureState == CaptureState.CAPTURE_PREPARING) {
            resetPreSelectionCapture()
        } else if (captureState == CaptureState.CAPTURE_IN_PROGRESS) {
            // The screenshot request has already crossed the service boundary; keep the
            // persisted workflow gate so a replacement service cannot re-add the overlay.
            captureState = CaptureState.RECOVERING
        }
        clearCaptureTimeout()
        invalidateCaptureBarrier()
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
        if (!connected ||
            !AppPreferences.captureButtonEnabled(this) ||
            captureState != CaptureState.IDLE ||
            AppPreferences.captureWorkflowActive(this)
        ) {
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
            else {
                windowManager.addView(view, params)
                if (logNextOverlayRestore) {
                    logCapture("overlay restored")
                    logNextOverlayRestore = false
                }
            }
        } catch (_: WindowManager.BadTokenException) {
            removeOverlay()
        } catch (_: IllegalArgumentException) {
            removeOverlay()
        } catch (_: IllegalStateException) {
            removeOverlay()
        }
    }

    /** removeViewImmediate synchronously detaches the ViewRoot before the compositor barrier starts. */
    private fun removeOverlay(): Boolean {
        val view = floatingView ?: return true
        if (!view.isAttachedToWindow) return true
        if (captureState == CaptureState.CAPTURE_PREPARING) logCapture("overlay remove requested")
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: WindowManager.BadTokenException) {
            // Verify detachment below; capture is aborted if WindowManager kept the surface attached.
        } catch (_: IllegalArgumentException) {
            // The window may have detached during a service or display transition.
        } catch (_: IllegalStateException) {
            // A concurrent window teardown already removed it.
        } catch (_: RuntimeException) {
            // Treat an unexpected WindowManager failure as a failed detach and retry safely.
        }
        val detached = !view.isAttachedToWindow
        if (captureState == CaptureState.CAPTURE_PREPARING) logCapture("overlay detached=$detached")
        return detached
    }

    private fun updateOverlayPosition(view: FloatingCaptureView, left: Int, top: Int) {
        if (!connected || captureState != CaptureState.IDLE || view !== floatingView || !view.isAttachedToWindow) return
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
        if (captureState != CaptureState.IDLE || !connected) return
        if (AppPreferences.captureWorkflowActive(this)) return
        captureState = CaptureState.CAPTURE_PREPARING
        captureStartedAt = SystemClock.uptimeMillis()
        val generation = ++captureGeneration
        logCapture("capture requested")
        AppPreferences.beginCaptureWorkflow(this)
        clearCaptureTimeout()
        captureTimeout = Runnable {
            if (captureState == CaptureState.CAPTURE_PREPARING || captureState == CaptureState.CAPTURE_IN_PROGRESS) {
                failCapture(getString(R.string.capture_failed))
            }
        }.also { mainHandler.postDelayed(it, CAPTURE_TIMEOUT_MS) }
        prepareCaptureOverlay(generation)
    }

    private fun prepareCaptureOverlay(generation: Int, detachRetries: Int = 0, barrierRestarts: Int = 0) {
        if (!isPreparing(generation)) return
        if (!removeOverlay()) {
            if (detachRetries >= MAX_DETACH_RETRIES) {
                logCapture("overlay detach failed after retries")
                failCapture(getString(R.string.capture_failed))
            } else {
                scheduleCaptureBarrier(DETACH_RETRY_DELAY_MS) {
                    prepareCaptureOverlay(generation, detachRetries + 1, barrierRestarts)
                }
            }
            return
        }
        awaitCompositorFrames(generation, frameNumber = 1, barrierRestarts = barrierRestarts)
    }

    private fun awaitCompositorFrames(generation: Int, frameNumber: Int, barrierRestarts: Int) {
        if (!isPreparing(generation)) return
        Choreographer.getInstance().postFrameCallback {
            if (!isPreparing(generation)) return@postFrameCallback
            logCapture("post-detach frame $frameNumber/$COMPOSITOR_FRAME_COUNT")
            if (frameNumber < COMPOSITOR_FRAME_COUNT) {
                awaitCompositorFrames(generation, frameNumber + 1, barrierRestarts)
                return@postFrameCallback
            }
            scheduleCaptureBarrier(COMPOSITOR_SETTLE_DELAY_MS) {
                if (!isPreparing(generation)) return@scheduleCaptureBarrier
                Choreographer.getInstance().postFrameCallback settleFrame@{
                    if (!isPreparing(generation)) return@settleFrame
                    logCapture("post-settle frame")
                    if (floatingView?.isAttachedToWindow == true) {
                        if (barrierRestarts >= MAX_BARRIER_RESTARTS) {
                            logCapture("overlay reattached during compositor settle")
                            failCapture(getString(R.string.capture_failed))
                        } else {
                            logCapture("overlay reattached during settle; restarting barrier")
                            prepareCaptureOverlay(generation, barrierRestarts = barrierRestarts + 1)
                        }
                        return@settleFrame
                    }
                    captureState = CaptureState.CAPTURE_IN_PROGRESS
                    requestScreenshot()
                }
            }
        }
    }

    private fun scheduleCaptureBarrier(delayMs: Long, action: () -> Unit) {
        pendingCaptureBarrier?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            pendingCaptureBarrier = null
            action()
        }
        pendingCaptureBarrier = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun isPreparing(generation: Int): Boolean =
        captureState == CaptureState.CAPTURE_PREPARING && captureGeneration == generation

    private fun requestScreenshot() {
        if (captureState != CaptureState.CAPTURE_IN_PROGRESS) return
        if (floatingView?.isAttachedToWindow == true) {
            logCapture("overlay attached at screenshot gate; restarting removal barrier")
            captureState = CaptureState.CAPTURE_PREPARING
            val generation = ++captureGeneration
            prepareCaptureOverlay(generation)
            return
        }
        logCapture("screenshot API invoked")
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    logCapture("screenshot callback success")
                    processScreenshot(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    logCapture("screenshot callback failure code=$errorCode")
                    failCapture(getString(R.string.capture_failed))
                }
            })
        } catch (_: RuntimeException) {
            failCapture(getString(R.string.capture_failed))
        }
    }

    private fun processScreenshot(screenshot: ScreenshotResult) {
        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
        if (captureState != CaptureState.CAPTURE_IN_PROGRESS || !connected) {
            runCatching { hardwareBuffer.close() }
            return
        }
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
                        if (captureState != CaptureState.CAPTURE_IN_PROGRESS || !connected) {
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
                            AppPreferences.markSelectionLaunched(this)
                            captureState = CaptureState.SELECTION_ACTIVE
                            startActivity(launch)
                            clearCaptureTimeout()
                        } catch (_: RuntimeException) {
                            file.delete()
                            captureState = CaptureState.CAPTURE_IN_PROGRESS
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
        if (captureState == CaptureState.IDLE || captureState == CaptureState.SELECTION_ACTIVE) return
        captureState = CaptureState.IDLE
        invalidateCaptureBarrier()
        clearCaptureTimeout()
        AppPreferences.finishCaptureWorkflow(this)
        logNextOverlayRestore = true
        refreshOverlay()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resetPreSelectionCapture() {
        captureState = CaptureState.IDLE
        invalidateCaptureBarrier()
        clearCaptureTimeout()
        AppPreferences.finishCaptureWorkflow(this)
    }

    private fun scheduleOrphanedCaptureRecovery() {
        clearCaptureTimeout()
        captureTimeout = Runnable {
            if (captureState == CaptureState.RECOVERING) {
                captureState = CaptureState.IDLE
                invalidateCaptureBarrier()
                AppPreferences.finishCaptureWorkflow(this)
                logNextOverlayRestore = true
                refreshOverlay()
            }
        }.also { mainHandler.postDelayed(it, ORPHANED_CAPTURE_RECOVERY_MS) }
    }

    private fun finishSelectionWorkflow() {
        captureState = CaptureState.IDLE
        invalidateCaptureBarrier()
        clearCaptureTimeout()
        AppPreferences.finishCaptureWorkflow(this)
        logNextOverlayRestore = true
        refreshOverlay()
    }

    private fun invalidateCaptureBarrier() {
        captureGeneration += 1
        pendingCaptureBarrier?.let(mainHandler::removeCallbacks)
        pendingCaptureBarrier = null
    }

    private fun logCapture(event: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val elapsed = if (captureStartedAt == 0L) 0L else SystemClock.uptimeMillis() - captureStartedAt
            Log.d(CAPTURE_LOG_TAG, "t+${elapsed}ms $event")
        }
    }

    private fun clearCaptureTimeout() {
        captureTimeout?.let(mainHandler::removeCallbacks)
        captureTimeout = null
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 60_000L
        private const val ORPHANED_CAPTURE_RECOVERY_MS = 1_500L
        private const val DETACH_RETRY_DELAY_MS = 16L
        private const val MAX_DETACH_RETRIES = 3
        private const val COMPOSITOR_FRAME_COUNT = 2
        private const val COMPOSITOR_SETTLE_DELAY_MS = 32L
        private const val MAX_BARRIER_RESTARTS = 2
        private const val CAPTURE_LOG_TAG = "CornerShotCapture"
    }
}
