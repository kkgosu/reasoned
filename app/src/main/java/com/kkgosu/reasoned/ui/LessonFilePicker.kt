package com.kkgosu.reasoned.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.TextView
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.fragments.LessonFileFragment
import com.kkgosu.reasoned.model.LessonFile
import com.kkgosu.reasoned.storage.LessonDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import me.grishka.appkit.Nav

/** Shared SAF entry and animated source chips for session creation and the lesson dock. */
internal class LessonFilePicker(
    private val fragment: Fragment,
    private val root: ViewGroup,
    private val owner: String,
    private val scope: () -> CoroutineScope?,
    private val files: () -> List<LessonFile>,
    private val save: suspend (List<LessonFile>) -> Boolean,
    private val changed: () -> Unit = {}
) {
    private val documents = LessonDocuments(root.context.applicationContext)
    private val add = root.findViewById<ImageButton>(R.id.attachment_add)
    private val items = root.findViewById<LinearLayout>(R.id.attachment_items)
    private val actions = root.findViewById<ViewGroup>(R.id.attachment_actions)
    val menuOpen get() = actions.visibility == View.VISIBLE
    private val status = root.findViewById<TextView>(R.id.attachment_status)
    private var shown: List<LessonFile>? = null
    private var editable = true
    private var error = 0
    private var pendingResult: Pair<Int, List<Uri>>? = null
    var busy = false
        private set

    init {
        add.setOnClickListener {
            if (!closeMenu()) {
                fragment.activity?.currentFocus?.clearFocus()
                root.isFocusableInTouchMode = true; root.requestFocus()
                root.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
                setMenuOpen(true)
            }
        }
        for ((id, request) in listOf(R.id.attachment_files to FILES, R.id.attachment_folder to FOLDER)) {
            actions.findViewById<View>(id).setOnClickListener {
                closeMenu()
                if (!editable || busy) return@setOnClickListener
                try {
                    val intent = if (request == FOLDER) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) else
                        Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            .putExtra(Intent.EXTRA_MIME_TYPES,
                                (listOf("application/pdf", "text/*") + LessonDocuments.TEXT_EXTENSIONS.mapNotNull {
                                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                                }).distinct().toTypedArray())
                    fragment.startActivityForResult(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), request)
                } catch (_: Exception) { error = R.string.attachment_read_error; render(editable) }
            }
        }
        for (id in listOf(R.id.lesson_composer, R.id.question_thinking, R.id.question_reading_thinking, R.id.runtime_goal)) {
            root.findViewById<EditText>(id)?.setOnFocusChangeListener { _, focused -> if (focused) closeMenu() }
        }
    }

    fun closeMenu(): Boolean {
        if (!menuOpen) return false
        setMenuOpen(false)
        return true
    }

    private fun setMenuOpen(open: Boolean) {
        if (root.isLaidOut && ValueAnimator.areAnimatorsEnabled()) TransitionManager.beginDelayedTransition(root,
            AutoTransition().setDuration(root.resources.getInteger(R.integer.motion_short_ms).toLong()))
        actions.visibility = if (open) View.VISIBLE else View.GONE
        add.setImageResource(if (open) R.drawable.ic_close else R.drawable.ic_add)
        add.contentDescription = root.context.getString(if (open) R.string.attachment_close else R.string.attachment_add)
        add.tooltipText = add.contentDescription
        changed()
    }

    fun render(canEdit: Boolean) {
        editable = canEdit
        if (!canEdit || busy) closeMenu()
        val current = files()
        if (shown != current) {
            if (root.isLaidOut && ValueAnimator.areAnimatorsEnabled()) TransitionManager.beginDelayedTransition(root,
                AutoTransition().setDuration(root.resources.getInteger(R.integer.motion_short_ms).toLong()))
            shown = current.toList()
            items.removeAllViews()
            for (file in current) {
                val row = LayoutInflater.from(root.context).inflate(R.layout.item_lesson_file, items, false)
                row.findViewById<Button>(R.id.attachment_open).apply {
                    text = file.name; contentDescription = file.name; tooltipText = file.name
                    setOnClickListener { Nav.go(fragment.activity, LessonFileFragment::class.java, Bundle().apply {
                        putString("owner", owner); putString("file", file.json().toString())
                    }) }
                }
                row.findViewById<ImageButton>(R.id.attachment_remove).apply {
                    contentDescription = context.getString(R.string.attachment_remove, file.name)
                    tooltipText = contentDescription
                    setOnClickListener { scope()?.launch {
                        busy = true; changed(); render(editable)
                        try {
                            withContext(NonCancellable) {
                                if (save(files().filter { it.id != file.id })) withContext(Dispatchers.IO) { documents.remove(owner, file) }
                                else error = R.string.attachment_read_error
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { error = R.string.attachment_read_error }
                        finally { busy = false; render(editable); changed() }
                    } }
                }
                items.addView(row)
            }
        }
        add.isEnabled = canEdit && !busy
        for (i in 0 until items.childCount) {
            val row = items.getChildAt(i)
            row.findViewById<View>(R.id.attachment_remove).isEnabled = canEdit && !busy
            row.findViewById<View>(R.id.attachment_open).isEnabled = !busy
        }
        val message = when { busy -> R.string.attachment_loading; error != 0 -> error; current.isNotEmpty() -> R.string.attachment_notice; else -> 0 }
        status.visibility = if (message == 0) View.GONE else View.VISIBLE
        if (message != 0) status.setText(message)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode !in setOf(FILES, FOLDER)) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return true
        pendingResult = requestCode to uris
        onShown()
        return true
    }

    /** Activity results arrive before AppKit recreates the foreground scope in onShown. */
    fun onShown() {
        val (requestCode, uris) = pendingResult ?: return
        val visible = scope() ?: return
        pendingResult = null
        visible.launch {
            if (!editable || busy) return@launch
            busy = true; error = 0; render(editable); changed()
            var added = emptyList<LessonFile>()
            var committed = false
            try {
                added = documents.importFiles(owner, uris, requestCode == FOLDER, files())
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { committed = save(files() + added) }
                if (!committed) throw ProviderFailure("attachment_read")
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                error = when ((failure as? ProviderFailure)?.reason) {
                    "attachment_limit" -> R.string.attachment_limit
                    "attachment_empty" -> R.string.attachment_empty
                    "attachment_type" -> R.string.attachment_type
                    "attachment_locked" -> R.string.attachment_locked
                    else -> if (failure is java.nio.charset.CharacterCodingException) R.string.attachment_type else R.string.attachment_read_error
                }
            } finally {
                if (!committed) added.forEach { documents.remove(owner, it) }
                busy = false; render(editable); changed()
            }
        }
    }

    companion object { private const val FILES = 151; private const val FOLDER = 152 }
}
