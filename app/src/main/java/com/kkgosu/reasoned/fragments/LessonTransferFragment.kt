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
import android.widget.EditText
import android.util.AtomicFile
import android.os.Build
import com.google.zxing.integration.android.IntentIntegrator
import com.kkgosu.reasoned.api.LanConnection
import com.kkgosu.reasoned.api.LanPairing
import com.kkgosu.reasoned.api.lanDigest
import com.kkgosu.reasoned.api.lanJson
import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.storage.TransferLesson
import com.kkgosu.reasoned.storage.TransferReceiptKey
import com.kkgosu.reasoned.storage.transferReceipt
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.util.UUID
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

/** Explicit file or paired-LAN access only. Archive paths never become filesystem destinations. */
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
    private var lanWork: Job? = null
    private var lanConnection: LanConnection? = null
    private var remoteTarget: LanConnection? = null
    private var lanIncoming: TransferReceiptKey? = null
    private var lanPending = false
    private val lan get() = app.lessonLan
    private val lanOperation get() = CredentialStore(activity, "lesson-lan-operation", 65536)
    private val lanFile get() = AtomicFile(File(activity.noBackupFilesDir, "lesson-lan-pending.zip"))

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
        views.findViewById<Button>(R.id.transfer_install).setOnClickListener {
            if (remoteTarget != null || lanIncoming != null) lanAction { install() } else scope?.launch { install() }
        }
        views.findViewById<Button>(R.id.transfer_export).setOnClickListener { selectExportLessons() }
        initLan(views)
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
        if (requestCode == 44) {
            if (resultCode == Activity.RESULT_OK) data?.getStringExtra("SCAN_RESULT")?.let { pairLan(it) }
            return
        }
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
        approved = null; archive = null; choices.clear(); remoteTarget = null; lanIncoming = null
        page?.findViewById<Button>(R.id.transfer_install)?.apply {
            setText(R.string.transfer_install)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_import, 0, 0, 0)
        }
        page?.findViewById<LinearLayout>(R.id.transfer_conflicts)?.removeAllViews()
        page?.findViewById<Button>(R.id.transfer_install)?.visibility = View.GONE
        page?.findViewById<Button>(R.id.transfer_open)?.visibility = View.GONE
    }

    private suspend fun preview(bytes: ByteArray, importImmediately: Boolean = false, receipt: TransferReceiptKey? = null) {
        val value = withContext(Dispatchers.IO) { app.sessions.previewTransfer(bytes) }
        showPreview(bytes, value, importImmediately, receipt = receipt)
    }

    private suspend fun showPreview(bytes: ByteArray, value: TransferPreview, importImmediately: Boolean, target: LanConnection? = null, receipt: TransferReceiptKey? = null) {
        clearApproval()
        archive = bytes; approved = value; remoteTarget = target; lanIncoming = receipt
        page?.findViewById<Button>(R.id.transfer_install)?.apply {
            setText(if (target == null) R.string.transfer_install else R.string.lan_send)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (target == null) R.drawable.ic_import else R.drawable.ic_export, 0, 0, 0)
        }
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
                        id = (activity as MainActivity).generateViewId()
                        setText(if (target != null) { if (incoming) R.string.lan_use_phone else R.string.lan_keep_computer }
                            else if (receipt != null) { if (incoming) R.string.lan_use_computer else R.string.lan_keep_phone } else label)
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
        for (id in intArrayOf(R.id.transfer_choose, R.id.transfer_export, R.id.lan_scan, R.id.lan_enter, R.id.lan_forget, R.id.lan_retry, R.id.lan_discard)) page?.findViewById<View>(id)?.isEnabled = !value
        for (id in intArrayOf(R.id.lan_send, R.id.lan_receive)) page?.findViewById<Button>(id)?.isEnabled = !value && lanConnection != null && !lanPending
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
        val target = remoteTarget
        if (target != null) { commitLanSend(target, bytes, value); return }
        val receipt = lanIncoming
        clearApproval(); setBusy(true); status(R.string.transfer_loading)
        try {
            val installed = app.runtime.installTransfer(bytes, value, incoming, receipt)
            if (receipt != null) finishLanOperation()
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

    private fun selectExportLessons(sendToComputer: Boolean = false) {
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
                    .setPositiveButton(if (sendToComputer) R.string.lan_send else R.string.transfer_export) { _, _ ->
                        if (sendToComputer) prepareLanSend(selected.toList()) else prepareExport(selected.toList())
                    }.show()
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

    private fun initLan(views: View) {
        views.findViewById<Button>(R.id.lan_scan).setOnClickListener {
            try {
                val intent = IntentIntegrator.forFragment(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setBeepEnabled(false).setPrompt(getString(R.string.lan_scan)).createScanIntent()
                startActivityForResult(intent, 44)
            } catch (_: Exception) { status(R.string.lan_failed) }
        }
        views.findViewById<Button>(R.id.lan_enter).setOnClickListener {
            val content = LayoutInflater.from(activity).inflate(R.layout.dialog_session_name, null)
            val input = content.findViewById<EditText>(R.id.session_name).apply {
                hint = "learnharness-lan://pair?…"; isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                // Pairing validates its own length; don't inherit the shared name field's truncation.
                filters = emptyArray()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            dialog = AlertDialog.Builder(activity).setTitle(R.string.lan_enter).setView(content)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.lan_connect) { _, _ -> pairLan(input.text.toString().trim()) }.show()
        }
        views.findViewById<Button>(R.id.lan_send).setOnClickListener { selectExportLessons(sendToComputer = true) }
        views.findViewById<Button>(R.id.lan_receive).setOnClickListener { receiveLan() }
        views.findViewById<Button>(R.id.lan_retry).setOnClickListener { retryLan() }
        views.findViewById<Button>(R.id.lan_discard).setOnClickListener {
            dialog = AlertDialog.Builder(activity).setMessage(R.string.lan_discard_notice).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lan_discard) { _, _ -> lanAction { clearApproval(); finishLanOperation() } }.show()
        }
        views.findViewById<View>(R.id.lan_forget).setOnClickListener { lanAction {
            withContext(Dispatchers.IO) { lan.forget() }; lanConnection = null; refreshLan()
        } }
        scope?.launch { lan.online.collect { renderLanDevice(it) } }
        lanAction { refreshLan() }
    }

    private fun lanAction(block: suspend () -> Unit) {
        if (busy) return
        lanWork = scope?.launch {
            setBusy(true)
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status(R.string.lan_failed) }
            finally { setBusy(false) }
        }
    }

    private suspend fun refreshLan() {
        val state = withContext(Dispatchers.IO) { lan.connection() to lanOperation.hasSavedKey() }
        lanConnection = state.first; lanPending = state.second
        renderLanDevice(lan.online.value)
        page?.findViewById<View>(R.id.lan_pending_status)?.visibility = if (lanPending) View.VISIBLE else View.GONE
        page?.findViewById<View>(R.id.lan_forget)?.visibility = if (lanConnection == null) View.GONE else View.VISIBLE
        for (id in intArrayOf(R.id.lan_retry, R.id.lan_discard)) page?.findViewById<Button>(id)?.visibility = if (lanPending) View.VISIBLE else View.GONE
    }

    private fun renderLanDevice(online: Boolean) {
        val label = page?.findViewById<TextView>(R.id.lan_status) ?: return
        val peer = lanConnection
        label.text = peer?.name?.substringBefore(" · ") ?: getString(R.string.lan_disconnected)
        label.contentDescription = if (peer == null) label.text else getString(if (online) R.string.lan_online else R.string.lan_offline, label.text)
        val dot = peer?.let {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(resources.getDimensionPixelSize(R.dimen.space_8), resources.getDimensionPixelSize(R.dimen.space_8))
                setColor(resources.getColor(if (online) R.color.answer_correct else R.color.answer_incorrect, activity.theme))
            }
        }
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        label.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.space_8)
    }

    private fun pairLan(raw: String) = lanAction {
        val pairing = LanPairing(raw)
        withContext(Dispatchers.IO) { lan.pair(pairing, Build.MODEL.take(100)) }
        refreshLan()
    }

    private fun prepareLanSend(ids: List<String>) = lanAction {
        clearApproval(); status(R.string.lan_working)
        val target = lanConnection ?: error("Connect first")
        val bytes = app.runtime.exportTransfer(ids)
        val remote = withContext(Dispatchers.IO) { lan.preview(target, bytes) }
        val lessons = remote.getJSONArray("chats")
        val blockers = remote.getJSONArray("blockers")
        val value = TransferPreview(lanDigest(bytes), remote.getString("revision"), (0 until lessons.length()).map { index ->
            val lesson = lessons.getJSONObject(index); TransferLesson(lesson.getString("id"), lesson.getString("title"), lesson.getString("kind"))
        }, remote.getInt("nodes"), remote.getInt("chains"), emptyList(), (0 until blockers.length()).map { blockers.getString(it) })
        showPreview(bytes, value, importImmediately = true, target = target)
    }

    private suspend fun saveLanOperation(bytes: ByteArray, operation: JSONObject) = withContext(Dispatchers.IO) {
        check(!lanOperation.hasSavedKey()) { "Resolve the pending exchange first" }
        val file = lanFile
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
        lanOperation.save(operation.put("sha", lanDigest(bytes)).toString())
    }

    private suspend fun finishLanOperation() {
        withContext(Dispatchers.IO) { lanOperation.forget(); lanFile.delete() }
        lanPending = false
        refreshLan()
    }

    private suspend fun commitLanSend(target: LanConnection, bytes: ByteArray, value: TransferPreview) {
        setBusy(true); status(R.string.lan_working)
        try {
            val decisions = JSONObject().apply { value.lessons.filter { it.kind == "conflict" }.forEach { put(it.id, if (choices[it.id] == true) "incoming" else "local") } }
            val operation = UUID.randomUUID().toString()
            saveLanOperation(bytes, JSONObject().put("kind", "send").put("operation", operation).put("connection", target.json()).put("revision", value.revision).put("choices", decisions))
            clearApproval(); lanPending = true
            val result = withContext(Dispatchers.IO) { lan.install(target, bytes, operation, value.revision, decisions) }
            finishLanOperation()
            page?.findViewById<TextView>(R.id.transfer_status)?.text = getString(R.string.lan_sent, result.getJSONArray("installed").length())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refreshLan(); status(R.string.lan_failed) }
        finally { setBusy(false) }
    }

    private fun receiveLan() = lanAction {
        val target = lanConnection ?: error("Connect first")
        val remote = withContext(Dispatchers.IO) { lan.lessons(target) }
        check(remote.getString("id") == target.id)
        val rows = remote.getJSONArray("lessons")
        if (rows.length() == 0) { status(R.string.lan_empty); return@lanAction }
        val selected = linkedSetOf<String>()
        dialog = AlertDialog.Builder(activity).setTitle(R.string.transfer_export_select)
            .setMultiChoiceItems((0 until rows.length()).map { rows.getJSONObject(it).getString("title") }.toTypedArray(), null) { _, index, checked ->
                val id = rows.getJSONObject(index).getString("id")
                if (checked) selected += id else selected -= id
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.size in 1..100
            }.setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.lan_receive) { _, _ ->
                lanAction {
                    clearApproval(); status(R.string.lan_working)
                    val bytes = withContext(Dispatchers.IO) { lan.export(target, selected.toList()) }
                    val operation = UUID.randomUUID().toString()
                    saveLanOperation(bytes, JSONObject().put("kind", "receive").put("operation", operation).put("peer", target.fingerprint))
                    lanPending = true
                    preview(bytes, importImmediately = true, receipt = TransferReceiptKey(operation, target.fingerprint))
                    refreshLan()
                }
            }.show()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
    }

    private fun retryLan() = lanAction {
        clearApproval(); status(R.string.lan_working)
        val pending = withContext(Dispatchers.IO) { lanJson(lanOperation.read()!!.toByteArray()) }
        val operation = pending.getString("operation")
        if (pending.getString("kind") == "send") {
            val target = LanConnection.parse(pending.getJSONObject("connection"))
            var receipt = withContext(Dispatchers.IO) { lan.receipt(target, operation) }
            if (receipt == null) {
                val bytes = readLanPending(pending)
                receipt = withContext(Dispatchers.IO) { lan.install(target, bytes, operation, pending.getString("revision"), pending.getJSONObject("choices")) }
            }
            finishLanOperation()
            page?.findViewById<TextView>(R.id.transfer_status)?.text = getString(R.string.lan_sent, receipt.getJSONArray("installed").length())
        } else {
            check(pending.getString("kind") == "receive")
            val key = TransferReceiptKey(operation, pending.getString("peer"))
            val receipt = withContext(Dispatchers.IO) { app.sessions.transferReceipt(key) }
            if (receipt != null) {
                check(receipt.first == pending.getString("sha")); finishLanOperation()
                page?.findViewById<TextView>(R.id.transfer_status)?.text = getString(R.string.transfer_installed, receipt.second.size)
            } else preview(readLanPending(pending), importImmediately = true, receipt = key)
        }
    }

    private suspend fun readLanPending(pending: JSONObject): ByteArray = withContext(Dispatchers.IO) {
        val file = lanFile
        require(file.baseFile.length() in 1..NativeTransferPackage.MAX_BYTES.toLong())
        file.readFully().also { require(it.size <= NativeTransferPackage.MAX_BYTES && lanDigest(it) == pending.getString("sha")) }
    }

    override fun onResume() {
        super.onResume()
        lanAction { refreshLan() }
    }

    override fun onPause() {
        lanWork?.cancel(); lanWork = null
        super.onPause()
    }

    private fun status(message: Int) { page?.findViewById<TextView>(R.id.transfer_status)?.setText(message) }

    override fun onDestroyView() {
        dialog?.dismiss(); dialog = null
        lanWork?.cancel(); lanWork = null
        scope?.cancel(); scope = null; page = null; archive = null; approved = null
        super.onDestroyView()
    }
}
