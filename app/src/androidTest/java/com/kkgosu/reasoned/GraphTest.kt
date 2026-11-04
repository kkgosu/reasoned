package com.kkgosu.reasoned

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import me.grishka.appkit.views.UsableRecyclerView
import com.kkgosu.reasoned.ui.adapters.TextListAdapter
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.fragments.HomeFragment
import com.kkgosu.reasoned.model.KnowledgeChain
import com.kkgosu.reasoned.model.KnowledgeNode
import com.kkgosu.reasoned.model.KnowledgeSnapshot
import com.kkgosu.reasoned.model.SampleGraph
import com.kkgosu.reasoned.ui.graph.GraphCamera
import com.kkgosu.reasoned.ui.graph.GraphLayout
import com.kkgosu.reasoned.ui.views.KnowledgeGraphView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GraphTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun settle() = instrumentation.uiAutomation.waitForIdle(500, 5000)

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
    }

    @Test
    fun graphCanBePannedZoomedSelectedAndReset() {
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
                graph.fit()
                graph.select(null)
                assertEquals(start, graph.camera)
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
                assertEquals(30, activity.findViewById<UsableRecyclerView>(R.id.graph_search_results).count)
                val search = activity.findViewById<EditText>(R.id.graph_search)
                assertTrue(search.hasFocus())
            }
            assertTrue("Search must open the IME", imeShown.await(5, TimeUnit.SECONDS))
            settle()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.viewTreeObserver.removeOnGlobalLayoutListener(imeListener)
                activity.findViewById<EditText>(R.id.graph_search).setText("zzznomatch")
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<UsableRecyclerView>(R.id.graph_search_results).count)
                assertTrue(activity.findViewById<View>(R.id.graph_search_empty).isShown)
                activity.findViewById<ImageButton>(R.id.graph_search_clear).performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(30, activity.findViewById<UsableRecyclerView>(R.id.graph_search_results).count)
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
                val results = activity.findViewById<UsableRecyclerView>(R.id.graph_search_results)
                assertTrue(results.count > 0)
                assertTrue(
                    (results.adapter as TextListAdapter<*>).items[0].toString()
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
