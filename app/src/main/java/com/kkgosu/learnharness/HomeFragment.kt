package com.kkgosu.learnharness

import android.animation.ValueAnimator
import android.database.DataSetObserver
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import com.kkgosu.learnharness.databinding.FragmentHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class HomeFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var binding: FragmentHomeBinding? = null
    private var visibleScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.home, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val views = binding ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.graph_fit -> views.knowledgeGraph.fit()
            R.id.graph_zoom_in -> views.knowledgeGraph.zoomBy(1.5f)
            R.id.graph_zoom_out -> views.knowledgeGraph.zoomBy(1 / 1.5f)
            R.id.open_sample_lesson -> Nav.go(activity, LearningSessionFragment::class.java, null)
            R.id.style_reference -> Nav.go(activity, StyleReferenceFragment::class.java, null)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.app_name)
        val views = FragmentHomeBinding.inflate(inflater, container, false)
        binding = views
        val data = SampleGraph.create(inflater.context.resources)
        val adapter = ArrayAdapter(inflater.context, R.layout.item_graph_search, R.id.graph_result_text, data.nodes)
        views.graphSearch.setAdapter(adapter)
        adapter.registerDataSetObserver(object : DataSetObserver() {
            override fun onInvalidated() = onChanged()
            override fun onChanged() {
                val ids = if (views.graphSearch.text.isBlank()) emptySet() else
                    (0 until adapter.count).mapNotNull { adapter.getItem(it)?.id }.toSet()
                views.knowledgeGraph.highlight(ids)
                views.graphSearch.error = if (views.graphSearch.text.isNotBlank() && adapter.count == 0)
                    getString(R.string.graph_no_matches) else null
            }
        })
        views.graphBrowse.setOnClickListener {
            views.graphSearch.setText("", false)
            views.graphSearch.requestFocus()
            // Native filtering otherwise dismisses an empty-query popup after showDropDown().
            adapter.filter.filter(null) {
                views.graphSearch.post { if (views.graphSearch.isShown) views.graphSearch.showDropDown() }
            }
        }
        views.graphSearch.setOnItemClickListener { _, _, position, _ ->
            val node = adapter.getItem(position) ?: return@setOnItemClickListener
            views.graphSearch.setText("", false)
            views.graphSearch.dismissDropDown()
            views.root.context.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(views.root.windowToken, 0)
            views.root.requestFocus()
            views.knowledgeGraph.highlight(emptySet())
            views.knowledgeGraph.select(node.id, focus = true)
        }
        views.knowledgeGraph.onSelection = ::renderSelection
        views.graphDetails.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTooltip() }
        views.graphDetails.setOnClickListener {
            views.knowledgeGraph.selectedId?.let { id ->
                Nav.go(activity, GraphNodeFragment::class.java, Bundle().apply { putString("node", id) })
            }
        }
        return views.root
    }

    private fun renderSelection(node: GraphNode?) {
        val views = binding ?: return
        val tooltip = views.graphDetails
        if (tooltip.tag != node?.id) {
            tooltip.tag = node?.id
            tooltip.isEnabled = false
            tooltip.animate().cancel()
            val duration = if (ValueAnimator.areAnimatorsEnabled())
                resources.getInteger(R.integer.motion_short_ms).toLong() else 0L
            tooltip.animate().alpha(0f).setDuration(if (tooltip.alpha > 0f) duration else 0L).withEndAction {
                if (binding !== views) return@withEndAction
                if (node == null) {
                    tooltip.visibility = View.INVISIBLE
                } else {
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
        val views = binding ?: return
        val tooltip = views.graphDetails
        if (!tooltip.isEnabled) return
        val graph = views.knowledgeGraph
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
        val views = binding ?: return
        renderSelection(views.knowledgeGraph.snapshot?.nodes?.firstOrNull { it.node.id == views.knowledgeGraph.selectedId }?.node)
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            if (views.knowledgeGraph.snapshot == null) scope.launch {
                val data = SampleGraph.create(views.root.resources)
                val snapshot = withContext(Dispatchers.Default) { GraphLayout.compute(data) }
                views.knowledgeGraph.setSnapshot(snapshot)
                views.graphLoading.visibility = View.GONE
            }
        }
    }

    override fun onHidden() {
        visibleScope?.cancel()
        visibleScope = null
        binding?.apply {
            graphSearch.dismissDropDown()
            knowledgeGraph.jumpDrawablesToCurrentState()
            graphDetails.animate().cancel()
            graphDetails.tag = null
            graphDetails.alpha = 0f
            graphDetails.isEnabled = false
            graphDetails.visibility = View.INVISIBLE
        }
        super.onHidden()
    }

    override fun onDestroyView() {
        visibleScope?.cancel()
        visibleScope = null
        binding?.graphSearch?.dismissDropDown()
        binding?.graphDetails?.animate()?.cancel()
        binding?.knowledgeGraph?.onSelection = null
        binding = null
        super.onDestroyView()
    }
}
