package com.kkgosu.reasoned.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.kkgosu.reasoned.R

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
