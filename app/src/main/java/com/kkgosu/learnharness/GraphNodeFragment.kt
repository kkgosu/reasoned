package com.kkgosu.learnharness

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

/** A readable native surface; no claims of inferred mastery, audited identity or real evidence. */
class GraphNodeFragment : ToolbarFragment(R.layout.fragment_shell) {
    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.graph_proposition)
        return inflater.inflate(R.layout.fragment_graph_node, container, false).apply {
            val data = SampleGraph.create(resources)
            val node = data.nodes.firstOrNull { it.id == arguments?.getString("node") }
            val statement = findViewById<TextView>(R.id.graph_statement)
            if (node == null) {
                statement.setText(R.string.graph_missing)
                return@apply
            }
            statement.text = node.statement
            findViewById<TextView>(R.id.graph_reasoning).text = node.reasoning
            findViewById<TextView>(R.id.graph_domain).text = data.clusters[node.cluster]
            val links = findViewById<LinearLayout>(R.id.graph_related)
            // Incoming/outgoing links are authored reasoning relationships, not a session plan.
            data.edges.filter { it.first == node.id || it.second == node.id }.forEach { (from, to) ->
                val other = data.nodes.first { it.id == if (from == node.id) to else from }
                val direction = if (to == node.id) R.string.graph_premise else R.string.graph_conclusion
                links.addView(Button(context, null, 0, R.style.Widget_Harness_Button_Secondary).apply {
                    text = getString(direction, other.label)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                    }
                    setOnClickListener {
                        Nav.go(activity, GraphNodeFragment::class.java, Bundle().apply { putString("node", other.id) })
                    }
                })
            }
            findViewById<Button>(R.id.graph_open_lesson).apply {
                visibility = if (node.id in SampleGraph.lessonNodes) View.VISIBLE else View.GONE
                setOnClickListener { Nav.go(activity, LearningSessionFragment::class.java, null) }
            }
        }
    }
}
