package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.GraphData
import com.kkgosu.reasoned.model.GraphNode
import com.kkgosu.reasoned.ui.graph.GraphCamera
import com.kkgosu.reasoned.ui.graph.GraphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {
    @Test
    fun layoutCameraInversePickingAndZoomFocus() {
        val data = GraphData(
            (0 until 30).map { GraphNode("node-$it", "Node $it", "", "", it / 5) },
            (0 until 29).map { "node-$it" to "node-${it + 1}" },
            (0 until 6).map { "Cluster $it" }
        )
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
            GraphLayout.compute(data.copy(edges = listOf("missing" to "node-0")))
        }
    }
}
