package com.kkgosu.reasoned.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ui.diagrams.DiagramRenderer
import com.kkgosu.reasoned.ui.text.DiagramPlugin
import com.kkgosu.reasoned.ui.views.DiagramView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.grishka.appkit.fragments.ToolbarFragment

/** Fullscreen static preview; zooming/opening saved diagrams never calls a model. */
class DiagramFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    private var figure: DiagramView? = null
    private var sourceDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, R.id.diagram_fit, 0, R.string.graph_fit)
        menu.add(0, R.id.diagram_source, 1, R.string.diagram_source)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.diagram_fit -> {
            figure?.fit(); true
        }

        R.id.diagram_source -> {
            val text = TextView(activity).apply {
                setTextAppearance(R.style.TextAppearance_Harness_Prose)
                text = arguments?.getString("source").orEmpty()
                setTextIsSelectable(true)
                val inset = resources.getDimensionPixelSize(R.dimen.space_24)
                setPadding(inset, inset, inset, inset)
            }
            sourceDialog = AlertDialog.Builder(activity).setTitle(R.string.diagram_source)
                .setView(android.widget.ScrollView(activity).apply { addView(text) })
                .setPositiveButton(android.R.string.ok, null).show()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(arguments?.getString("label") ?: getString(R.string.diagram_svg))
        val views = inflater.inflate(R.layout.fragment_diagram, container, false)
        val image = DiagramView(inflater.context).apply {
            contentDescription = arguments?.getString("label") ?: getString(R.string.diagram_svg)
        }
        figure = image
        views.findViewById<ViewGroup>(R.id.diagram_image).addView(image)
        val status = views.findViewById<TextView>(R.id.diagram_status)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                try {
                    val kind = arguments?.getString("kind").orEmpty()
                    val source = arguments?.getString("source").orEmpty()
                    val key = "$kind\n$source"
                    val bitmap = DiagramPlugin.cache.get(key) ?: DiagramRenderer(inflater.context)
                        .render(kind, source, targetPixels = 2048)
                        .also { DiagramPlugin.cache.put(key, it) }
                    image.bitmap = bitmap
                    image.zoom = savedInstanceState?.getFloat("zoom", 1f) ?: 1f
                    image.panX = savedInstanceState?.getFloat("panX", 0f) ?: 0f
                    image.panY = savedInstanceState?.getFloat("panY", 0f) ?: 0f
                    status.visibility = View.GONE
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.setText(R.string.diagram_failed)
                }
            }
        }
        return views
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        figure?.let {
            outState.putFloat("zoom", it.zoom); outState.putFloat(
            "panX",
            it.panX
        ); outState.putFloat("panY", it.panY)
        }
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; sourceDialog?.dismiss(); sourceDialog = null; figure = null
        super.onDestroyView()
    }
}
