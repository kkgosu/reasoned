package com.kkgosu.learnharness.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.kkgosu.learnharness.R
import com.kkgosu.learnharness.ReasonedApplication
import com.kkgosu.learnharness.model.fingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.fragments.ToolbarFragment
import java.text.DateFormat
import java.util.Date

/** Saved excerpts are plain selectable text, not active HTML or agent instructions. */
class ResearchSourcesFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setTitle(R.string.research_sources)
    }

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        inflater.inflate(R.layout.fragment_research_sources, container, false).also { root ->
            val list = root.findViewById<LinearLayout>(R.id.research_sources)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope?.launch {
                val reports = try {
                    withContext(Dispatchers.IO) {
                        (activity.application as ReasonedApplication).sessions.load(
                            arguments?.getString(
                                "session"
                            ).orEmpty()
                        )?.research.orEmpty()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    list.addView(TextView(activity).apply { setText(R.string.runtime_storage_error) }); return@launch
                }

                fun text(value: String, style: Int) {
                    list.addView(TextView(activity).apply {
                        setTextAppearance(style); text = value; setTextIsSelectable(true)
                        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_16), 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                if (reports.isEmpty()) text(
                    getString(R.string.research_no_reports),
                    R.style.TextAppearance_Harness_Prose
                )
                reports.forEach { report ->
                    text(report.question, R.style.TextAppearance_Harness_Title)
                    text(report.brief, R.style.TextAppearance_Harness_Prose)
                    report.sources.forEach { source ->
                        text(source.title, R.style.TextAppearance_Harness_Title)
                        text(
                            getString(
                                R.string.research_source_meta,
                                source.url,
                                DateFormat.getDateTimeInstance().format(Date(source.retrieved)),
                                fingerprint(source.text)
                            ), R.style.TextAppearance_Harness_Meta
                        )
                        text(source.text, R.style.TextAppearance_Harness_Prose)
                    }
                    if (report.limitations.isNotEmpty()) text(
                        "Limitations: " + report.limitations.joinToString(
                            ", "
                        ), R.style.TextAppearance_Harness_Meta
                    )
                }
            }
        }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; super.onDestroyView()
    }
}
