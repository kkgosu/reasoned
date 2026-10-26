package com.kkgosu.learnharness

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Toolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.CustomTransitionsFragment
import me.grishka.appkit.fragments.ToolbarFragment
import me.grishka.appkit.utils.V

/**
 * Search transition adapted from Mastodon Android's SearchQueryFragment (GPL-3.0),
 * revision 24e8cdbc1f2bc9a4164d911f2a20894f39e6268b, Mastodon and contributors.
 * See REFERENCES.md and assets/licenses/mastodon.txt for provenance and terms.
 */
class GraphSearchFragment : ToolbarFragment(R.layout.fragment_shell), CustomTransitionsFragment {
    private var pageView: View? = null
    private var search: EditText? = null
    private lateinit var navigation: LayerDrawable
    private var adapter: ArrayAdapter<GraphNode>? = null
    private var scope: CoroutineScope? = null
    private val sample get() = arguments?.getBoolean("sample") == true

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawable(): Drawable? =
        if (::navigation.isInitialized) navigation else super.getNavigationIconDrawable()

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle("")
        navigation = LayerDrawable(
            arrayOf(
                activity.getDrawable(R.drawable.ic_search)!!.mutate(),
                activity.getDrawable(me.grishka.appkit.R.drawable.ic_arrow_back)!!.mutate()
                    .apply { setTint(activity.getColor(R.color.foreground)) },
            )
        ).apply { getDrawable(0).alpha = 0 }
        return inflater.inflate(R.layout.fragment_graph_search, container, false)
            .also { pageView = it }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val views = pageView ?: return
        val results = views.findViewById<ListView>(R.id.graph_search_results)
        val adapter = ArrayAdapter<GraphNode>(
            activity,
            R.layout.item_graph_search,
            R.id.graph_result_text,
            mutableListOf()
        ).also { this.adapter = it }
        results.emptyView = views.findViewById(R.id.graph_search_empty)
        results.adapter = adapter
        results.setOnItemClickListener { _, _, position, _ ->
            val node = adapter.getItem(position) ?: return@setOnItemClickListener
            results.onItemClickListener = null
            fragmentManager.fragments.filterIsInstance<HomeFragment>()
                .lastOrNull { (it.arguments?.getBoolean("sample") == true) == sample }
                ?.selectNode(node.id)
            Nav.finish(this)
        }
        val bar = layoutInflater.inflate(R.layout.view_graph_search_toolbar, toolbar, false)
        toolbar.minimumHeight = (72 * resources.displayMetrics.density).toInt()
        toolbar.layoutParams = toolbar.layoutParams.apply { height = toolbar.minimumHeight }
        toolbar.addView(
            bar,
            Toolbar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val edit = bar.findViewById<EditText>(R.id.graph_search)
        val clear = bar.findViewById<ImageButton>(R.id.graph_search_clear)
        search = edit
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) {
                adapter.filter.filter(text.toString())
                V.setVisibilityAnimated(
                    clear,
                    if (text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                )
            }

            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) =
                Unit
        })
        clear.setOnClickListener { edit.setText(""); edit.requestFocus() }
        edit.setOnEditorActionListener { _, _, _ ->
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(edit.windowToken, 0)
            true
        }
        savedInstanceState?.getString("query")
            ?.let { edit.setText(it); edit.setSelection(edit.length()) }
        edit.requestFocus()
        if (Build.VERSION.SDK_INT >= 30) {
            // AppKit owns system-bar padding; follow the native IME's actual insets on every frame.
            view.setWindowInsetsAnimationCallback(object :
                WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    val bottom =
                        insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
                    return insets
                }
            })
        }
    }

    override fun onShown() {
        super.onShown()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        if (sample) SampleGraph.create(resources) else (activity.application as ReasonedApplication).sessions.knowledge()
                            .data()
                    }
                    adapter?.apply {
                        clear(); addAll(data.nodes); filter.filter(
                        search?.text?.toString().orEmpty()
                    )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    pageView?.findViewById<android.widget.TextView>(R.id.graph_search_empty)
                        ?.setText(R.string.runtime_storage_error)
                }
            }
        }
        search?.let { edit ->
            edit.post {
                if (search === edit && isVisible) activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun onHidden() {
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onCreateEnterTransition(prev: View, container: View): Animator? =
        transition(prev, container, true)

    override fun onCreateExitTransition(prev: View, container: View): Animator? {
        search?.setText("")
        return transition(prev, container, false)
    }

    private fun transition(prev: View, container: View, enter: Boolean): Animator? {
        val source = prev.findViewById<View>(R.id.graph_search_open) ?: return null
        if (source.width == 0 || source.height == 0) return null
        val location = IntArray(2)
        source.getLocationInWindow(location)
        val x = location[0];
        val y = location[1]
        container.getLocationInWindow(location)
        val start = Rect(
            x - location[0],
            y - location[1],
            x - location[0] + source.width,
            y - location[1] + source.height
        )
        val bar = toolbar
        bar.getLocationInWindow(location)
        val dx = x - location[0]
        val dy = y - location[1] + (source.height - bar.height) / 2f
        val buttonRadius = 28 * resources.displayMetrics.density
        val bounds = Rect()
        var radius = 0f
        val outline = container.outlineProvider
        val clip = container.clipToOutline
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, result: Outline) {
                result.setRoundRect(bounds, radius)
            }
        }
        container.clipToOutline = true
        return ValueAnimator.ofFloat(if (enter) 0f else 1f, if (enter) 1f else 0f).apply {
            duration = if (enter) 700 else 300
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                val remaining = 1 - progress
                bounds.set(
                    (start.left * remaining).toInt(), (start.top * remaining).toInt(),
                    (start.right + (container.width - start.right) * progress).toInt(),
                    (start.bottom + (container.height - start.bottom) * progress).toInt()
                )
                radius = buttonRadius * remaining
                container.invalidateOutline()
                bar.translationX = dx * remaining
                bar.translationY = dy * remaining
                pageView?.alpha = progress
                prev.alpha = remaining
                navigation.getDrawable(0).alpha = (255 * remaining).toInt()
                navigation.getDrawable(1).alpha = (255 * progress).toInt()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.outlineProvider = outline; container.clipToOutline = clip
                    bar.translationX = 0f; bar.translationY = 0f
                    pageView?.alpha = 1f; prev.alpha = 1f
                }
            })
        }
    }

    override fun onDestroyView() {
        if (Build.VERSION.SDK_INT >= 30) view?.setWindowInsetsAnimationCallback(null)
        scope?.cancel(); scope = null
        adapter = null
        search = null
        pageView = null
        super.onDestroyView()
    }
}
