package io.github.deniztopcuoglu.cornershot

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SelectionActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService
    private lateinit var root: FrameLayout
    private lateinit var selectionView: SelectionView
    private lateinit var actionContainer: FrameLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var progress: ProgressBar
    private val actionButtons = mutableListOf<TextView>()
    private var sourceUri: Uri? = null
    private var terminalHandled = false
    private var loadGeneration = 0
    private var progressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CornerShot-selection-io").apply { isDaemon = true }
        }
        sourceUri = savedInstanceState?.getString(STATE_SOURCE_URI)?.let(Uri::parse)
            ?: intent.getStringExtra(InternalActions.EXTRA_SOURCE_URI)?.let(Uri::parse)
        configureImmersiveWindow()
        createInterface()
        installBackHandling()
        if (sourceUri == null) {
            failAndClose()
        } else {
            loadSource(sourceUri!!)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sourceUri?.let { outState.putString(STATE_SOURCE_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureImmersiveWindow()
        root.post {
            if (selectionView.state == SelectionState.SELECTED || selectionView.state == SelectionState.PROCESSING) {
                positionActionMenu()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureImmersiveWindow()
    }

    override fun onDestroy() {
        progressRunnable?.let(mainHandler::removeCallbacks)
        loadGeneration++
        if (isFinishing && !isChangingConfigurations && !terminalHandled) {
            finishWorkflow(deleteSource = true)
        }
        if (::worker.isInitialized) worker.shutdownNow()
        selectionViewOrNull()?.sourceBitmap()?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        super.onDestroy()
    }

    private fun configureImmersiveWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun createInterface() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        selectionView = SelectionView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            onStateChanged = ::onSelectionStateChanged
            onSmallSelection = { Toast.makeText(this@SelectionActivity, R.string.selection_too_small, Toast.LENGTH_SHORT).show() }
        }
        root.addView(selectionView, FrameLayout.LayoutParams(-1, -1))
        createActionMenu()
        setContentView(root)
    }

    private fun createActionMenu() {
        actionContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.menu_surface))
                setStroke(dp(1), ColorUtils.setAlphaComponent(getColor(R.color.menu_text), 35))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            clipToOutline = true
        }
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val labels: List<Pair<Int, () -> Unit>> = listOf(
            R.string.save to ::saveSelection,
            R.string.copy to ::copySelection,
            R.string.retry to { retrySelection() },
            R.string.cancel to { cancelSelection() }
        )
        labels.forEach { (labelRes, action) ->
            val button = TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                setTextColor(getColor(R.color.menu_text))
                gravity = Gravity.CENTER
                minHeight = dp(52)
                isClickable = true
                isFocusable = true
                contentDescription = getString(labelRes)
                setOnClickListener { action() }
            }
            actionButtons += button
            actionRow.addView(button, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        actionContainer.addView(actionRow, FrameLayout.LayoutParams(-1, dp(56)))
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.color_accent))
            visibility = View.GONE
            contentDescription = getString(R.string.processing)
        }
        val progressSize = dp(22)
        actionContainer.addView(progress, FrameLayout.LayoutParams(progressSize, progressSize, Gravity.END or Gravity.TOP).apply {
            marginEnd = dp(8)
            topMargin = dp(17)
        })
        root.addView(actionContainer, FrameLayout.LayoutParams(-2, dp(56)))
    }

    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (selectionView.state) {
                    SelectionState.SELECTING -> selectionView.cancelCurrentDrag()
                    SelectionState.SELECTED -> selectionView.retry()
                    SelectionState.IDLE -> cancelSelection()
                    SelectionState.PROCESSING -> Unit
                }
            }
        })
    }

    private fun loadSource(uri: Uri) {
        val generation = ++loadGeneration
        worker.execute {
            val decoded = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = false
                    })
                } ?: error("Screenshot source missing")
            }.getOrNull()
            mainHandler.post {
                if (generation != loadGeneration || isFinishing || isDestroyed) {
                    decoded?.recycle()
                    return@post
                }
                if (decoded == null || decoded.width <= 0 || decoded.height <= 0) {
                    decoded?.recycle()
                    failAndClose()
                } else {
                    selectionView.setSource(decoded)
                    selectionView.requestFocus()
                }
            }
        }
    }

    private fun onSelectionStateChanged(state: SelectionState) {
        when (state) {
            SelectionState.SELECTED -> {
                actionContainer.visibility = View.VISIBLE
                actionButtons.forEach { it.isEnabled = true; it.alpha = 1f }
                progress.visibility = View.GONE
                positionActionMenu()
            }
            SelectionState.PROCESSING -> {
                actionButtons.forEach { it.isEnabled = false; it.alpha = 0.55f }
                progressRunnable?.let(mainHandler::removeCallbacks)
                progressRunnable = Runnable {
                    if (selectionView.state == SelectionState.PROCESSING) progress.visibility = View.VISIBLE
                }.also { mainHandler.postDelayed(it, 250L) }
            }
            SelectionState.IDLE, SelectionState.SELECTING -> {
                progressRunnable?.let(mainHandler::removeCallbacks)
                progress.visibility = View.GONE
                actionContainer.visibility = View.GONE
            }
        }
    }

    private fun positionActionMenu() {
        if (root.width <= 0 || root.height <= 0) return
        actionContainer.measure(
            View.MeasureSpec.makeMeasureSpec(minOf(dp(288), root.width - dp(24)).coerceAtLeast(dp(1)), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
        )
        val menuWidth = actionContainer.measuredWidth
        val menuHeight = actionContainer.measuredHeight
        val rect = selectionView.selectionViewRect() ?: return
        val safeInsets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val marginLeft = maxOf(dp(12), (safeInsets?.left ?: 0) + dp(8))
        val marginTop = maxOf(dp(12), (safeInsets?.top ?: 0) + dp(8))
        val marginRight = maxOf(dp(12), (safeInsets?.right ?: 0) + dp(8))
        val marginBottom = maxOf(dp(12), (safeInsets?.bottom ?: 0) + dp(8))
        val centerX = (rect.left + rect.right) / 2f
        val left = (centerX - menuWidth / 2f).toInt().coerceIn(marginLeft, (root.width - menuWidth - marginRight).coerceAtLeast(marginLeft))
        val above = rect.top.toInt() - menuHeight - marginTop
        val below = rect.bottom.toInt() + marginBottom
        val top = when {
            above >= marginTop -> above
            below + menuHeight <= root.height - marginBottom -> below
            else -> (rect.top - menuHeight - marginTop).toInt().coerceIn(marginTop, (root.height - menuHeight - marginBottom).coerceAtLeast(marginTop))
        }
        val params = actionContainer.layoutParams as FrameLayout.LayoutParams
        params.width = menuWidth
        params.height = menuHeight
        params.leftMargin = left
        params.topMargin = top
        params.gravity = Gravity.TOP or Gravity.LEFT
        actionContainer.layoutParams = params
    }

    private fun saveSelection() {
        if (selectionView.state != SelectionState.SELECTED) return
        val bitmap = selectionView.sourceBitmap() ?: return
        val rect = selectionView.selectionSourceRect() ?: return
        selectionView.beginProcessing()
        runImageOperation(
            operation = { ScreenshotStore.saveCrop(this, bitmap, rect) },
            onSuccess = {
                Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
                finishWorkflow(deleteSource = true)
                finishAndRemoveTask()
            },
            onFailure = {
                selectionView.processingFailed()
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun copySelection() {
        if (selectionView.state != SelectionState.SELECTED) return
        val bitmap = selectionView.sourceBitmap() ?: return
        val rect = selectionView.selectionSourceRect() ?: return
        selectionView.beginProcessing()
        runImageOperation(
            operation = {
                val uri = ScreenshotStore.createClipboardUri(this, bitmap, rect)
                ScreenshotStore.copyToClipboard(this, uri)
                uri
            },
            onSuccess = {
                Toast.makeText(this, R.string.screenshot_copied, Toast.LENGTH_SHORT).show()
                finishWorkflow(deleteSource = true)
                finishAndRemoveTask()
            },
            onFailure = {
                selectionView.processingFailed()
                Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runImageOperation(
        operation: () -> Uri,
        onSuccess: (Uri) -> Unit,
        onFailure: () -> Unit
    ) {
        worker.execute {
            val result = runCatching(operation)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess(onSuccess).onFailure { onFailure() }
            }
        }
    }

    private fun retrySelection() {
        selectionView.retry()
    }

    private fun cancelSelection() {
        if (selectionView.state == SelectionState.PROCESSING) return
        finishWorkflow(deleteSource = true)
        finishAndRemoveTask()
    }

    private fun failAndClose() {
        Toast.makeText(this, R.string.source_unavailable, Toast.LENGTH_SHORT).show()
        finishWorkflow(deleteSource = true)
        finishAndRemoveTask()
    }

    private fun finishWorkflow(deleteSource: Boolean) {
        if (terminalHandled) return
        terminalHandled = true
        if (deleteSource) ScreenshotStore.deleteSource(this, sourceUri)
        AppPreferences.finishCaptureWorkflow(this)
        sendBroadcast(InternalActions.intent(this, InternalActions.CAPTURE_FINISHED))
    }

    private fun selectionViewOrNull(): SelectionView? = if (::selectionView.isInitialized) selectionView else null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val STATE_SOURCE_URI = "selection_source_uri"
    }
}
