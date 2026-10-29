package com.kkgosu.reasoned.fragments

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import com.kkgosu.reasoned.BuildConfig
import com.kkgosu.reasoned.MainActivity
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.controllers.extractionStatus
import com.kkgosu.reasoned.model.GraphNode
import com.kkgosu.reasoned.model.SampleGraph
import com.kkgosu.reasoned.storage.knowledge
import com.kkgosu.reasoned.ui.graph.GraphLayout
import com.kkgosu.reasoned.ui.views.KnowledgeGraphView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class HomeFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var visibleScope: CoroutineScope? = null
    private var pendingSelection: String? = null
    private val sample get() = BuildConfig.DEBUG && arguments?.getBoolean("sample") == true
    private val app get() = activity.application as ReasonedApplication

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawableResource() = R.drawable.ic_menu
    override fun onToolbarNavigationClick() = (activity as MainActivity).openDrawer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSelection = savedInstanceState?.getString("selection")
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.home, menu)
        if (BuildConfig.DEBUG) {
            inflater.inflate(R.menu.home_debug, menu)
            menu.findItem(R.id.graph_sample)
                ?.setTitle(if (sample) R.string.knowledge_real else R.string.knowledge_sample)
        }
        menu.findItem(R.id.graph_stop_update)?.isVisible = app.extraction.state.value.running
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val graph = pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
            ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.graph_fit -> graph.fit()
            R.id.graph_zoom_in -> graph.zoomBy(1.5f)
            R.id.graph_zoom_out -> graph.zoomBy(1 / 1.5f)
            R.id.open_sample_lesson -> if (BuildConfig.DEBUG) Nav.go(
                activity,
                RuntimeSessionFragment::class.java,
                Bundle().apply { putBoolean("sample", true) }
            )

            R.id.graph_sample -> if (BuildConfig.DEBUG) Nav.go(
                activity,
                HomeFragment::class.java,
                Bundle().apply { putBoolean("sample", !sample) })

            R.id.graph_stop_update -> app.extraction.stop()
            R.id.style_reference -> if (BuildConfig.DEBUG) Nav.go(
                activity,
                StyleReferenceFragment::class.java,
                null
            )

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.app_name)
        val views = inflater.inflate(R.layout.fragment_home, container, false)
        pageView = views
        views.findViewById<Button>(R.id.graph_search_open).setOnClickListener {
            Nav.go(
                activity,
                GraphSearchFragment::class.java,
                Bundle().apply { putBoolean("sample", sample) })
        }
        views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph).onSelection = ::renderSelection
        val details = views.findViewById<Button>(R.id.graph_details)
        details.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTooltip() }
        details.setOnClickListener {
            views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph).selectedId?.let { id ->
                Nav.go(
                    activity,
                    GraphNodeFragment::class.java,
                    Bundle().apply { putString("node", id); putBoolean("sample", sample) })
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
            val duration =
                if (ValueAnimator.areAnimatorsEnabled()) resources.getInteger(R.integer.motion_short_ms)
                    .toLong() else 0L
            tooltip.animate().alpha(0f).setDuration(if (tooltip.alpha > 0f) duration else 0L)
                .withEndAction {
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
        tooltip.translationY =
            (graph.top + y - tooltip.height - resources.getDimension(R.dimen.space_12))
                .coerceIn(
                    0f,
                    (graph.height - tooltip.height).coerceAtLeast(0).toFloat()
                ) - tooltip.top
    }

    override fun onShown() {
        super.onShown()
        toolbar.navigationContentDescription = getString(R.string.navigation_open)
        val views = pageView ?: return
        val graph = views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
        renderSelection(graph.snapshot?.nodes?.firstOrNull { it.node.id == graph.selectedId }?.node)
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            loadGraph(views, scope)
            scope.launch {
                app.extraction.state.collectIndexed { index, status ->
                    views.findViewById<TextView>(R.id.knowledge_status).apply {
                        text = activity.extractionStatus(status)
                        visibility =
                            if (status.phase == "idle" || sample) View.GONE else View.VISIBLE
                    }
                    invalidateOptionsMenu()
                    if (index > 0 && !sample && !status.running) loadGraph(views, scope)
                }
            }
        }
    }

    fun refreshGraph() {
        val views = pageView ?: return
        visibleScope?.let { loadGraph(views, it) }
    }

    private fun loadGraph(views: View, scope: CoroutineScope) {
        scope.launch {
            val loading = views.findViewById<TextView>(R.id.graph_loading)
            try {
                val data = withContext(Dispatchers.IO) {
                    if (BuildConfig.DEBUG && sample) SampleGraph.create(views.resources) else app.sessions.knowledge()
                        .data()
                }
                val snapshot = withContext(Dispatchers.Default) { GraphLayout.compute(data) }
                val graph = views.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
                val emptyBefore = graph.snapshot?.nodes?.isEmpty() == true
                graph.setSnapshot(snapshot)
                if (emptyBefore) graph.fit()
                pendingSelection?.let(::selectNode)
                loading.setText(R.string.knowledge_empty)
                loading.visibility = if (data.nodes.isEmpty()) View.VISIBLE else View.GONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loading.setText(R.string.runtime_storage_error); loading.visibility = View.VISIBLE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", pendingSelection)
        super.onSaveInstanceState(outState)
    }

    override fun onHidden() {
        visibleScope?.cancel(); visibleScope = null
        pageView?.findViewById<KnowledgeGraphView>(R.id.knowledge_graph)
            ?.jumpDrawablesToCurrentState()
        pageView?.findViewById<Button>(R.id.graph_details)?.apply {
            animate().cancel(); tag = null; alpha = 0f; isEnabled = false; visibility =
            View.INVISIBLE
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
