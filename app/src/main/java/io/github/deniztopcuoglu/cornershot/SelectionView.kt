package io.github.deniztopcuoglu.cornershot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.deniztopcuoglu.cornershot.geometry.CropGeometry
import io.github.deniztopcuoglu.cornershot.geometry.FloatRect
import io.github.deniztopcuoglu.cornershot.geometry.IntRect

enum class SelectionState { IDLE, SELECTING, SELECTED, PROCESSING }

class SelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var state: SelectionState = SelectionState.IDLE
        private set

    private var sourceBitmap: Bitmap? = null
    private var selectionInView: RectF? = null
    private var selectionInSource: IntRect? = null
    private var startX = 0f
    private var startY = 0f
    private val bitmapMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val imageBounds = RectF()
    private val scratchRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    var onStateChanged: ((SelectionState) -> Unit)? = null
    var onSmallSelection: (() -> Unit)? = null

    fun setSource(bitmap: Bitmap) {
        sourceBitmap = bitmap
        state = SelectionState.IDLE
        selectionInView = null
        selectionInSource = null
        updateTransform()
        invalidate()
        onStateChanged?.invoke(state)
    }

    fun sourceBitmap(): Bitmap? = sourceBitmap

    fun selectionSourceRect(): IntRect? = selectionInSource

    fun selectionViewRect(): RectF? = selectionInView?.let { RectF(it) }

    fun beginProcessing() {
        if (state != SelectionState.SELECTED) return
        setState(SelectionState.PROCESSING)
    }

    fun processingFailed() {
        if (state == SelectionState.PROCESSING) setState(SelectionState.SELECTED)
    }

    fun retry() {
        if (state == SelectionState.PROCESSING) return
        selectionInView = null
        selectionInSource = null
        setState(SelectionState.IDLE)
        invalidate()
    }

    fun cancelCurrentDrag() {
        if (state != SelectionState.SELECTING) return
        selectionInView = null
        selectionInSource = null
        setState(SelectionState.IDLE)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        val bitmap = sourceBitmap ?: return
        val computed = CropGeometry.fitCenter(bitmap.width, bitmap.height, width, height) ?: return
        bitmapMatrix.setValues(floatArrayOf(
            computed.scale, 0f, computed.offsetX,
            0f, computed.scale, computed.offsetY,
            0f, 0f, 1f
        ))
        bitmapMatrix.invert(inverseMatrix)
        val bounds = computed.imageBounds
        imageBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
        selectionInView?.let { oldRect ->
            val source = selectionInSource
            if (source != null) {
                val mapped = computed.sourceToView(FloatRect(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat()))
                oldRect.set(mapped.left, mapped.top, mapped.right, mapped.bottom)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = sourceBitmap ?: return
        if (width <= 0 || height <= 0) return
        canvas.drawBitmap(bitmap, bitmapMatrix, bitmapPaint)
        canvas.drawRect(imageBounds, scrimPaint)

        val selectedRect = selectionInView ?: return
        canvas.save()
        canvas.clipRect(selectedRect)
        canvas.drawBitmap(bitmap, bitmapMatrix, bitmapPaint)
        canvas.restore()
        canvas.drawRect(selectedRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state != SelectionState.IDLE || !imageBounds.contains(event.x, event.y)) return false
                startX = event.x.coerceIn(imageBounds.left, imageBounds.right)
                startY = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
                selectionInView = RectF(startX, startY, startX, startY)
                selectionInSource = null
                parent?.requestDisallowInterceptTouchEvent(true)
                setState(SelectionState.SELECTING)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (state != SelectionState.SELECTING) return false
                updateDrag(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (state != SelectionState.SELECTING) return false
                updateDrag(event.x, event.y)
                val current = selectionInView ?: return false
                if (CropGeometry.isTooSmall(current.toFloatRect(), 24f * resources.displayMetrics.density)) {
                    selectionInView = null
                    selectionInSource = null
                    setState(SelectionState.IDLE)
                    onSmallSelection?.invoke()
                    invalidate()
                    return true
                }
                scratchRect.set(current)
                inverseMatrix.mapRect(scratchRect)
                val bitmap = sourceBitmap ?: return false
                val sourceRect = CropGeometry.toSourcePixels(scratchRect.toFloatRect(), bitmap.width, bitmap.height)
                if (sourceRect.width <= 0 || sourceRect.height <= 0) {
                    selectionInView = null
                    setState(SelectionState.IDLE)
                    invalidate()
                    return true
                }
                selectionInSource = sourceRect
                setState(SelectionState.SELECTED)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (state == SelectionState.SELECTING) cancelCurrentDrag()
                return true
            }
        }
        return state == SelectionState.SELECTING
    }

    private fun updateDrag(x: Float, y: Float) {
        val current = selectionInView ?: return
        val endX = x.coerceIn(imageBounds.left, imageBounds.right)
        val endY = y.coerceIn(imageBounds.top, imageBounds.bottom)
        current.set(
            minOf(startX, endX),
            minOf(startY, endY),
            maxOf(startX, endX),
            maxOf(startY, endY)
        )
        invalidate()
    }

    private fun setState(newState: SelectionState) {
        if (state == newState) return
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun RectF.toFloatRect() = FloatRect(left, top, right, bottom)
}
