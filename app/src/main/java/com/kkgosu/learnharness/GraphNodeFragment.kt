package com.kkgosu.learnharness

import android.os.Bundle
import android.text.TextUtils
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

/** LearnHarness's name/statement/session summary, on a native full-screen surface. */
class GraphNodeFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    private var pageView: View? = null

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.graph_proposition)
        return inflater.inflate(R.layout.fragment_graph_node, container, false)
            .also { pageView = it }
    }

    override fun onShown() {
        super.onShown()
        refreshGraph()
    }

    fun refreshGraph() {
        val views = pageView ?: return
        if (!isVisible) return
        scope?.cancel()
        val sources = views.findViewById<LinearLayout>(R.id.graph_sources)
        val name = views.findViewById<TextView>(R.id.graph_node_name)
        val statement = views.findViewById<TextView>(R.id.graph_statement)
        val sourceLabel = views.findViewById<TextView>(R.id.graph_sources_label)
        sources.removeAllViews()
        toolbar.menu.clear()
        name.setText(R.string.navigation_loading)
        statement.text = ""
        sourceLabel.text = ""
        val sample = arguments?.getBoolean("sample") == true
        val app = activity.application as ReasonedApplication
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                try {
                    val (data, knowledge) = withContext(Dispatchers.IO) {
                        val saved = if (sample) null else app.sessions.knowledge()
                        (saved?.data() ?: SampleGraph.create(resources)) to saved
                    }
                    val node = data.nodes.firstOrNull {
                        it.id == arguments?.getString("node") && !it.implication
                    }
                    if (node == null) {
                        name.setText(R.string.graph_missing)
                        return@launch
                    }
                    name.text = node.label
                    if (!sample || node.id in SampleGraph.lessonNodes) {
                        toolbar.menu.add(if (sample) R.string.open_sample_lesson else R.string.session_practice)
                            .setOnMenuItemClickListener {
                                if (sample) Nav.go(
                                    activity,
                                    LearningSessionFragment::class.java,
                                    null
                                )
                                else Nav.go(
                                    activity,
                                    NewSessionFragment::class.java,
                                    Bundle().apply {
                                        putString("target", node.id); putString(
                                        "topic",
                                        node.statement.take(2000)
                                    )
                                    })
                                true
                            }
                    }
                    lessonMarkdown(views.context).setMarkdown(statement, node.statement)
                    val sessions =
                        knowledge?.sources?.get(node.id).orEmpty().distinctBy { it.session }
                    sourceLabel.setText(if (sessions.isEmpty()) R.string.graph_no_sessions else R.string.graph_sessions)
                    sessions.forEach { source ->
                        sources.addView(
                            Button(
                                views.context,
                                null,
                                0,
                                R.style.Widget_Harness_Navigation
                            ).apply {
                                text = source.title
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                val verticalInset = resources.getDimensionPixelSize(R.dimen.space_8)
                                val horizontalInset =
                                    resources.getDimensionPixelSize(R.dimen.space_24)
                                setPaddingRelative(
                                    horizontalInset,
                                    verticalInset,
                                    horizontalInset,
                                    verticalInset
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                                setOnClickListener {
                                    (activity as MainActivity).openSession(
                                        source.session,
                                        message = source.message
                                    )
                                }
                            })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    name.setText(R.string.runtime_storage_error)
                }
            }
        }
    }

    override fun onHidden() {
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; pageView = null; super.onDestroyView()
    }
}
