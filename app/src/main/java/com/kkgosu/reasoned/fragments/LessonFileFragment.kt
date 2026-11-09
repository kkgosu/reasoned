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
import android.widget.ScrollView
import android.widget.TextView
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.model.LessonFile
import com.kkgosu.reasoned.storage.LessonDocuments
import com.kkgosu.reasoned.ui.views.DiagramView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.grishka.appkit.fragments.ToolbarFragment
import org.json.JSONObject

/** Local preview only; uses the same bounded page renderer as model source reads. */
class LessonFileFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var scope: CoroutineScope? = null
    private var root: View? = null
    private var image: DiagramView? = null
    private lateinit var source: LessonFile
    private var page = 0
    private var count = 1
    private var restored: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = LessonFile.parse(JSONObject(arguments!!.getString("file")!!))
        page = savedInstanceState?.getInt("page") ?: 0
        count = if (source.pages > 0) source.pages else 1
        restored = savedInstanceState
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (source.pages > 0) menu.add(0, R.id.diagram_fit, 0, R.string.graph_fit)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == R.id.diagram_fit) {
        image?.fit(); true
    } else super.onOptionsItemSelected(item)

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(source.name)
        val views = inflater.inflate(R.layout.fragment_lesson_file, container, false)
        root = views
        if (source.pages > 0) {
            views.findViewById<View>(R.id.attachment_text_scroll).visibility = View.GONE
            image = DiagramView(inflater.context).also { views.findViewById<ViewGroup>(R.id.attachment_preview).addView(it) }
        }
        views.findViewById<Button>(R.id.attachment_previous).setOnClickListener { page--; restored = null; load() }
        views.findViewById<Button>(R.id.attachment_next).setOnClickListener { page++; restored = null; load() }
        return views
    }

    override fun onShown() {
        super.onShown()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        load()
    }

    private fun load() {
        val views = root ?: return
        val previous = views.findViewById<Button>(R.id.attachment_previous)
        val next = views.findViewById<Button>(R.id.attachment_next)
        val status = views.findViewById<TextView>(R.id.attachment_status)
        previous.isEnabled = false; next.isEnabled = false
        status.visibility = View.VISIBLE; status.setText(R.string.attachment_loading)
        scope?.launch {
            try {
                val documents = LessonDocuments(views.context.applicationContext)
                val owner = arguments!!.getString("owner")!!
                if (source.pages > 0) {
                    val bitmap = documents.render(owner, source, page + 1)
                    image!!.apply {
                        val old = this.bitmap
                        this.bitmap = bitmap; old?.recycle(); fit()
                        restored?.let { zoom = it.getFloat("zoom", 1f); panX = it.getFloat("panX"); panY = it.getFloat("panY") }
                        contentDescription = context.getString(R.string.attachment_page, page + 1, count)
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            alpha = 0f; animate().alpha(1f).setDuration(resources.getInteger(R.integer.motion_short_ms).toLong()).start()
                        }
                    }
                } else {
                    val text = documents.read(owner, listOf(source), JSONObject().put("file", source.id)
                        .put("page", 0).put("offset", page * 12_000).put("image", false))
                    count = ((text.getInt("length") + 11_999) / 12_000).coerceAtLeast(1)
                    views.findViewById<TextView>(R.id.attachment_text).text = text.getString("text")
                    views.findViewById<ScrollView>(R.id.attachment_text_scroll).scrollTo(0, 0)
                }
                restored = null
                views.findViewById<TextView>(R.id.attachment_page).text = views.context.getString(
                    if (source.pages > 0) R.string.attachment_page else R.string.attachment_part, page + 1, count)
                status.visibility = View.GONE
                previous.isEnabled = page > 0; next.isEnabled = page + 1 < count
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                image?.let { val old = it.bitmap; it.bitmap = null; old?.recycle() }
                views.findViewById<TextView>(R.id.attachment_text).text = ""
                status.setText(R.string.attachment_read_error)
                previous.isEnabled = page > 0
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
        image?.let { outState.putFloat("zoom", it.zoom); outState.putFloat("panX", it.panX); outState.putFloat("panY", it.panY) }
    }

    override fun onHidden() {
        scope?.cancel(); scope = null
        super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null
        image?.let { it.animate().cancel(); val old = it.bitmap; it.bitmap = null; old?.recycle() }
        image = null; root = null
        super.onDestroyView()
    }
}
