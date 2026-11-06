package com.mylegguy.gmsf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class SongBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val baseCell = minOf(dp(22f), resources.displayMetrics.heightPixels * 0.72f / 14f)
    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val fillPaint = Paint().apply { isAntiAlias = false }
    private val linePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = dp(1.1f)
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val playHeadPaint = Paint().apply { color = 0x33FFFFFF }
    private val playLinePaint = Paint().apply {
        color = 0xFFFFD400.toInt()
        strokeWidth = dp(2f)
        isAntiAlias = false
    }
    private val labelColumnPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val labelTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        isAntiAlias = false
        isFakeBoldText = true
    }
    private val noteFallbackPaint = Paint().apply {
        color = 0xFF7ED321.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFF000000.toInt()
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val emptyFallback = intArrayOf(
        0xFFFFAFBF.toInt(), 0xFFFFF4A8.toInt(), 0xFFBDF35A.toInt(),
        0xFFA5EAF2.toInt(), 0xFF7070FF.toInt(), 0xFFC9C9C9.toInt(), 0xFFFFFFFF.toInt()
    )

    private var cell = baseCell
    private var zoom = 1f
    private var scrollX = 0f
    private var scrollY = 0f
    private var playColumn = -1
    private var followPausedUntil = 0L

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onPlaceNote: ((Note) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(0xFFBDBDBD.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val boardHeight = (14f * baseCell).toInt()
        setMeasuredDimension(width, boardHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
    }

    private fun labelWidth(): Float = cell

    private fun cellAreaWidth(): Float = max(1f, width - labelWidth())

    private fun maxScrollX(): Float {
        return max(0f, AppState.song.width * cell - cellAreaWidth())
    }

    private fun maxScrollY(): Float {
        return max(0f, 14f * cell - height)
    }

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }

    fun setZoomLevel(value: Float) {
        zoom = value.coerceIn(0.65f, 2.4f)
        cell = max(dp(8f), baseCell * zoom)
        clampScroll()
        postInvalidateOnAnimation()
    }

    fun zoomBy(delta: Float) {
        setZoomLevel(zoom + delta)
    }

    fun zoomLevel(): Float = zoom

    fun resetView() {
        zoom = 1f
        cell = baseCell
        scrollX = 0f
        scrollY = 0f
        postInvalidateOnAnimation()
    }

    fun resetScroll() {
        scrollX = 0f
        scrollY = 0f
        postInvalidateOnAnimation()
    }

    fun scrollPage(direction: Int) {
        followPausedUntil = System.currentTimeMillis() + 2500L
        scrollX += direction * cellAreaWidth() * 0.8f
        clampScroll()
        postInvalidateOnAnimation()
    }

    fun setPlayColumn(column: Int, follow: Boolean = true) {
        clampScroll()
        playColumn = column
        if (follow && System.currentTimeMillis() > followPausedUntil) {
            revealColumn(column)
        }
        postInvalidateOnAnimation()
    }

    private fun revealColumn(column: Int) {
        if (cellAreaWidth() <= 1f) return
        val columnX = column * cell
        if (AppState.settings.centerPlayhead) {
            val target = cellAreaWidth() * 0.35f
            scrollX = (columnX - target).coerceIn(0f, maxScrollX())
        } else {
            val start = scrollX
            val end = scrollX + cellAreaWidth()
            if (columnX < start || columnX + cell > end) {
                scrollX = (columnX - cell).coerceIn(0f, maxScrollX())
            }
        }
    }

    fun centerOnPlayhead() {
        if (playColumn >= 0) {
            revealColumn(playColumn)
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val song = AppState.song
        val rows = 14
        val labelW = labelWidth()
        val cellArea = cellAreaWidth()
        val startColumn = max(0, floor(scrollX / cell).toInt())
        val endColumn = minOf(song.width - 1, ceil((scrollX + cellArea) / cell).toInt())
        val startRow = max(0, floor(scrollY / cell).toInt())
        val endRow = minOf(rows - 1, ceil((scrollY + height) / cell).toInt())

        fillPaint.color = 0xFFBDBDBD.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        for (row in startRow..endRow) {
            val y = row * cell - scrollY
            val empty = AssetStore.emptyCell(row)
            for (column in startColumn..endColumn) {
                if (column < 0) continue
                val x = column * cell - scrollX
                if (empty != null) {
                    paint.alpha = 255
                    canvas.drawBitmap(empty, null, RectF(x, y, x + cell, y + cell), paint)
                } else {
                    fillPaint.color = emptyFallback[row % emptyFallback.size]
                    canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                }
            }
        }

        for (row in startRow..endRow) {
            val y = row * cell - scrollY
            val label = AssetStore.rowLabel(row)
            val labelX = width.toFloat() - labelW
            if (label != null) {
                canvas.drawBitmap(label, null, RectF(labelX, y, labelX + labelW, y + cell), paint)
            } else {
                fillPaint.color = emptyFallback[row % emptyFallback.size]
                canvas.drawRect(labelX, y, labelX + labelW, y + cell, fillPaint)
                canvas.drawText(NoteNames.label(row), labelX + labelW / 2f, y + cell * 0.68f, labelTextPaint)
            }
        }

        for (row in startRow..endRow + 1) {
            val y = row * cell - scrollY
            canvas.drawLine(0f, y, cellArea, y, linePaint)
        }
        for (column in startColumn..endColumn + 1) {
            val x = column * cell - scrollX
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        if (startColumn > 0) {
            canvas.drawLine(0f, 0f, 0f, height.toFloat(), linePaint)
        }

        fillPaint.color = labelColumnPaint.color
        canvas.drawRect(width.toFloat() - labelW, 0f, width.toFloat(), height.toFloat(), fillPaint)
        for (row in startRow..endRow) {
            val y = row * cell - scrollY
            val label = AssetStore.rowLabel(row)
            val labelX = width.toFloat() - labelW
            if (label != null) {
                canvas.drawBitmap(label, null, RectF(labelX, y, labelX + labelW, y + cell), paint)
            } else {
                fillPaint.color = emptyFallback[row % emptyFallback.size]
                canvas.drawRect(labelX, y, labelX + labelW, y + cell, fillPaint)
                canvas.drawText(NoteNames.label(row), labelX + labelW / 2f, y + cell * 0.68f, labelTextPaint)
            }
        }
        canvas.drawLine(width.toFloat() - labelW, 0f, width.toFloat() - labelW, height.toFloat(), linePaint)

        if (playColumn in startColumn - 1..endColumn + 1 && playColumn >= 0) {
            val x = playColumn * cell - scrollX
            canvas.drawRect(x, 0f, x + cell, height.toFloat(), playHeadPaint)
            canvas.drawLine(x, 0f, x, height.toFloat(), playLinePaint)
        }

        for (note in song.notes) {
            if (note.columnNotVisible(startColumn, endColumn)) continue
            val x = note.col * cell - scrollX
            val y = note.row * cell - scrollY
            if (y + cell < 0 || y > height) continue
            val icon = AssetStore.noteIcon(note.instrument, note.accidental)
            val padding = cell * 0.08f
            if (icon != null) {
                paint.alpha = 255
                canvas.drawBitmap(icon, null, RectF(x + padding, y + padding, x + cell - padding, y + cell - padding), paint)
            } else {
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * 0.28f, noteFallbackPaint)
            }
            if (note.col == playColumn) {
                fillPaint.color = 0x33FFD400
                canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
            }
        }

        canvas.drawRect(0f, 0f, width.toFloat() - 1f, height.toFloat() - 1f, borderPaint)
    }

    private fun Note.columnNotVisible(start: Int, end: Int): Boolean {
        return col < start - 1 || col > end + 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                followPausedUntil = System.currentTimeMillis() + 2500L
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!dragging && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    scrollX -= dx
                    scrollY -= dy
                    clampScroll()
                    lastX = event.x
                    lastY = event.y
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    handleTap(event.x, event.y)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (x >= width - labelWidth()) return
        val row = ((y + scrollY) / cell).toInt()
        val column = ((x + scrollX) / cell).toInt()
        val song = AppState.song
        if (row !in 0..13 || column !in 0 until song.width) return

        val existing = song.notes.firstOrNull { it.row == row && it.col == column }
        if (AppState.eraser) {
            if (existing != null) {
                AppState.removeNoteAt(row, column)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                AppState.notifyChanged()
            }
            return
        }
        if (existing != null && existing.instrument == AppState.selectedFamily && existing.accidental == AppState.selectedAccidental) {
            AppState.removeNoteAt(row, column)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            AppState.notifyChanged()
            return
        }
        val note = Note(
            id = AppState.nextId(),
            row = row,
            col = column,
            instrument = AppState.selectedFamily,
            accidental = AppState.selectedAccidental,
            duration = 1,
            velocity = 0.9f
        )
        AppState.putNote(note)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPlaceNote?.invoke(note)
        AppState.notifyChanged()
    }

    private fun dp(value: Float): Float = value * density
}
