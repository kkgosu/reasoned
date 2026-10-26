package com.kkgosu.learnharness

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.grishka.appkit.fragments.ToolbarFragment

/** Fullscreen static preview; zooming/opening saved diagrams never calls a model. */
class DiagramFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    private var figure: DiagramView? = null
    private var sourceDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, R.id.diagram_fit, 0, R.string.graph_fit)
        menu.add(0, R.id.diagram_source, 1, R.string.diagram_source)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.diagram_fit -> {
            figure?.fit(); true
        }

        R.id.diagram_source -> {
            val text = TextView(activity).apply {
                setTextAppearance(R.style.TextAppearance_Harness_Prose)
                text = arguments?.getString("source").orEmpty()
                setTextIsSelectable(true)
                val inset = resources.getDimensionPixelSize(R.dimen.space_24)
                setPadding(inset, inset, inset, inset)
            }
            sourceDialog = AlertDialog.Builder(activity).setTitle(R.string.diagram_source)
                .setView(android.widget.ScrollView(activity).apply { addView(text) })
                .setPositiveButton(android.R.string.ok, null).show()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(arguments?.getString("label") ?: getString(R.string.diagram_svg))
        val views = inflater.inflate(R.layout.fragment_diagram, container, false)
        val image = DiagramView(inflater.context).apply {
            contentDescription = arguments?.getString("label") ?: getString(R.string.diagram_svg)
        }
        figure = image
        views.findViewById<ViewGroup>(R.id.diagram_image).addView(image)
        val status = views.findViewById<TextView>(R.id.diagram_status)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                try {
                    val kind = arguments?.getString("kind").orEmpty()
                    val source = arguments?.getString("source").orEmpty()
                    val key = "$kind\n$source"
                    val bitmap = DiagramPlugin.cache.get(key) ?: DiagramRenderer(inflater.context)
                        .render(kind, source, targetPixels = 2048)
                        .also { DiagramPlugin.cache.put(key, it) }
                    image.bitmap = bitmap
                    image.zoom = savedInstanceState?.getFloat("zoom", 1f) ?: 1f
                    image.panX = savedInstanceState?.getFloat("panX", 0f) ?: 0f
                    image.panY = savedInstanceState?.getFloat("panY", 0f) ?: 0f
                    status.visibility = View.GONE
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.setText(R.string.diagram_failed)
                }
            }
        }
        return views
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        figure?.let {
            outState.putFloat("zoom", it.zoom); outState.putFloat(
            "panX",
            it.panX
        ); outState.putFloat("panY", it.panY)
        }
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; sourceDialog?.dismiss(); sourceDialog = null; figure = null
        super.onDestroyView()
    }
}

internal class DiagramView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value; invalidate()
        }
    var zoom = 1f
    var panX = 0f
    var panY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pinch =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale(detector.scaleFactor, detector.focusX, detector.focusY); return true
            }
        })
    private val gestures =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent) = performClick()
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1f) fit() else scale(2f, e.x, e.y); return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!pinch.isInProgress) {
                    panX -= distanceX; panY -= distanceY; invalidate()
                }; return true
            }
        })

    init {
        isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun fit() {
        zoom = 1f; panX = 0f; panY = 0f; invalidate()
    }

    private fun scale(factor: Float, x: Float = width / 2f, y: Float = height / 2f) {
        val next = (zoom * factor).coerceIn(1f, 8f)
        val ratio = next / zoom
        panX = (panX - x + width / 2f) * ratio + x - width / 2f
        panY = (panY - y + height / 2f) * ratio + y - height / 2f
        zoom = next; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height) * zoom
        val xLimit = ((image.width * scale - width) / 2).coerceAtLeast(0f)
        val yLimit = ((image.height * scale - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-xLimit, xLimit); panY = panY.coerceIn(-yLimit, yLimit)
        canvas.save()
        canvas.translate(
            (width - image.width * scale) / 2 + panX,
            (height - image.height * scale) / 2 + panY
        )
        canvas.scale(scale, scale); canvas.drawBitmap(image, 0f, 0f, paint); canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event); gestures.onTouchEvent(event); return true
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageView"; info.isScrollable = true
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                context.getString(R.string.graph_zoom_in)
            )
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                context.getString(R.string.graph_zoom_out)
            )
        )
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                scale(1.5f); true
            }

            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                scale(1 / 1.5f); true
            }

            else -> super.performAccessibilityAction(action, arguments)
        }
}
