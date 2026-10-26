package com.kkgosu.learnharness

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

/** Persistent propositions/arguments link to exact saved messages; samples stay explicitly separate. */
class GraphNodeFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.graph_proposition)
        val views = inflater.inflate(R.layout.fragment_graph_node, container, false)
        val sample = arguments?.getBoolean("sample") == true
        val app = activity.application as ReasonedApplication
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                val statement = views.findViewById<TextView>(R.id.graph_statement)
                try {
                    val (data, knowledge) = withContext(Dispatchers.IO) {
                        val saved = if (sample) null else app.sessions.knowledge()
                        (saved?.data() ?: SampleGraph.create(resources)) to saved
                    }
                    val node = data.nodes.firstOrNull { it.id == arguments?.getString("node") }
                    if (node == null) {
                        statement.setText(R.string.graph_missing); return@launch
                    }
                    val renderer = lessonMarkdown(inflater.context)
                    renderer.setMarkdown(statement, node.statement)
                    renderer.setMarkdown(
                        views.findViewById(R.id.graph_reasoning),
                        node.reasoning.ifEmpty { getString(R.string.knowledge_no_derivation) })
                    views.findViewById<TextView>(R.id.graph_domain).text =
                        data.clusters[node.cluster]
                    views.findViewById<TextView>(R.id.graph_provenance_text)
                        .setText(if (sample) R.string.graph_fixture_source else R.string.knowledge_source_notice)
                    fun button(list: LinearLayout, label: String, click: () -> Unit) {
                        list.addView(
                            Button(
                                inflater.context,
                                null,
                                0,
                                R.style.Widget_Harness_Button_Secondary
                            ).apply {
                                text = label
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                                }
                                setOnClickListener { click() }
                            })
                    }

                    val sources = views.findViewById<LinearLayout>(R.id.graph_sources)
                    knowledge?.sources?.get(node.id).orEmpty().forEach { source ->
                        button(
                            sources,
                            getString(
                                R.string.knowledge_source,
                                source.title.take(160),
                                source.message,
                                source.text.take(240)
                            )
                        ) {
                            (activity as MainActivity).openSession(
                                source.session,
                                message = source.message
                            )
                        }
                    }
                    val related = views.findViewById<LinearLayout>(R.id.graph_related)
                    data.edges.filter { it.first == node.id || it.second == node.id }
                        .forEach { (from, to) ->
                            val other =
                                data.nodes.first { it.id == if (from == node.id) to else from }
                            button(
                                related,
                                getString(
                                    if (to == node.id) R.string.graph_premise else R.string.graph_conclusion,
                                    other.label
                                )
                            ) {
                                Nav.go(
                                    activity,
                                    GraphNodeFragment::class.java,
                                    Bundle().apply {
                                        putString(
                                            "node",
                                            other.id
                                        ); putBoolean("sample", sample)
                                    })
                            }
                        }
                    views.findViewById<Button>(R.id.graph_open_lesson).apply {
                        val canPractice =
                            !sample && knowledge?.nodes?.any { it.id == node.id } == true
                        visibility =
                            if (canPractice || sample && node.id in SampleGraph.lessonNodes) View.VISIBLE else View.GONE
                        if (canPractice) setText(R.string.session_practice)
                        setOnClickListener {
                            if (canPractice) Nav.go(
                                activity,
                                NewSessionFragment::class.java,
                                Bundle().apply {
                                    putString("target", node.id); putString(
                                    "topic",
                                    node.statement.take(2000)
                                )
                                }) else Nav.go(activity, LearningSessionFragment::class.java, null)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    statement.setText(R.string.runtime_storage_error)
                }
            }
        }
        return views
    }

    override fun onHidden() {
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; super.onDestroyView()
    }
}
