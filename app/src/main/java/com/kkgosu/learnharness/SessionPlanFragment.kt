package com.kkgosu.learnharness

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.fragments.ToolbarFragment

class SessionPlanFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.learning_plan)
        val views = inflater.inflate(R.layout.fragment_session_plan, container, false)
        val app = activity.application as ReasonedApplication
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                val list = views.findViewById<LinearLayout>(R.id.plan_nodes)
                fun paragraph(value: String) {
                    list.addView(TextView(inflater.context).apply {
                        text = value; setTextAppearance(R.style.TextAppearance_Harness_Prose)
                        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_24), 0, 0)
                        setTextIsSelectable(true)
                    })
                }
                try {
                    val session =
                        withContext(Dispatchers.IO) { app.sessions.load(arguments!!.getString("session")!!) }
                    val plan = session?.plan
                    if (plan == null) paragraph(getString(R.string.plan_missing))
                    else {
                        val diagram = TextView(inflater.context).apply {
                            setTextAppearance(R.style.TextAppearance_Harness_Prose)
                            setPadding(0, resources.getDimensionPixelSize(R.dimen.space_24), 0, 0)
                        }
                        list.addView(diagram)
                        lessonMarkdown(inflater.context).setMarkdown(
                            diagram,
                            plan.diagramMarkdown()
                        )
                        val (current, visited) = plan.positions(session.protocol)
                        plan.nodes.forEach { node ->
                            val status = getString(
                                when {
                                    node.id == current -> R.string.plan_current; node.id in visited -> R.string.plan_visited; else -> R.string.plan_upcoming
                                }
                            )
                            val requires = plan.edges.filter { it.second == node.id }
                                .map { edge -> plan.nodes.first { it.id == edge.first }.title }
                            paragraph(
                                "${node.title}\n$status\n${node.summary}\n" + if (requires.isEmpty()) getString(
                                    R.string.plan_foundation
                                ) else getString(
                                    R.string.plan_requires,
                                    requires.joinToString(", ")
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    paragraph(getString(R.string.runtime_storage_error))
                }
            }
        }
        return views
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; super.onDestroyView()
    }
}
