package com.kkgosu.learnharness

import android.database.DataSetObserver
import android.os.Bundle
import android.view.LayoutInflater
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
        views.knowledgeGraph.onSelection = { node ->
            views.graphDetails.isEnabled = node != null
            views.graphDetails.text = node?.let { getString(R.string.graph_inspect, it.label) } ?: getString(R.string.graph_select)
        }
        views.graphDetails.setOnClickListener {
            views.knowledgeGraph.selectedId?.let { id ->
                Nav.go(activity, GraphNodeFragment::class.java, Bundle().apply { putString("node", id) })
            }
        }
        views.graphZoomIn.setOnClickListener { views.knowledgeGraph.zoomBy(1.5f) }
        views.graphZoomOut.setOnClickListener { views.knowledgeGraph.zoomBy(1 / 1.5f) }
        views.graphFit.setOnClickListener { views.knowledgeGraph.fit() }
        views.openSampleLesson.setOnClickListener { Nav.go(activity, LearningSessionFragment::class.java, null) }
        views.styleReference.setOnClickListener { Nav.go(activity, StyleReferenceFragment::class.java, null) }
        return views.root
    }

    override fun onShown() {
        super.onShown()
        val views = binding ?: return
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
        binding?.graphSearch?.dismissDropDown()
        super.onHidden()
    }

    override fun onDestroyView() {
        visibleScope?.cancel()
        visibleScope = null
        binding?.graphSearch?.dismissDropDown()
        binding = null
        super.onDestroyView()
    }
}
