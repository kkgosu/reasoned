package com.kkgosu.learnharness

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Parcel
import android.os.Parcelable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class KnowledgeGraphView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var snapshot: GraphSnapshot? = null
        private set
    var camera = GraphCamera(0f, 0f, 1f)
        private set
    var selectedId: String? = null
        private set
    var onSelection: ((GraphNode?) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private var fitScale = 1f
    private val labelSize = resources.getDimension(R.dimen.text_12_5)
    private var fadingSelectionId: String? = null
    private var selectionProgress = 1f
    private var selectionAnimator: ValueAnimator? = null
    private var initialized = false
    private var restored: SavedState? = null
    private var matches: Set<String> = emptySet()
    private val ink = context.getColor(R.color.foreground)
    private val muted = context.getColor(R.color.muted_foreground)
    private val labelColorEvaluator = ArgbEvaluator()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.prose)
        textSize = resources.getDimension(R.dimen.text_12_5)
    }
    private val regionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.ui)
        textSize = resources.getDimension(R.dimen.text_12_5)
        textAlign = Paint.Align.CENTER
        color = muted
    }
    private var regionPaints: List<Paint> = emptyList()
    private var labels: List<String> = emptyList()
    private var labelWidths: List<Float> = emptyList()
    private var labelBounds: List<RectF> = emptyList()
    private val scratch = RectF()
    private var drawnLabels = 0
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera = camera.pan(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaleDetector.isInProgress && e2.pointerCount == 1) {
                camera = camera.pan(-distanceX, -distanceY)
                updateSelection()
            }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val point = snapshot?.pick(camera.worldX(e.x, width), camera.worldY(e.y, height), 24 * density / camera.scale)
            select(point?.node?.id)
            performClick()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomBy(2f, e.x, e.y)
            return true
        }
    })

    init {
        isClickable = true
        // Every proposition is available in the native, accessible full-screen search list.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSnapshot(value: GraphSnapshot) {
        snapshot = value
        val colors = intArrayOf(R.color.graph_region_0, R.color.graph_region_1, R.color.graph_region_2,
            R.color.graph_region_3, R.color.graph_region_4, R.color.graph_region_5)
        regionPaints = value.regions.mapIndexed { index, region ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(region.x, region.y, region.radius,
                    context.getColor(colors[index % colors.size]), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
        }
        labelPaint.textSize = labelSize
        labels = value.nodes.map { TextUtils.ellipsize(it.node.label, labelPaint, 132 * density, TextUtils.TruncateAt.END).toString() }
        labelWidths = labels.map(labelPaint::measureText)
        labelBounds = List(value.nodes.size + value.regions.size) { RectF() }
        initializeCamera()
        updateSelection()
    }

    fun highlight(ids: Set<String>) {
        if (matches == ids) return
        matches = ids
        invalidate()
    }

    fun select(id: String?, focus: Boolean = false) {
        val point = snapshot?.nodes?.firstOrNull { it.node.id == id }
        if (selectedId != point?.node?.id) {
            selectionAnimator?.cancel()
            fadingSelectionId = selectedId
            selectedId = point?.node?.id
            selectionProgress = 1f
            if (isShown && ValueAnimator.areAnimatorsEnabled()) {
                selectionProgress = 0f
                selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = resources.getInteger(R.integer.motion_short_ms).toLong()
                    addUpdateListener { selectionProgress = it.animatedValue as Float; invalidate() }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            fadingSelectionId = null
                            selectionProgress = 1f
                            invalidate()
                        }
                    })
                    start()
                }
            } else fadingSelectionId = null
        }
        if (focus && point != null) camera = GraphCamera(point.x, point.y, fitScale * 3f)
        updateSelection()
    }

    fun fit() {
        val bounds = snapshot?.bounds ?: return
        if (width <= 0 || height <= 0) return
        camera = GraphCamera.fit(bounds, width, height, 24 * density)
        fitScale = camera.scale
        initialized = true
        updateSelection()
    }

    fun zoomBy(factor: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        if (!initialized) return
        camera = camera.zoom(factor, focusX, focusY, width, height, fitScale * 0.5f, fitScale * 12f)
        updateSelection()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initializeCamera()
        updateSelection()
    }

    private fun updateSelection() {
        onSelection?.invoke(snapshot?.nodes?.firstOrNull { it.node.id == selectedId }?.node)
        invalidate()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        selectionAnimator?.end()
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun initializeCamera() {
        val bounds = snapshot?.bounds ?: return
        if (width <= 0 || height <= 0) return
        val fit = GraphCamera.fit(bounds, width, height, 24 * density)
        val saved = restored
        camera = when {
            saved != null && saved.hasCamera -> GraphCamera(saved.x, saved.y, fit.scale * saved.zoom.coerceIn(0.5f, 12f))
            initialized -> camera.copy(scale = fit.scale * (camera.scale / fitScale))
            else -> fit
        }
        if (saved != null) selectedId = saved.selectedId
        fitScale = fit.scale
        initialized = true
        restored = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = snapshot ?: return
        if (!initialized) return
        canvas.save()
        canvas.translate(width / 2f - camera.x * camera.scale, height / 2f - camera.y * camera.scale)
        canvas.scale(camera.scale, camera.scale)
        data.regions.forEachIndexed { index, region ->
            canvas.drawCircle(region.x, region.y, region.radius, regionPaints[index])
        }
        paint.color = muted
        paint.alpha = 115
        paint.strokeWidth = density * 0.65f / camera.scale
        data.edges.forEach { (from, to) -> canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
        canvas.restore()
        val zoom = camera.scale / fitScale
        labelPaint.textSize = labelSize * zoom
        regionLabelPaint.textSize = labelSize * zoom
        data.nodes.forEach { point ->
            val x = camera.screenX(point.x, width)
            val y = camera.screenY(point.y, height)
            val selected = when (point.node.id) {
                selectedId -> selectionProgress
                fadingSelectionId -> 1f - selectionProgress
                else -> 0f
            }
            val emphasis = if (point.node.id in matches) 1f else selected
            paint.color = ink
            if (emphasis > 0f) {
                paint.alpha = (230 * emphasis).toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                canvas.drawCircle(x, y, 9 * density, paint)
                paint.style = Paint.Style.FILL
            }
            paint.alpha = if (matches.isEmpty()) 230 else (80 + 150 * emphasis).toInt()
            canvas.drawCircle(x, y, (2.5f + emphasis) * density, paint)
        }
        drawnLabels = 0
        // Selected labels take precedence; suppress colliding labels, never enlarge the dots into cards.
        data.nodes.forEachIndexed { index, point ->
            if (point.node.id == selectedId) {
                val opacity = if (point.node.id in matches || zoom >= 1.8f) 1f else selectionProgress
                drawLabel(canvas, point, index, zoom, opacity)
            }
        }
        data.nodes.forEachIndexed { index, point ->
            if (point.node.id != selectedId) {
                val opacity = if (point.node.id in matches || zoom >= 1.8f) 1f else
                    if (point.node.id == fadingSelectionId) 1f - selectionProgress else 0f
                if (opacity > 0f) drawLabel(canvas, point, index, zoom, opacity)
            }
        }
        if (zoom < 1.8f) data.regions.forEach { region ->
            val x = camera.screenX(region.x, width)
            val y = camera.screenY(region.y, height) + 38 * density * zoom
            val halfWidth = regionLabelPaint.measureText(region.label) / 2
            if (reserveLabel(x - halfWidth, y + regionLabelPaint.ascent(), x + halfWidth, y + regionLabelPaint.descent())) {
                canvas.drawText(region.label, x, y, regionLabelPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, point: GraphPoint, index: Int, zoom: Float, opacity: Float) {
        if (opacity <= 0f) return
        val x = camera.screenX(point.x, width)
        val y = camera.screenY(point.y, height) + 14 * density * zoom
        val labelWidth = labelWidths[index] * zoom
        if (x < 0 || x > width || y < 0 || y > height) return
        val remaining = width - labelWidth
        val left = (x - labelWidth / 2).coerceIn(remaining.coerceAtMost(0f), remaining.coerceAtLeast(0f))
        if (!reserveLabel(left, y + labelPaint.ascent(), left + labelWidth, y + labelPaint.descent())) return
        labelPaint.color = when {
            point.node.id in matches -> ink
            point.node.id == selectedId -> labelColorEvaluator.evaluate(selectionProgress, muted, ink) as Int
            else -> muted
        }
        labelPaint.alpha = (255 * opacity).toInt()
        canvas.drawText(labels[index], left, y, labelPaint)
    }

    // ponytail: O(n²) label decluttering and linear picking suit 30 nodes; use a spatial index for large graphs.
    private fun reserveLabel(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        scratch.set(left - 4 * density, top, right + 4 * density, bottom)
        if (!scratch.intersect(0f, 0f, width.toFloat(), height.toFloat())) return false
        if ((0 until drawnLabels).any { RectF.intersects(scratch, labelBounds[it]) }) return false
        labelBounds[drawnLabels++].set(scratch)
        return true
    }

    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.hasCamera = initialized
        it.x = camera.x; it.y = camera.y; it.zoom = camera.scale / fitScale; it.selectedId = selectedId
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) { super.onRestoreInstanceState(state); return }
        super.onRestoreInstanceState(state.superState)
        restored = state
        initializeCamera()
        updateSelection()
    }

    class SavedState : BaseSavedState {
        var hasCamera = false
        var x = 0f; var y = 0f; var zoom = 1f
        var selectedId: String? = null
        constructor(parent: Parcelable?) : super(parent)
        constructor(source: Parcel) : super(source) {
            hasCamera = source.readInt() != 0
            x = source.readFloat(); y = source.readFloat(); zoom = source.readFloat(); selectedId = source.readString()
        }
        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (hasCamera) 1 else 0)
            out.writeFloat(x); out.writeFloat(y); out.writeFloat(zoom); out.writeString(selectedId)
        }
        companion object {
            @JvmField val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel) = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
