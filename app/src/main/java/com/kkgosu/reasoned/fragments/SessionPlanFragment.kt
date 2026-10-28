package com.kkgosu.reasoned.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.kkgosu.reasoned.BuildConfig
import com.kkgosu.reasoned.MainActivity
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.model.StudyPlan
import com.kkgosu.reasoned.ui.text.lessonMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.fragments.ToolbarFragment

/** Progress styling is presentation-only; the saved transcript's plan diagram stays monochrome. */
internal fun StudyPlan.progressDiagramMarkdown(context: Context, protocol: String): String = buildString {
    val (current, visited) = positions(protocol)
    fun covered(id: String) = id == current || id in visited
    fun color(resource: Int) = "#%06x".format(context.getColor(resource) and 0xffffff)
    val ink = color(R.color.foreground)
    val border = color(R.color.border)
    append(diagramMarkdown().removeSuffix("```"))
    append("classDef visited fill:${color(R.color.background)},fill-opacity:0.5,stroke:$ink,color:$ink\n")
    // Mermaid approximation of LearnHarness's blurred yellow halo; no custom SVG filters.
    append("classDef current fill:${color(R.color.plan_current)},fill-opacity:0.25,stroke:$ink,color:$ink,font-weight:bold\n")
    append("classDef upcoming fill:${color(R.color.surface)},stroke:$border,color:${color(R.color.muted_foreground)}\n")
    nodes.forEachIndexed { index, node ->
        val status = when {
            node.id == current -> "current"
            node.id in visited -> "visited"
            else -> "upcoming"
        }
        append("class n$index $status\n")
    }
    edges.forEachIndexed { index, (from, to) ->
        append("linkStyle $index stroke:${if (covered(from) && covered(to)) ink else border}\n")
    }
    append("```")
}

class SessionPlanFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    private val sample get() = BuildConfig.DEBUG && arguments?.getBoolean("sample") == true

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(if (sample) R.string.sample_plan_title else R.string.learning_plan)
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
                    val updates = if (BuildConfig.DEBUG && sample) {
                        val lesson = (activity as MainActivity).mockLesson
                        lesson.state.map { lesson.plan to lesson.planProtocol(it) }
                    } else {
                        val sessionId = arguments!!.getString("session")!!
                        val saved = withContext(Dispatchers.IO) { app.sessions.load(sessionId) }
                        app.runtime.state.map { active ->
                            val session = active?.takeIf { it.id == sessionId } ?: saved
                            session?.plan to session?.protocol.orEmpty()
                        }
                    }
                    updates.distinctUntilChangedBy { (plan, protocol) -> plan to plan?.positions(protocol) }
                        .collect { (plan, protocol) ->
                            list.removeAllViews()
                            if (plan == null) paragraph(getString(R.string.plan_missing))
                            else {
                                val diagram = TextView(inflater.context).apply {
                                    setTextAppearance(R.style.TextAppearance_Harness_Prose)
                                }
                                list.addView(diagram)
                                lessonMarkdown(inflater.context).setMarkdown(
                                    diagram,
                                    plan.progressDiagramMarkdown(inflater.context, protocol)
                                )
                                val (current, visited) = plan.positions(protocol)
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
