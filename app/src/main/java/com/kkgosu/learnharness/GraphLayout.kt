package com.kkgosu.learnharness

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Renderer projection shared by authored samples and source-backed knowledge; never a mastery score. */
data class GraphNode(val id: String, val label: String, val statement: String, val reasoning: String, val cluster: Int) {
    override fun toString() = "$label. $statement"
}

data class GraphData(val nodes: List<GraphNode>, val edges: List<Pair<String, String>>, val clusters: List<String>)
data class GraphPoint(val node: GraphNode, val x: Float, val y: Float)
data class GraphRegion(val label: String, val x: Float, val y: Float, val radius: Float)
data class GraphBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class GraphSnapshot(
    val nodes: List<GraphPoint>,
    val edges: List<Pair<GraphPoint, GraphPoint>>,
    val regions: List<GraphRegion>,
    val bounds: GraphBounds,
) {
    fun pick(x: Float, y: Float, radius: Float): GraphPoint? = nodes
        .minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
        ?.takeIf { hypot(it.x - x, it.y - y) <= radius }
}

object GraphLayout {
    // ponytail: bounded deterministic birth-session placement; use a graph-layout engine if larger graphs need it.
    fun compute(data: GraphData): GraphSnapshot {
        require(data.nodes.map { it.id }.distinct().size == data.nodes.size)
        require(data.nodes.all { it.cluster in data.clusters.indices })
        val extra = if (data.nodes.size > 30) ((sqrt(data.nodes.groupBy { it.cluster }.values.maxOfOrNull { it.size }?.toFloat() ?: 0f) * 50 + 60) - 230).coerceAtLeast(0f) else 0f
        val regions = data.clusters.mapIndexed { index, label ->
            GraphRegion(label, (index % 2) * (510f + extra * 2), (index / 2) * (440f + extra * 2), 230f + extra)
        }
        val points = data.nodes.groupBy { it.cluster }.flatMap { (cluster, members) ->
            val region = regions[cluster]
            members.mapIndexed { index, node ->
                val angle = if (members.size > 12) index * Math.PI * (3 - sqrt(5.0)) else index * (Math.PI * 2 / members.size) - Math.PI / 2 + cluster * 0.23
                val radius = if (members.size > 12) sqrt(index + 1f) * 50 else if (index % 2 == 0) 155f else 105f
                GraphPoint(node, region.x + cos(angle).toFloat() * radius, region.y + sin(angle).toFloat() * radius)
            }
        }
        val byId = points.associateBy { it.node.id }
        val edges = data.edges.map { (from, to) ->
            require(from != to && from in byId && to in byId) { "Invalid graph edge: $from → $to" }
            byId.getValue(from) to byId.getValue(to)
        }
        val bounds = if (regions.isEmpty()) GraphBounds(-1f, -1f, 1f, 1f) else GraphBounds(
            regions.minOf { it.x - it.radius }, regions.minOf { it.y - it.radius },
            regions.maxOf { it.x + it.radius }, regions.maxOf { it.y + it.radius },
        )
        return GraphSnapshot(points, edges, regions, bounds)
    }
}

/** Screen = viewport center + (world - camera center) * scale. No View or layout ownership. */
data class GraphCamera(val x: Float, val y: Float, val scale: Float) {
    fun worldX(screen: Float, width: Int) = x + (screen - width / 2f) / scale
    fun worldY(screen: Float, height: Int) = y + (screen - height / 2f) / scale
    fun screenX(world: Float, width: Int) = width / 2f + (world - x) * scale
    fun screenY(world: Float, height: Int) = height / 2f + (world - y) * scale
    fun pan(dx: Float, dy: Float) = copy(x = x - dx / scale, y = y - dy / scale)
    fun zoom(factor: Float, focusX: Float, focusY: Float, width: Int, height: Int, minScale: Float, maxScale: Float): GraphCamera {
        require(factor.isFinite() && factor > 0)
        val next = (scale * factor).coerceIn(minScale, maxScale)
        return GraphCamera(worldX(focusX, width) - (focusX - width / 2f) / next,
            worldY(focusY, height) - (focusY - height / 2f) / next, next)
    }

    companion object {
        fun fit(bounds: GraphBounds, width: Int, height: Int, padding: Float): GraphCamera {
            val scale = min((width - 2 * padding).coerceAtLeast(1f) / (bounds.right - bounds.left),
                (height - 2 * padding).coerceAtLeast(1f) / (bounds.bottom - bounds.top))
            return GraphCamera((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2, scale)
        }
    }
}
