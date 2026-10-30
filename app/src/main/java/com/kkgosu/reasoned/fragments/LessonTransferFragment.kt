package com.kkgosu.reasoned.fragments

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.kkgosu.reasoned.MainActivity
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.model.NativeTransferPackage
import com.kkgosu.reasoned.storage.TransferPreview
import com.kkgosu.reasoned.storage.previewTransfer
import com.kkgosu.reasoned.storage.transferExportLessons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment
import java.io.ByteArrayOutputStream
import java.io.File

/** Explicit document access only. Archive paths never become filesystem destinations. */
class LessonTransferFragment : ToolbarFragment(R.layout.fragment_shell) {
    private val app get() = activity.application as ReasonedApplication
    private var page: View? = null
    private var scope: CoroutineScope? = null
    private var archive: ByteArray? = null
    private var approved: TransferPreview? = null
    private val choices = mutableMapOf<String, Boolean>()
    private var cacheName: String? = null
    private var zipExportName: String? = null
    private var dialog: AlertDialog? = null
    private var busy = false

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.settings_transfer)
        val views = inflater.inflate(R.layout.fragment_lesson_transfer, container, false)
        page = views
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cacheName = savedInstanceState?.getString("archive")?.takeIf { it.matches(Regex("transfer-[A-Za-z0-9-]+\\.zip")) }
        zipExportName = savedInstanceState?.getString("zipExport")?.takeIf { it.matches(Regex("transfer-export-[A-Za-z0-9-]+\\.zip")) }
        views.findViewById<Button>(R.id.transfer_choose).setOnClickListener {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json", "application/octet-stream", "application/x-zip-compressed"))
                }, 41)
            } catch (_: Exception) { status(R.string.transfer_failed) }
        }
        views.findViewById<Button>(R.id.transfer_install).setOnClickListener { scope?.launch { install() } }
        views.findViewById<Button>(R.id.transfer_export).setOnClickListener { selectExportLessons() }
        cacheName?.let { name ->
            scope?.launch {
                setBusy(true)
                try { preview(withContext(Dispatchers.IO) { read(Uri.fromFile(File(activity.cacheDir, name))) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { clearApproval(); status(R.string.transfer_failed) }
                finally { setBusy(false) }
            }
        }
        return views
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("archive", cacheName)
        outState.putString("zipExport", zipExportName)
        // Restoring a pending file never installs it or restores conflict choices automatically.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 43) {
            val name = zipExportName ?: return
            zipExportName = null
            val file = File(activity.cacheDir, name)
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) { file.delete(); status(R.string.transfer_export_cancelled); return }
            val resolver = activity.contentResolver
            scope?.launch {
                setBusy(true)
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = read(Uri.fromFile(file), resolver)
                        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    }
                    status(R.string.transfer_export_saved)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status(R.string.transfer_export_failed) }
                finally { file.delete(); setBusy(false) }
            }
            return
        }
        if (requestCode != 41 || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        scope?.launch {
            clearApproval(); setBusy(true); status(R.string.transfer_loading)
            cacheName?.let { File(activity.cacheDir, it).delete() }; cacheName = null
            try {
                val bytes = withContext(Dispatchers.IO) { read(uri) }
                cacheName = withContext(Dispatchers.IO) {
                    File.createTempFile("transfer-", ".zip", activity.cacheDir).also { it.writeBytes(bytes) }.name
                }
                preview(bytes, importImmediately = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { clearApproval(); status(R.string.transfer_failed) }
            finally { setBusy(false) }
        }
    }

    private fun read(uri: Uri, resolver: ContentResolver = activity.contentResolver): ByteArray = resolver.openInputStream(uri)!!.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= NativeTransferPackage.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun clearApproval() {
        approved = null; archive = null; choices.clear()
        page?.findViewById<LinearLayout>(R.id.transfer_conflicts)?.removeAllViews()
        page?.findViewById<Button>(R.id.transfer_install)?.visibility = View.GONE
        page?.findViewById<Button>(R.id.transfer_open)?.visibility = View.GONE
    }

    private suspend fun preview(bytes: ByteArray, importImmediately: Boolean = false) {
        clearApproval()
        val value = withContext(Dispatchers.IO) { app.sessions.previewTransfer(bytes) }
        archive = bytes; approved = value
        val views = page ?: return
        if (value.blockers.isNotEmpty()) {
            views.findViewById<TextView>(R.id.transfer_status).text = getString(R.string.transfer_blocked, value.blockers.joinToString("\n"))
            return
        }
        val conflicts = value.lessons.filter { it.kind == "conflict" }
        if (conflicts.isEmpty() && importImmediately) { install(); return }
        views.findViewById<TextView>(R.id.transfer_status).setText(R.string.transfer_conflict_notice)
        val rows = views.findViewById<LinearLayout>(R.id.transfer_conflicts)
        conflicts.forEach { lesson ->
            rows.addView(TextView(activity).apply { text = lesson.title })
            rows.addView(RadioGroup(activity).apply {
                for ((incoming, label) in listOf(false to R.string.transfer_keep_local, true to R.string.transfer_use_incoming)) {
                    addView(RadioButton(activity, null, 0, R.style.Widget_Harness_RadioRow).apply {
                        layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            val margin = resources.getDimensionPixelSize(R.dimen.space_8)
                            setMargins(margin, margin, margin, margin)
                        }
                        id = (activity as MainActivity).generateViewId(); setText(label)
                        setOnCheckedChangeListener { _, checked -> if (checked) { choices[lesson.id] = incoming; updateInstall() } }
                    })
                }
            })
        }
        views.findViewById<Button>(R.id.transfer_install).visibility = View.VISIBLE
        updateInstall()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        for (id in intArrayOf(R.id.transfer_choose, R.id.transfer_export)) page?.findViewById<Button>(id)?.isEnabled = !value
        updateInstall()
    }

    private fun updateInstall() {
        val value = approved
        page?.findViewById<Button>(R.id.transfer_install)?.isEnabled = !busy && value != null && value.blockers.isEmpty() &&
            value.lessons.filter { it.kind == "conflict" }.all { it.id in choices }
    }

    private suspend fun install() {
        val value = approved ?: return
        val bytes = archive ?: return
        val incoming = choices.filterValues { it }.keys.toSet()
        clearApproval(); setBusy(true); status(R.string.transfer_loading)
        try {
            val installed = app.runtime.installTransfer(bytes, value, incoming)
            page?.findViewById<TextView>(R.id.transfer_status)?.text = getString(R.string.transfer_installed, installed.size)
            val open = value.lessons.filter { it.kind != "conflict" || it.id in incoming }
            page?.findViewById<Button>(R.id.transfer_open)?.apply {
                visibility = if (open.isEmpty()) View.GONE else View.VISIBLE
                setOnClickListener {
                    fun show(index: Int) = Nav.go(activity, RuntimeSessionFragment::class.java,
                        Bundle().apply { putString("session", open[index].id) })
                    if (open.size == 1) show(0) else dialog = AlertDialog.Builder(activity)
                        .setTitle(R.string.transfer_open).setItems(open.map { it.title }.toTypedArray()) { _, index -> show(index) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            cacheName?.let { File(activity.cacheDir, it).delete() }; cacheName = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { clearApproval(); status(R.string.transfer_failed) }
        finally { setBusy(false) }
    }

    private fun selectExportLessons() {
        scope?.launch {
            setBusy(true)
            try {
                val lessons = withContext(Dispatchers.IO) { app.sessions.transferExportLessons() }
                if (lessons.isEmpty()) { status(R.string.transfer_export_empty); return@launch }
                val selected = linkedSetOf<String>()
                dialog = AlertDialog.Builder(activity).setTitle(R.string.transfer_export_select)
                    .setMultiChoiceItems(lessons.map { it.second }.toTypedArray(), null) { _, index, checked ->
                        if (checked) selected += lessons[index].first else selected -= lessons[index].first
                        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.size in 1..100
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.transfer_export) { _, _ -> prepareExport(selected.toList()) }.show()
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status(R.string.transfer_zip_failed) }
            finally { setBusy(false) }
        }
    }

    private fun prepareExport(ids: List<String>) {
        clearApproval(); status(R.string.transfer_loading)
        cacheName?.let { File(activity.cacheDir, it).delete() }; cacheName = null
        scope?.launch {
            setBusy(true)
            var file: File? = null
            try {
                val bytes = app.runtime.exportTransfer(ids)
                val cache = activity.cacheDir
                withContext(Dispatchers.IO) {
                    file = File.createTempFile("transfer-export-", ".zip", cache).also { it.writeBytes(bytes) }
                }
                zipExportName?.let { File(cache, it).delete() }
                zipExportName = file!!.name
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "learnharness-lessons.zip")
                }, 43)
            } catch (cancelled: CancellationException) { file?.delete(); throw cancelled }
            catch (_: Exception) { file?.delete(); zipExportName = null; status(R.string.transfer_zip_failed) }
            finally { setBusy(false) }
        }
    }

    private fun status(message: Int) { page?.findViewById<TextView>(R.id.transfer_status)?.setText(message) }

    override fun onDestroyView() {
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null; page = null; archive = null; approved = null
        super.onDestroyView()
    }
}
