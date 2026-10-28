package com.kkgosu.reasoned

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.fragments.GraphSearchFragment
import com.kkgosu.reasoned.fragments.HomeFragment
import com.kkgosu.reasoned.model.GraphData
import com.kkgosu.reasoned.model.GraphNode
import com.kkgosu.reasoned.model.KnowledgeChain
import com.kkgosu.reasoned.model.KnowledgeNode
import com.kkgosu.reasoned.model.KnowledgeSnapshot
import com.kkgosu.reasoned.model.SampleGraph
import com.kkgosu.reasoned.ui.graph.GraphCamera
import com.kkgosu.reasoned.ui.graph.GraphLayout
import com.kkgosu.reasoned.ui.views.KnowledgeGraphView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GraphTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun settle() = instrumentation.uiAutomation.waitForIdle(500, 5000)

    @Test
    fun layoutCameraInversePickingAndZoomFocus() {
        val data = SampleGraph.create(instrumentation.targetContext.resources)
        val layout = GraphLayout.compute(data)
        assertEquals(layout, GraphLayout.compute(data))
        assertEquals(30, layout.nodes.size)
        assertEquals(30, layout.nodes.map { it.node.id }.distinct().size)
        val fitted = GraphCamera.fit(layout.bounds, 800, 1000, 40f)
        assertTrue(fitted.screenX(layout.bounds.left, 800) >= 39.99f)
        assertTrue(fitted.screenY(layout.bounds.top, 1000) >= 39.99f)
        assertTrue(fitted.screenX(layout.bounds.right, 800) <= 760.01f)
        assertTrue(fitted.screenY(layout.bounds.bottom, 1000) <= 960.01f)
        val panned = fitted.pan(110f, -75f)
        layout.nodes.forEach { point ->
            val x = panned.screenX(point.x, 800)
            val y = panned.screenY(point.y, 1000)
            assertEquals(fitted.screenX(point.x, 800) + 110, x, 0.001f)
            assertEquals(point.x, panned.worldX(x, 800), 0.001f)
            assertEquals(point.y, panned.worldY(y, 1000), 0.001f)
            assertEquals(
                point,
                layout.pick(panned.worldX(x, 800), panned.worldY(y, 1000), 24 / panned.scale)
            )
        }
        val zoomed = panned.zoom(3f, 123f, 456f, 800, 1000, fitted.scale / 2, fitted.scale * 12)
        assertEquals(panned.worldX(123f, 800), zoomed.worldX(123f, 800), 0.001f)
        assertEquals(panned.worldY(456f, 1000), zoomed.worldY(456f, 1000), 0.001f)
        assertEquals(
            fitted.scale * 12, zoomed.zoom(
                100f, 0f, 0f, 800, 1000,
                fitted.scale / 2, fitted.scale * 12
            ).scale, 0f
        )
        assertNull(layout.pick(-10000f, -10000f, 24f))
        assertTrue(
            GraphLayout.compute(
                GraphData(
                    emptyList(),
                    emptyList(),
                    emptyList()
                )
            ).nodes.isEmpty()
        )
        assertThrows(IllegalArgumentException::class.java) {
            GraphLayout.compute(data.copy(edges = listOf("missing" to "square")))
        }
    }

    @Test
    fun jointPremiseJunctionsAreGeometryNotSelectableKnowledge() {
        val data = KnowledgeSnapshot(
            listOf(
                KnowledgeNode("a", "A", "A holds.", emptyList(), "chat"),
                KnowledgeNode("b", "B", "B holds.", emptyList(), "chat"),
                KnowledgeNode("c", "C", "C follows.", emptyList(), "chat")
            ),
            listOf(
                KnowledgeChain("direct", listOf("a"), "b"),
                KnowledgeChain("joint", listOf("a", "b"), "c")
            ),
            emptyMap()
        ).data()
        assertEquals(listOf("a", "b", "c"), data.nodes.filterNot { it.implication }.map { it.id })
        assertEquals(listOf("a" to "b", "a" to "joint", "b" to "joint", "joint" to "c"), data.edges)
        val snapshot = GraphLayout.compute(data)
        val junction = snapshot.nodes.single { it.node.implication }
        assertNull(snapshot.pick(junction.x, junction.y, 1f))
        instrumentation.runOnMainSync {
            val graph = KnowledgeGraphView(instrumentation.targetContext)
            graph.setSnapshot(snapshot)
            graph.select("a")
            assertEquals("a", graph.selectedId)
            graph.select("joint")
            assertNull(graph.selectedId)
            val restored = KnowledgeGraphView.SavedState(View.BaseSavedState.EMPTY_STATE).apply {
                hasCamera = true; zoom = 1f; selectedId = "joint"
            }
            graph.layout(0, 0, 800, 1000)
            graph.restoreHierarchyState(SparseArray<Parcelable>().apply {
                graph.id = 1234
                put(graph.id, restored)
            })
            assertNull(graph.selectedId)
        }
    }

    @Test
    fun nativePanPinchPickingAndParcelledCamera() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.showFragmentClearingBackStack(HomeFragment().apply {
                    arguments = Bundle().apply { putBoolean("sample", true) }
                })
            }
            settle()
            scenario.onActivity { activity ->
                val graph = activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                assertNotNull(graph.snapshot)
                assertTrue(graph.height >= activity.resources.getDimensionPixelSize(R.dimen.touch_target))
                val start = graph.camera
                val cx = graph.width / 2f
                val cy = graph.height / 2f
                val time = SystemClock.uptimeMillis()
                fun one(action: Int, x: Float, y: Float, offset: Long) {
                    val event = MotionEvent.obtain(time, time + offset, action, x, y, 0)
                    graph.dispatchTouchEvent(event)
                    event.recycle()
                }
                one(MotionEvent.ACTION_DOWN, cx, cy, 0)
                one(MotionEvent.ACTION_MOVE, cx + 90, cy + 60, 30)
                one(MotionEvent.ACTION_UP, cx + 90, cy + 60, 60)
                assertEquals(start.x - 90 / start.scale, graph.camera.x, 0.01f)
                assertEquals(start.y - 60 / start.scale, graph.camera.y, 0.01f)
                val before = graph.camera
                val properties = Array(2) { index ->
                    MotionEvent.PointerProperties().apply {
                        id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val dp = activity.resources.displayMetrics.density
                fun pinch(action: Int, halfSpan: Float, count: Int, offset: Long) {
                    val coords = Array(count) { index ->
                        MotionEvent.PointerCoords().apply {
                            x = cx + (if (index == 0) -halfSpan else halfSpan); y = cy
                            pressure = 1f; size = 1f
                        }
                    }
                    val event = MotionEvent.obtain(
                        time + 100, time + offset, action, count, properties, coords,
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    graph.dispatchTouchEvent(event)
                    event.recycle()
                }
                pinch(MotionEvent.ACTION_DOWN, 60 * dp, 1, 100)
                pinch(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    60 * dp,
                    2,
                    120
                )
                pinch(MotionEvent.ACTION_MOVE, 90 * dp, 2, 150)
                pinch(MotionEvent.ACTION_MOVE, 120 * dp, 2, 180)
                pinch(
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    120 * dp,
                    2,
                    200
                )
                pinch(MotionEvent.ACTION_UP, 120 * dp, 1, 220)
                assertTrue(graph.camera.scale > before.scale)
                assertEquals(
                    before.worldX(cx, graph.width),
                    graph.camera.worldX(cx, graph.width),
                    0.01f
                )
                assertEquals(
                    before.worldY(cy, graph.height),
                    graph.camera.worldY(cy, graph.height),
                    0.01f
                )
                val point = graph.snapshot!!.nodes.first()
                graph.select(point.node.id, focus = true)
                one(MotionEvent.ACTION_DOWN, cx, cy, 500)
                one(MotionEvent.ACTION_UP, cx, cy, 550)
                assertEquals(point.node.id, graph.selectedId)
                assertEquals(point.node.id, activity.findViewById<Button>(R.id.graph_details).tag)
                val camera = graph.camera
                val parcel = Parcel.obtain()
                try {
                    val saved = SparseArray<Parcelable>()
                    graph.saveHierarchyState(saved)
                    parcel.writeSparseArray(saved)
                    parcel.setDataPosition(0)
                    @Suppress("DEPRECATION") val state =
                        parcel.readSparseArray<Parcelable>(graph.javaClass.classLoader)!!
                    val restored = KnowledgeGraphView(activity).apply { id = graph.id }
                    restored.restoreHierarchyState(state)
                    restored.setSnapshot(graph.snapshot!!)
                    restored.layout(0, 0, graph.width, graph.height)
                    assertEquals(camera, restored.camera)
                    assertEquals(point.node.id, restored.selectedId)
                } finally {
                    parcel.recycle()
                }
                graph.fit()
                graph.select(null)
                assertEquals(start, graph.camera)
            }
        }
    }

    @Test
    fun scaledLabelsAndAnchoredFadingTooltip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.showFragmentClearingBackStack(HomeFragment().apply {
                    arguments = Bundle().apply { putBoolean("sample", true) }
                })
            }
            settle()
            fun watchFade(alpha: Float): CountDownLatch {
                val latch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val tooltip = activity.findViewById<Button>(R.id.graph_details)
                    tooltip.animate().setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (tooltip.alpha == alpha) latch.countDown()
                        }
                    })
                }
                return latch
            }

            val shown = watchFade(1f)
            scenario.onActivity { activity ->
                val graph = activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                graph.select("square", focus = true)
                graph.select(
                    "event",
                    focus = true
                ) // Latest selection wins; this short label also fits at 200% text.
                graph.jumpDrawablesToCurrentState()
                val bitmap = Bitmap.createBitmap(graph.width, graph.height, Bitmap.Config.ARGB_8888)
                val sizes = mutableMapOf<String, Float>()
                val colors = mutableMapOf<String, Int>()
                val canvas = object : Canvas(bitmap) {
                    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                        sizes[text] = paint.textSize
                        colors[text] = paint.color
                        super.drawText(text, x, y, paint)
                    }
                }
                try {
                    graph.draw(canvas)
                    val label = graph.snapshot!!.nodes.first { it.node.id == "event" }.node.label
                    val before = sizes.getValue(label)
                    graph.zoomBy(2f)
                    sizes.clear()
                    graph.draw(canvas)
                    assertEquals(before * 2, sizes.getValue(label), 0.01f)
                    graph.select(null)
                    graph.jumpDrawablesToCurrentState()
                    colors.clear()
                    graph.draw(canvas)
                    val visibleColor = colors.getValue(label)
                    graph.select("event")
                    colors.clear()
                    graph.draw(canvas)
                    assertTrue(
                        "Selecting a visible label must not hide it",
                        colors.containsKey(label)
                    )
                    assertEquals(255, Color.alpha(colors.getValue(label)))
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        assertEquals(
                            "Fade starts from the visible label's color",
                            visibleColor,
                            colors.getValue(label)
                        )
                    }
                    graph.jumpDrawablesToCurrentState()
                } finally {
                    bitmap.recycle()
                }
                val search = activity.findViewById<Button>(R.id.graph_search_open)
                assertEquals(
                    android.view.Gravity.CENTER_VERTICAL,
                    search.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                )
            }
            assertTrue("Tooltip must finish fading in", shown.await(5, TimeUnit.SECONDS))
            settle()
            scenario.onActivity { activity ->
                val graph = activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                val tooltip = activity.findViewById<Button>(R.id.graph_details)
                assertTrue(tooltip.isEnabled && tooltip.isShown)
                assertEquals(1f, tooltip.alpha, 0f)
                assertEquals("An event", tooltip.text.toString())
                assertTrue(tooltip.x >= 0 && tooltip.x + tooltip.width <= graph.width + 1)
                assertTrue(tooltip.y >= 0 && tooltip.y + tooltip.height < graph.height / 2f)
                activity.openHomeAction(R.id.graph_zoom_out)
            }
            settle()
            val hidden = watchFade(0f)
            scenario.onActivity { activity ->
                activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph).select(null)
            }
            assertTrue("Tooltip must finish fading out", hidden.await(5, TimeUnit.SECONDS))
            settle()
            scenario.onActivity { activity ->
                val tooltip = activity.findViewById<Button>(R.id.graph_details)
                assertFalse(tooltip.isEnabled)
                assertEquals(View.INVISIBLE, tooltip.visibility)
                assertEquals(0f, tooltip.alpha, 0f)
                tooltip.animate().setListener(null)
                activity.openHomeAction(R.id.graph_fit)
            }
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    fun searchSummaryNavigationAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.showFragmentClearingBackStack(HomeFragment().apply {
                    arguments = Bundle().apply { putBoolean("sample", true) }
                })
            }
            settle()
            scenario.onActivity { it.findViewById<Button>(R.id.graph_search_open).performClick() }
            settle()
            val imeShown = CountDownLatch(1)
            lateinit var imeListener: android.view.ViewTreeObserver.OnGlobalLayoutListener
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                imeListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    if (decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) imeShown.countDown()
                }
                decor.viewTreeObserver.addOnGlobalLayoutListener(imeListener)
                if (decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) imeShown.countDown()
                assertEquals(30, activity.findViewById<ListView>(R.id.graph_search_results).count)
                val search = activity.findViewById<EditText>(R.id.graph_search)
                assertTrue(search.hasFocus())
                assertEquals(
                    android.view.Gravity.CENTER_VERTICAL,
                    search.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                )
            }
            assertTrue("Search must open the IME", imeShown.await(5, TimeUnit.SECONDS))
            settle()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.viewTreeObserver.removeOnGlobalLayoutListener(imeListener)
                val fragment =
                    activity.fragmentManager.fragments.filterIsInstance<GraphSearchFragment>()
                        .single()
                val bottom =
                    decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.systemBars()).bottom
                assertEquals(
                    "Search follows the visible IME",
                    bottom,
                    fragment.view!!.paddingBottom
                )
                activity.findViewById<EditText>(R.id.graph_search).setText("zzznomatch")
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<ListView>(R.id.graph_search_results).count)
                assertTrue(activity.findViewById<View>(R.id.graph_search_empty).isShown)
                activity.findViewById<ImageButton>(R.id.graph_search_clear).performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(30, activity.findViewById<ListView>(R.id.graph_search_results).count)
                activity.findViewById<EditText>(R.id.graph_search).setText("positive")
            }
            settle()
            scenario.recreate()
            settle()
            var camera: GraphCamera? = null
            var selected: String? = null
            scenario.onActivity { activity ->
                assertEquals(
                    "positive",
                    activity.findViewById<EditText>(R.id.graph_search).text.toString()
                )
                val results = activity.findViewById<ListView>(R.id.graph_search_results)
                assertTrue(results.count > 0)
                assertTrue(
                    (results.getItemAtPosition(0) as GraphNode).toString()
                        .contains("positive", ignoreCase = true)
                )
            }
            var result =
                instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Positive derivative")
                    .first { it.text?.startsWith("Positive derivative.") == true }
            while (!result.isClickable && result.parent != null) result = result.parent
            assertTrue(
                "Native search result must be clickable",
                result.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            settle()
            scenario.onActivity { activity ->
                val node =
                    SampleGraph.create(activity.resources).nodes.first { it.id == "positive" }
                val graph = activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                assertEquals(node.id, graph.selectedId)
                selected = node.id
                camera = graph.camera
            }
            scenario.recreate()
            settle()
            scenario.onActivity { activity ->
                val graph = activity.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                assertEquals(selected, graph.selectedId)
                assertEquals(camera, graph.camera)
            }
            scenario.onActivity { it.findViewById<Button>(R.id.graph_details).performClick() }
            settle()
            scenario.onActivity { activity ->
                val node =
                    SampleGraph.create(activity.resources).nodes.first { it.id == "positive" }
                assertEquals(
                    node.label,
                    activity.findViewById<TextView>(R.id.graph_node_name).text.toString()
                )
                assertEquals(
                    node.statement,
                    activity.findViewById<TextView>(R.id.graph_statement).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.graph_no_sessions),
                    activity.findViewById<TextView>(R.id.graph_sources_label).text.toString()
                )
                assertEquals(
                    0,
                    activity.findViewById<android.widget.LinearLayout>(R.id.graph_sources).childCount
                )
                val menu =
                    activity.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar).menu
                assertEquals(
                    activity.getString(R.string.open_sample_lesson),
                    menu.getItem(0).title.toString()
                )
                assertTrue(menu.performIdentifierAction(menu.getItem(0).itemId, 0))
            }
            settle()
            scenario.onActivity { activity -> assertNotNull(activity.findViewById<View>(R.id.lesson_list)) }
        }
    }
}
