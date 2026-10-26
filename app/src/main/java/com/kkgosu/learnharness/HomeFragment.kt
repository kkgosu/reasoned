package com.kkgosu.learnharness

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.OneShotPreDrawListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class HomeFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var visibleScope: CoroutineScope? = null
    private var pendingSelection: String? = null

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawableResource() = R.drawable.ic_menu
    override fun onToolbarNavigationClick() = (activity as MainActivity).openDrawer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSelection = savedInstanceState?.getString("selection")
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) { inflater.inflate(R.menu.home, menu) }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val graph = pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph) ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.graph_fit -> graph.fit()
            R.id.graph_zoom_in -> graph.zoomBy(1.5f)
            R.id.graph_zoom_out -> graph.zoomBy(1 / 1.5f)
            R.id.open_sample_lesson -> Nav.go(activity, LearningSessionFragment::class.java, null)
            R.id.style_reference -> Nav.go(activity, StyleReferenceFragment::class.java, null)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.app_name)
        val views = inflater.inflate(R.layout.fragment_home, container, false)
        pageView = views
        views.findViewById<Button>(R.id.graph_search_open).setOnClickListener { Nav.go(activity, GraphSearchFragment::class.java, null) }
        views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph).onSelection = ::renderSelection
        val details = views.findViewById<Button>(R.id.graph_details)
        details.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTooltip() }
        details.setOnClickListener {
            views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph).selectedId?.let { id ->
                Nav.go(activity, GraphNodeFragment::class.java, Bundle().apply { putString("node", id) })
            }
        }
        return views
    }

    fun selectNode(id: String) {
        pendingSelection = id
        val graph = pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph) ?: return
        // Restored, hidden graphs have not applied their saved camera/selection until layout.
        if (graph.snapshot != null) OneShotPreDrawListener.add(graph) {
            if (pendingSelection == id) {
                graph.select(id, focus = true)
                pendingSelection = null
            }
        }
    }

    private fun renderSelection(node: GraphNode?) {
        val views = pageView ?: return
        val tooltip = views.findViewById<Button>(R.id.graph_details)
        if (tooltip.tag != node?.id) {
            tooltip.tag = node?.id
            tooltip.isEnabled = false
            tooltip.animate().cancel()
            val duration = if (ValueAnimator.areAnimatorsEnabled()) resources.getInteger(R.integer.motion_short_ms).toLong() else 0L
            tooltip.animate().alpha(0f).setDuration(if (tooltip.alpha > 0f) duration else 0L).withEndAction {
                if (pageView !== views) return@withEndAction
                if (node == null) tooltip.visibility = View.INVISIBLE
                else {
                    tooltip.text = node.label
                    tooltip.contentDescription = getString(R.string.graph_inspect, node.label)
                    tooltip.visibility = View.VISIBLE
                    tooltip.isEnabled = true
                    positionTooltip()
                    tooltip.animate().alpha(1f).setDuration(duration).start()
                }
            }.start()
        }
        positionTooltip()
    }

    private fun positionTooltip() {
        val views = pageView ?: return
        val tooltip = views.findViewById<Button>(R.id.graph_details)
        if (!tooltip.isEnabled) return
        val graph = views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
        val point = graph.snapshot?.nodes?.firstOrNull { it.node.id == graph.selectedId } ?: return
        val x = graph.camera.screenX(point.x, graph.width)
        val y = graph.camera.screenY(point.y, graph.height)
        if (x !in 0f..graph.width.toFloat() || y !in 0f..graph.height.toFloat()) {
            tooltip.visibility = View.INVISIBLE
            return
        }
        tooltip.visibility = View.VISIBLE
        tooltip.translationX = (graph.left + x - tooltip.width / 2f)
            .coerceIn(0f, (graph.width - tooltip.width).coerceAtLeast(0).toFloat()) - tooltip.left
        tooltip.translationY = (graph.top + y - tooltip.height - resources.getDimension(R.dimen.space_12))
            .coerceIn(0f, (graph.height - tooltip.height).coerceAtLeast(0).toFloat()) - tooltip.top
    }

    override fun onShown() {
        super.onShown()
        toolbar.navigationContentDescription = getString(R.string.navigation_open)
        val views = pageView ?: return
        val graph = views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
        renderSelection(graph.snapshot?.nodes?.firstOrNull { it.node.id == graph.selectedId }?.node)
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            if (graph.snapshot == null) scope.launch {
                val data = SampleGraph.create(views.resources)
                val snapshot = withContext(Dispatchers.Default) { GraphLayout.compute(data) }
                graph.setSnapshot(snapshot)
                pendingSelection?.let(::selectNode)
                views.findViewById<View>(R.id.graph_loading).visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", pendingSelection)
        super.onSaveInstanceState(outState)
    }

    override fun onHidden() {
        visibleScope?.cancel(); visibleScope = null
        pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)?.jumpDrawablesToCurrentState()
        pageView?.findViewById<Button>(R.id.graph_details)?.apply {
            animate().cancel(); tag = null; alpha = 0f; isEnabled = false; visibility = View.INVISIBLE
        }
        super.onHidden()
    }

    override fun onDestroyView() {
        visibleScope?.cancel(); visibleScope = null
        pageView?.findViewById<Button>(R.id.graph_details)?.animate()?.cancel()
        pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)?.onSelection = null
        pageView = null
        super.onDestroyView()
    }
}
