package com.kkgosu.learnharness

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CoreProps
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolverDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.grishka.appkit.Nav
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import java.util.IdentityHashMap

/** Keep the native document renderer; only mermaid/svg fences become static image spans. */
internal class DiagramPlugin(private val context: Context) : AbstractMarkwonPlugin() {
    private val renderer = DiagramRenderer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = IdentityHashMap<AsyncDrawable, Job>()

    companion object {
        // Shared by inline/fullscreen views; fits a 2048² bitmap without another render or a Bundle-sized image.
        val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) =
                value.allocationByteCount + key.length * 2 + 128
        }
    }

    private val loader = object : AsyncDrawableLoader() {
        override fun load(drawable: AsyncDrawable) {
            if (jobs.containsKey(drawable)) return
            val key = drawable.destination
            fun display(bitmap: Bitmap) {
                drawable.setResult(BitmapDrawable(context.resources, bitmap).apply {
                    setBounds(
                        0,
                        0,
                        intrinsicWidth,
                        intrinsicHeight
                    )
                })
            }
            cache.get(key)?.let { display(it); return }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val bitmap = renderer.render(
                        key.substringBefore('\n'),
                        key.substringAfter('\n'),
                        targetPixels = 2048
                    )
                    cache.put(key, bitmap)
                    if (drawable.isAttached) display(bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Leave the accessible diagram/source action usable; never log untrusted diagram text.
                    drawable.clearResult(); drawable.invalidateSelf()
                } finally {
                    if (jobs[drawable] === coroutineContext[Job]) jobs.remove(drawable)
                }
            }
            jobs[drawable] = job; job.start()
        }

        override fun cancel(drawable: AsyncDrawable) {
            jobs.remove(drawable)?.cancel()
        }

        override fun placeholder(drawable: AsyncDrawable): Drawable? = null
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(FencedCodeBlock::class.java) { visitor, node ->
            val kind = node.info.trim().substringBefore(' ').lowercase()
            visitor.blockStart(node)
            val start = visitor.length()
            if (kind in setOf("mermaid", "svg")) {
                val source = node.literal
                val description =
                    if (kind == "svg") Regex("<title\\b[^>]*>([\\s\\S]*?)</title\\s*>").find(source)?.groupValues?.get(
                        1
                    )
                    else Regex("(?m)^\\s*accTitle\\s*:\\s*(.+)$").find(source)?.groupValues?.get(1)
                val label = node.info.trim().substringAfter(' ', "").ifBlank {
                    android.text.Html.fromHtml(
                        description.orEmpty(),
                        android.text.Html.FROM_HTML_MODE_LEGACY
                    ).toString().trim()
                }.take(160)
                    .ifBlank { context.getString(if (kind == "mermaid") R.string.diagram_mermaid else R.string.diagram_svg) }
                val drawable = AsyncDrawable("$kind\n$source", loader, ImageSizeResolverDef(), null)
                visitor.builder().append(label)
                visitor.setSpans(
                    start,
                    AsyncDrawableSpan(
                        visitor.configuration().theme(),
                        drawable,
                        AsyncDrawableSpan.ALIGN_BOTTOM,
                        false
                    )
                )
                visitor.setSpans(start, object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        // A visible preview can outlive its cache entry; retain the tapped bitmap for the inspector.
                        (drawable.result as? BitmapDrawable)?.bitmap?.let {
                            cache.put(
                                drawable.destination,
                                it
                            )
                        }
                        val activity =
                            generateSequence(widget.context) { (it as? ContextWrapper)?.baseContext }
                                .filterIsInstance<Activity>().firstOrNull() ?: return
                        Nav.go(activity, DiagramFragment::class.java, Bundle().apply {
                            putString("kind", kind); putString("source", source); putString(
                            "label",
                            label
                        )
                        })
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = context.getColor(R.color.muted_foreground); ds.isUnderlineText =
                            false
                    }
                })
            } else {
                appendCode(visitor, node, node.literal, node.info)
            }
            visitor.blockEnd(node)
        }
        builder.on(IndentedCodeBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            appendCode(visitor, node, node.literal, null)
            visitor.blockEnd(node)
        }
    }

    private fun appendCode(visitor: MarkwonVisitor, node: Node, source: String, language: String?) {
        val start = visitor.length()
        // Preserve native code spans/highlighting, not padding made from blank text lines.
        visitor.builder().append(
            visitor.configuration().syntaxHighlight().highlight(
                language, source.removeSuffix("\n").ifEmpty { "\u00a0" })
        )
        CoreProps.CODE_BLOCK_INFO.set(visitor.renderProps(), language)
        visitor.setSpansForNodeOptional(node, start)
    }

    private fun diagramLinkAt(view: TextView, event: MotionEvent): ClickableSpan? {
        val text = view.text as? Spanned ?: return null
        val layout = view.layout ?: return null
        val x = event.x - view.totalPaddingLeft + view.scrollX
        val y = event.y - view.totalPaddingTop + view.scrollY
        if (y < 0 || y >= layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        if (text.getSpans(offset, offset, AsyncDrawableSpan::class.java).none {
                it.drawable.destination.startsWith("svg\n") || it.drawable.destination.startsWith("mermaid\n")
            }) return null
        return text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) =
        AsyncDrawableScheduler.unschedule(textView)

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Confirmed taps call performClick via GestureDetector; spans retain native accessibility.
    override fun afterSetText(textView: TextView) {
        val spans = (textView.text as? Spanned)?.getSpans(
            0,
            textView.length(),
            AsyncDrawableSpan::class.java
        ).orEmpty()
        if (spans.none {
                it.drawable.destination.startsWith("svg\n") || it.drawable.destination.startsWith(
                    "mermaid\n"
                )
            }) return
        textView.movementMethod = LinkMovementMethod.getInstance()
        AsyncDrawableScheduler.schedule(textView)
        if (textView.getTag(R.id.diagram_attach_listener) == null) {
            textView.setTag(R.id.diagram_attach_listener, true)
            // Handle only image taps before TextView's selection/focus path can scroll the chat.
            // Ordinary prose keeps native selection; ClickableSpan still supplies keyboard/TalkBack activation.
            var diagramTouch = false
            val gestures = GestureDetector(
                textView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent) = true
                    override fun onSingleTapUp(event: MotionEvent): Boolean {
                        val link = diagramLinkAt(textView, event) ?: return false
                        textView.performClick()
                        link.onClick(textView)
                        return true
                    }
                })
            textView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) diagramTouch =
                    diagramLinkAt(textView, event) != null
                val handled = diagramTouch
                if (handled) gestures.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) diagramTouch =
                    false
                handled
            }
            textView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) =
                    AsyncDrawableScheduler.schedule(textView)

                override fun onViewDetachedFromWindow(v: View) =
                    AsyncDrawableScheduler.unschedule(textView)
            })
        }
    }
}
