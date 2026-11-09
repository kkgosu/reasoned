package com.kkgosu.reasoned

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.widget.EditText
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import com.kkgosu.reasoned.fragments.LessonFileFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.LessonDocuments
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.utils.objects
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Authored local sources and fake providers only; no credentials or external network. */
@RunWith(AndroidJUnit4::class)
class LessonAttachmentTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun args(id: String, page: Int, image: Boolean = false) = JSONObject().put("file", id)
        .put("page", page).put("offset", 0).put("image", image)

    private fun authoredPdf(file: File) {
        PDFBoxResourceLoader.init(app)
        PDDocument().use { document ->
            for (page in 1..2) {
                val sheet = PDPage(); document.addPage(sheet)
                PDPageContentStream(document, sheet).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(50f, 700f); content.showText("AUTHORED_PAGE_$page"); content.endText()
                }
            }
            document.save(file)
        }
    }

    @Test fun attachPreviewRestoreAndRemoveDoesNotStartLearning() = runBlocking {
        val fixture = RuntimeSession(goal = "[Authored attachment journey]", model = "fixture")
        val original = File(app.cacheDir, "updates/authored-${fixture.id}.pdf").apply { parentFile!!.mkdirs() }
        authoredPdf(original)
        app.sessions.save(fixture)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.showFragmentClearingBackStack(RuntimeSessionFragment().apply {
                    arguments = android.os.Bundle().apply { putString("session", fixture.id) }
                }) }
                withTimeout(5000) { app.runtime.state.first { it?.id == fixture.id } }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.lesson_composer).setText("Read my selected source")
                    activity.findViewById<View>(R.id.attachment_add).performClick()
                    assertTrue(activity.findViewById<View>(R.id.attachment_files).isShown)
                }
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<View>(R.id.attachment_files).isShown)
                    assertEquals("Read my selected source", activity.findViewById<EditText>(R.id.lesson_composer).text.toString())
                }
                val monitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                        assertEquals("*/*", intent.type)
                        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                        val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toSet()
                        assertTrue(types.containsAll(setOf("application/pdf", "text/*", "application/json")))
                        assertFalse(types.contains("*/*"))
                        assertFalse(types.contains("image/*"))
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(R.id.attachment_add).performClick()
                        activity.findViewById<View>(R.id.attachment_files).performClick()
                    }
                    assertEquals("Files must launch the native document picker", 1, monitor.hits)
                } finally { instrumentation.removeMonitor(monitor) }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { activity ->
                    val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                    // SAF delivers its result before onShown recreates the foreground scope.
                    fragment.onActivityResult(151, Activity.RESULT_OK, Intent().setData(
                        FileProvider.getUriForFile(app, "${app.packageName}.updates", original)))
                    assertTrue(app.sessions.load(fixture.id)!!.files.isEmpty())
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                withTimeout(5000) { app.runtime.state.first { it?.id == fixture.id && it.files.size == 1 } }
                assertEquals(1, app.sessions.load(fixture.id)!!.files.size)
                assertEquals("idle", app.runtime.state.value!!.status)
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { it.findViewById<View>(R.id.attachment_open).performClick() }
                suspend fun awaitPage(expected: Int) {
                    withTimeout(5000) {
                        var ready = false
                        while (!ready) {
                            scenario.onActivity { activity ->
                                val viewer = activity.fragmentManager.fragments.filterIsInstance<LessonFileFragment>().lastOrNull()
                                val label = viewer?.view?.findViewById<TextView>(R.id.attachment_page)
                                ready = label?.text == activity.getString(R.string.attachment_page, expected, 2)
                            }
                            if (!ready) delay(20)
                        }
                    }
                }
                awaitPage(1)
                scenario.onActivity { it.findViewById<View>(R.id.attachment_next).performClick() }
                awaitPage(2)
                scenario.recreate(); awaitPage(2)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { it.findViewById<View>(R.id.attachment_remove).performClick() }
                withTimeout(5000) { app.runtime.state.first { it?.id == fixture.id && it.files.isEmpty() } }
                assertEquals("idle", app.runtime.state.value!!.status)
                assertTrue(app.sessions.load(fixture.id)!!.files.isEmpty())
                assertTrue(original.isFile)
            }
        } finally {
            app.runtime.delete(fixture.id)
            original.delete()
        }
    }

    @Test fun chosenFolderImportsNestedTextButNotUnselectedOrUnsupportedFiles() = runBlocking {
        val owner = UUID.randomUUID().toString()
        val documents = LessonDocuments(app)
        try {
            val uri = android.provider.DocumentsContract.buildTreeDocumentUri("com.kkgosu.reasoned.debug.test.attachments", "root")
            val files = documents.importFiles(owner, listOf(uri), true, emptyList())
            assertEquals(setOf("note.txt", "notes/nested.md"), files.map { it.name }.toSet())
            for (file in files) assertTrue(documents.read(owner, files, args(file.id, 0)).getString("text").contains("Authored offline folder source"))
        } finally { documents.delete(owner) }
    }

    @Test fun chosenSourcesPersistAndReadThroughTheNativeRuntimeWithoutAutomaticRequests() = runBlocking {
        withTimeout(25_000) {
            val owner = UUID.randomUUID().toString()
            val documents = LessonDocuments(app)
            val directory = File(app.cacheDir, "updates").apply { mkdirs() }
            val pdf = File(directory, "authored-$owner.pdf")
            val text = File(directory, "authored-$owner.txt")
            val empty = File(directory, "empty-$owner.txt")
            val database = "attachment-$owner.db"
            authoredPdf(pdf)
            text.writeText("[Authored offline source] ".repeat(800))
            empty.writeBytes(byteArrayOf())
            fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            try {
                val files = documents.importFiles(owner, listOf(uri(pdf), uri(text)), false, emptyList())
                assertEquals(2, files.size); assertEquals(2, files[0].pages); assertEquals(0, files[1].pages)
                val first = documents.read(owner, files, args(files[1].id, 0))
                assertEquals(12_000, first.getString("text").length)
                assertEquals(12_000, first.getInt("nextOffset"))
                val secondPage = documents.read(owner, files, args(files[0].id, 2))
                assertTrue(secondPage.getString("text").contains("AUTHORED_PAGE_2"))
                assertFalse(secondPage.getString("text").contains("AUTHORED_PAGE_1"))
                assertEquals(2, secondPage.getInt("page"))
                assertEquals(files[0].name, secondPage.getString("source"))
                documents.render(owner, files[0], 2).also { bitmap ->
                    assertTrue(bitmap.width > 0 && bitmap.height <= 2048); bitmap.recycle()
                }
                try {
                    documents.importFiles(owner, listOf(uri(text), uri(empty)), false, files)
                    fail("Empty source accepted")
                } catch (failure: ProviderFailure) { assertEquals("attachment_empty", failure.reason) }
                assertTrue(documents.read(owner, files, args(files[1].id, 0)).getString("text").isNotBlank())
                SessionStore(app, database).use { store ->
                    val chat = NativeChat.parse(JSONObject().put("id", owner).put("title", "Authored source lesson")
                        .put("version", 1).put("createdAt", "2026-01-01T00:00:00Z").put("updatedAt", "2026-01-01T00:00:00Z")
                        .put("messages", JSONArray().put(JSONObject().put("id", "goal").put("role", "user")
                            .put("timestamp", 1).put("content", "Authored PDF test"))).toString())
                    var requests = 0
                    val fake = ModelProvider { request, _ ->
                        requests++
                        assertTrue(request.getString("instructions").contains(files[0].id))
                        val output = JSONArray()
                        when (requests) {
                            1, 2 -> output.put(JSONObject().put("type", "function_call").put("name", "read_attachment")
                                .put("call_id", "source_$requests").put("arguments", args(files[0].id, 2, requests == 2).toString()))
                            3 -> {
                                val input = request.getJSONArray("input").toString()
                                assertTrue(input.contains("AUTHORED_PAGE_2")); assertTrue(input.contains("input_image"))
                                output.put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                                    .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                                        .put("text", "[Authored test] Source: ${files[0].name}, page 2."))))
                            }
                            else -> error("Unexpected model request")
                        }
                        JSONObject().put("status", "completed").put("output", output)
                            .put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 10))
                    }
                    val creation = SessionRuntime(app, store, fake)
                    creation.create("Authored PDF test", "fixture", files = files, id = owner).join()
                    assertEquals("", creation.failure.value)
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
                    assertFalse(File(app.cacheDir, "lesson-files/$hash").exists())
                    assertTrue(File(app.filesDir, "lesson-files/$hash").isDirectory)
                    store.saveNative(RuntimeSession(id = owner, goal = "Authored PDF test", model = "fixture", files = files, nativeRecord = chat))
                    val runtime = SessionRuntime(app, store, fake)
                    runtime.open(owner).join()
                    assertEquals("", runtime.failure.value)
                    assertEquals(0, requests)
                    assertEquals(files, runtime.state.value!!.files)
                    assertTrue(runtime.setFiles(owner, files.take(1)))
                    assertEquals(0, requests)
                    runtime.edit("Read the selected second PDF page.", -1, 60, true)
                    runtime.send().join()
                    assertEquals("", runtime.failure.value)
                    assertEquals("", runtime.state.value!!.error)
                    assertEquals(3, requests)
                    assertEquals(files.take(1), store.load(owner)!!.files)
                    runtime.open(owner).join()
                    assertEquals(3, requests)
                    val restoredImage = runtime.state.value!!.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        .flatMap { it.optJSONArray("content")?.objects().orEmpty() }.single { it.optString("type") == "image" }
                    assertEquals("image/jpeg", restoredImage.getString("mimeType"))
                    runtime.delete(owner)
                    assertNull(store.load(owner))
                    try { documents.render(owner, files[0], 1); fail("Deleted session kept its source") }
                    catch (_: java.io.FileNotFoundException) { }
                }
            } finally {
                documents.delete(owner)
                listOf(pdf, text, empty).forEach { it.delete() }
                app.deleteDatabase(database)
            }
        }
    }
}
