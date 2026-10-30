package com.kkgosu.reasoned

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTransferPackage
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.storage.importedGraph
import com.kkgosu.reasoned.storage.installTransfer
import com.kkgosu.reasoned.storage.exportTransfer
import com.kkgosu.reasoned.storage.transferExportLessons
import com.kkgosu.reasoned.storage.previewTransfer
import com.kkgosu.reasoned.storage.transferVersion
import com.kkgosu.reasoned.storage.transferVersions
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disposable local databases and a provider that fails on any call. No installed desktop lessons. */
@RunWith(AndroidJUnit4::class)
class NativeTransferImportTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun chat(text: String = "Authored native import", id: String = "chat_import") = NativeChat.parse(
        JSONObject().put("id", id).put("title", "Authored import sample").put("opaque", "PRESERVE")
            .put("messages", JSONArray().put(JSONObject().put("id", "m_import").put("role", "user")
                .put("timestamp", 1).put("content", text))).toString())
    private fun node(statement: String = "Authored proposition", id: String = "n_import", chat: String = "chat_import") =
        JSONObject().put("id", id).put("name", "Authored node").put("statement", statement)
            .put("createdAt", "2026-10-01T00:00:00Z").put("opaque", "NODE_PRESERVE")
            .put("provenance", JSONArray().put(JSONObject().put("chatId", chat).put("messageId", "m_import"))).toString() + "\n"
    private fun zip(chat: NativeChat = chat(), nodes: String = node()) = NativeTransferPackage.write(listOf(chat), nodes, "", "{\"version\":1,\"opaque\":\"FORMAT\"}")
    private fun install(store: SessionStore, bytes: ByteArray) = store.installTransfer(bytes, store.previewTransfer(bytes), emptySet())

    @Test fun standaloneJsonInstallsAndExportsAsZipWithoutProviderCalls() = runBlocking {
        SessionStore(app, null).use { store ->
            val original = chat()
            val bytes = original.raw.toByteArray(Charsets.UTF_8)
            val runtime = SessionRuntime(app, store, ModelProvider { _, _ -> fail("JSON import requested a provider"); error("unreachable") })
            runtime.installTransfer(bytes, store.previewTransfer(bytes), emptySet())
            runtime.open(original.id).join()
            assertEquals(original.raw, runtime.state.value!!.nativeRecord!!.raw)
            assertNotEquals("running", runtime.state.value!!.status)
            assertTrue(runtime.installTransfer(bytes, store.previewTransfer(bytes), emptySet()).isEmpty())
            val exported = NativeTransferPackage.read(runtime.exportTransfer(listOf(original.id)))
            assertEquals(original.raw, exported.chats.single().raw)
            assertEquals("", exported.nodes)
            assertEquals("", exported.chains)
        }
    }

    @Test fun importedZipAndJsonContinueWithLocalModelOnlyAfterExplicitInput() = runBlocking {
        val preferences = app.getSharedPreferences("provider", 0)
        val before = preferences.all.filterKeys { it in setOf("chatgpt", "model", "model:api") }
        try {
            val snapshot = chat().snapshot().put("settings", JSONObject().put("model",
                JSONObject().put("provider", "foreign_billing").put("id", "foreign_model")))
            snapshot.getJSONArray("messages").put(JSONObject().put("id", "a_import").put("role", "assistant")
                .put("provider", "foreign_billing").put("model", "foreign_model").put("api", "openai-responses")
                .put("stopReason", "stop").put("timestamp", 2)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Authored explanation"))))
            val original = NativeChat.parse(snapshot.toString())
            for (bytes in listOf(original.raw.toByteArray(Charsets.UTF_8), zip(original))) {
                preferences.edit().putBoolean("chatgpt", false).putString("model", "").putString("model:api", "").commit()
                SessionStore(app, null).use { store ->
                    var calls = 0
                    val fake = ModelProvider { payload, _ ->
                        calls++
                        assertEquals("fixture_local", payload.getString("model"))
                        JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                            .put("type", "message").put("id", "msg_local").put("role", "assistant")
                            .put("status", "completed").put("content", JSONArray().put(JSONObject()
                                .put("type", "output_text").put("text", "Offline continued lesson")))))
                            .put("usage", JSONObject().put("input_tokens", 40).put("output_tokens", 10))
                    }
                    val runtime = SessionRuntime(app, store, fake, providerFor = { connection ->
                        assertEquals("api", connection); fake
                    })
                    runtime.installTransfer(bytes, store.previewTransfer(bytes), emptySet())
                    runtime.open(original.id).join()
                    assertEquals(0, calls)
                    runtime.retry(continueLesson = true).join()
                    assertEquals("model_settings_invalid", runtime.failure.value)
                    assertEquals(0, calls)
                    assertEquals(original.raw, store.load(original.id)!!.nativeRecord!!.raw)
                    preferences.edit().putString("model:api", "fixture_local").commit()
                    runtime.open(original.id).join()
                    assertEquals("fixture_local", runtime.state.value!!.model)
                    assertEquals("api", runtime.state.value!!.connection)
                    assertEquals(original.raw, store.load(original.id)!!.nativeRecord!!.raw)
                    assertEquals(0, calls)
                    runtime.retry(continueLesson = true).join()
                    assertEquals("", runtime.failure.value)
                    assertEquals(1, calls)
                    val messages = store.load(original.id)!!.nativeRecord!!.snapshot().getJSONArray("messages")
                    assertEquals(snapshot.getJSONArray("messages").getJSONObject(0).toString(), messages.getJSONObject(0).toString())
                    assertEquals(snapshot.getJSONArray("messages").getJSONObject(1).toString(), messages.getJSONObject(1).toString())
                    assertEquals("Continue", messages.getJSONObject(2).getString("content"))
                    runtime.open(original.id).join()
                    assertEquals(1, calls)
                    assertEquals("fixture_local", store.load(original.id)!!.model)
                    assertTrue(store.transferVersions().any { (id, sha) -> store.transferVersion(id, sha) == original.raw })
                    val exported = NativeTransferPackage.read(runtime.exportTransfer(listOf(original.id)))
                    assertEquals(messages.toString(), exported.chats.single().snapshot().getJSONArray("messages").toString())
                }
            }
        } finally {
            preferences.edit().remove("chatgpt").remove("model").remove("model:api").apply {
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                } }
            }.commit()
        }
    }

    @Test fun transferScreenHasOnlyImportExportAndNoApprovalCheckbox() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val screen = android.view.LayoutInflater.from(app).inflate(R.layout.fragment_lesson_transfer, null)
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val views = descendants(screen)
            assertEquals(listOf("Import", "Export"), views.filterIsInstance<android.widget.Button>()
                .filter { it.visibility == android.view.View.VISIBLE }.map { it.text.toString() })
            assertTrue(views.none { it is android.widget.CheckBox })
            val text = views.filterIsInstance<android.widget.TextView>().joinToString(" ") { it.text }
            assertFalse(text.contains("legacy", ignoreCase = true))
            assertFalse(text.contains("read-only", ignoreCase = true))
            assertFalse(text.contains("Inspect imported"))
            assertFalse(text.contains("saved native version"))
        }
    }

    @Test fun atomicImportReopenDuplicateGraphAndNoExecution() = runBlocking {
        val name = "native-import-${UUID.randomUUID()}.db"
        val bytes = zip()
        try {
            SessionStore(app, name).use { store ->
                val preview = store.previewTransfer(bytes)
                assertEquals("new", preview.lessons.single().kind)
                assertTrue(preview.blockers.toString(), preview.blockers.isEmpty())
                assertTrue(store.list().isEmpty())
                assertEquals("" to "", store.importedGraph())
                assertEquals(listOf("chat_import"), store.installTransfer(bytes, preview, emptySet()))
                assertEquals(node(), store.importedGraph().first)
                assertEquals(chat().raw, store.load("chat_import")!!.nativeRecord!!.raw)
                assertEquals("imported", store.load("chat_import")!!.connection)
                val ids = store.load("chat_import")!!.nativeDisplayIds
                assertEquals("same", store.previewTransfer(bytes).lessons.single().kind)
                assertTrue(install(store, bytes).isEmpty())
                assertEquals(1, store.list().size)
                assertEquals(1, store.transferVersions().size)
                assertEquals(ids, store.load("chat_import")!!.nativeDisplayIds)
            }
            SessionStore(app, name).use { store ->
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ -> fail("Import/open attempted a provider request"); error("unreachable") })
                runtime.open("chat_import").join()
                assertEquals(chat().raw, runtime.state.value!!.nativeRecord!!.raw)
                assertEquals(chat().raw, store.load("chat_import")!!.nativeRecord!!.raw)
                assertEquals("", runtime.failure.value)
                runtime.stop().join()
                assertEquals(node(), store.importedGraph().first)
                store.delete("chat_import")
                assertNull(store.load("chat_import"))
                assertTrue(store.transferVersions().isEmpty())
                assertTrue(store.previewTransfer(bytes).blockers.isNotEmpty()) // Never silently resurrect a deletion.
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun conflictsKeepBothVersionsAndStalePreviewCannotOverwrite() {
        SessionStore(app, null).use { store ->
            install(store, zip())
            val original = store.load("chat_import")!!
            store.save(original.copy(draft = "Unsent local draft"))
            val replacement = chat("Different authored version")
            val bytes = zip(replacement)
            val preview = store.previewTransfer(bytes)
            assertEquals("conflict", preview.lessons.single().kind)
            assertTrue(preview.blockers.toString(), preview.blockers.isEmpty())
            assertTrue(store.installTransfer(bytes, preview, emptySet()).isEmpty())
            assertEquals(original.nativeRecord!!.raw, store.load("chat_import")!!.nativeRecord!!.raw)
            assertEquals(2, store.transferVersions().size)
            for ((id, sha) in store.transferVersions()) assertTrue(store.transferVersion(id, sha) in listOf(chat().raw, replacement.raw))
            val stale = store.previewTransfer(bytes)
            store.rename("chat_import", "Changed after preview")
            try { store.installTransfer(bytes, stale, setOf("chat_import")); fail("Stale preview installed") } catch (_: IllegalStateException) { }
            store.installTransfer(bytes, store.previewTransfer(bytes), setOf("chat_import"))
            assertEquals(replacement.raw, store.load("chat_import")!!.nativeRecord!!.raw)
            assertEquals(original.nativeDisplayIds, store.load("chat_import")!!.nativeDisplayIds)
            assertEquals(2, store.transferVersions().size)
            store.readableDatabase.rawQuery("SELECT checkpoint FROM native_transfer_versions WHERE checkpoint<>''", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Unsent local draft", JSONObject(row.getString(0)).getString("draft"))
            }
        }
    }

    @Test fun selectedExportPreservesNativeBytesAndMetadataWithoutLeakingOtherLessonsOrLocalState() {
        SessionStore(app, null).use { store ->
            install(store, zip())
            val other = chat("UNSELECTED_TRANSCRIPT", "chat_other")
            val otherZip = zip(other, node(id = "n_other", chat = other.id))
            val otherSha = store.previewTransfer(otherZip).packageSha
            install(store, otherZip)
            store.save(store.load("chat_import")!!.copy(draft = "LOCAL_UNSENT_DRAFT", model = "LOCAL_MODEL_BINDING"))
            val checkpoint = store.load("chat_import")!!.checkpoint()
            val versions = store.transferVersions()
            val graph = store.importedGraph()
            val exported = NativeTransferPackage.read(store.exportTransfer(listOf("chat_import")))
            assertEquals(listOf(chat().raw), exported.chats.map { it.raw })
            assertEquals(node(), exported.nodes)
            assertEquals("", exported.chains)
            assertEquals("{\"version\":1,\"opaque\":\"FORMAT\"}", exported.graphFormat)
            assertEquals(checkpoint, store.load("chat_import")!!.checkpoint())
            assertEquals(versions, store.transferVersions())
            assertEquals(graph, store.importedGraph())
            // Do not silently discard opaque global format metadata when combining package graphs.
            val different = "{\"version\":1,\"opaque\":\"DIFFERENT_FORMAT\"}"
            store.writableDatabase.execSQL("UPDATE native_transfer_graphs SET format=? WHERE package=?", arrayOf(different, otherSha))
            assertEquals(exported, NativeTransferPackage.read(store.exportTransfer(listOf("chat_import"))))
            assertEquals(different, NativeTransferPackage.read(store.exportTransfer(listOf(other.id))).graphFormat)
            try { store.exportTransfer(listOf("chat_import", other.id)); fail("Discarded differing format metadata") } catch (_: IllegalStateException) { }
        }
        SessionStore(app, null).use { store ->
            install(store, zip(nodes = ""))
            assertEquals("{\"version\":1,\"opaque\":\"FORMAT\"}",
                NativeTransferPackage.read(store.exportTransfer(listOf("chat_import"))).graphFormat)
        }
    }

    @Test fun exportListsBeyondRecentThirtyButRefusesLegacyDeletedDuplicateAndUnsettledSelections() {
        SessionStore(app, null).use { store ->
            repeat(36) { index ->
                val record = chat(id = "chat_bulk_$index")
                store.saveNative(RuntimeSession(id = record.id, goal = "Authored bulk $index", model = "", nativeRecord = record))
            }
            store.save(RuntimeSession(id = "legacy", goal = "Authored legacy", model = "fixture"))
            store.delete("chat_bulk_0")
            assertEquals(35, store.transferExportLessons().size)
            assertFalse(store.transferExportLessons().any { it.first in setOf("legacy", "chat_bulk_0") })
            assertEquals(35, NativeTransferPackage.read(store.exportTransfer(store.transferExportLessons().map { it.first })).chats.size)
            for (ids in listOf(listOf("legacy"), listOf("chat_bulk_0"), listOf("missing"))) {
                try { store.exportTransfer(ids); fail("Exported unavailable native selection") } catch (_: IllegalStateException) { }
            }
            for (ids in listOf(emptyList(), listOf("chat_bulk_1", "chat_bulk_1"), (1..101).map { "chat_$it" })) {
                try { store.exportTransfer(ids); fail("Exported invalid selection") } catch (_: IllegalArgumentException) { }
            }
            val settled = store.load("chat_bulk_1")!!
            // Compaction's transient flag is guarded by SessionRuntime.work, not serialized in SQLite.
            for (unsettled in listOf(settled.copy(status = "running"), settled.copy(nativeTurn = "{}"))) {
                store.save(unsettled)
                try { store.exportTransfer(listOf(settled.id)); fail("Exported an unsettled lesson") } catch (_: IllegalStateException) { }
            }
        }
    }

    @Test fun installedSyntheticDesktopPackageExportsThroughTheRealRuntimeWriterGate() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("nativeTransferExport") == "1")
        val input = java.io.File(app.filesDir, "native-transfer-export-input.zip")
        val bytes = input.readBytes()
        val expected = NativeTransferPackage.read(bytes)
        require(expected.chats.single().id == "chat_transfer_fixture" &&
            expected.chats.single().snapshot().getJSONObject("opaque").getString("preserve") == "Копия 🧪")
        SessionStore(app, null).use { store ->
            install(store, bytes)
            val runtime = SessionRuntime(app, store, ModelProvider { _, _ -> fail("Export attempted a provider request"); error("unreachable") })
            try {
                runtime.open(expected.chats.single().id).join()
                val output = runtime.exportTransfer(expected.chats.map { it.id })
                val actual = NativeTransferPackage.read(output)
                assertEquals(expected.chats.map { it.raw }, actual.chats.map { it.raw })
                assertEquals(expected.nodes, actual.nodes); assertEquals(expected.chains, actual.chains)
                assertEquals(expected.graphFormat, actual.graphFormat)
                java.io.File(app.filesDir, "native-transfer-export-output.zip").writeBytes(output)
            } finally { runtime.stop().join() }
        }
    }

    @Test fun fractionMarkupSurvivesTransferAndUsesTheSharedLatexRenderer() {
        val source = """Authored fractions: ${'$'}\frac{2}{4}${'$'} = ${'$'}\frac{1}{2}${'$'}."""
        val contents = NativeTransferPackage.read(zip(chat(source)))
        val text = contents.chats.single().snapshot().getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(source, text)
        val rendered = com.kkgosu.reasoned.ui.text.lessonMarkdown(app).toMarkdown(text)
        assertEquals(2, rendered.getSpans(0, rendered.length, io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan::class.java).size)
        // A malformed authored escape stays data, not an importer/renderer repair heuristic.
        val malformed = source.replace("\\frac", "\u000crac")
        assertEquals(malformed, NativeTransferPackage.read(zip(chat(malformed))).chats.single().snapshot()
            .getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun versionFourMigrationPreservesNativeArchiveAndCheckpoint() {
        val name = "native-transfer-v4-${UUID.randomUUID()}.db"
        try {
            val original = RuntimeSession(id = "chat_import", goal = "Authored migration", model = "fixture", nativeRecord = chat())
            SessionStore(app, name).use { it.save(original) }
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (table in listOf("native_transfer_graphs", "native_transfer_versions", "native_transfer_packages")) db.execSQL("DROP TABLE $table")
                db.version = 4
            }
            SessionStore(app, name).use { store ->
                assertEquals(5, store.readableDatabase.version)
                assertEquals(original.nativeRecord!!.raw, store.load(original.id)!!.nativeRecord!!.raw)
                assertEquals(original.checkpoint(), store.load(original.id)!!.checkpoint())
                assertTrue(install(store, zip()).isEmpty())
                assertEquals("api", store.load(original.id)!!.connection) // Identical import cannot change billing.
                store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun unsupportedCapabilitiesLargeDisplayRowsAndBusyLessonsCannotInstall() {
        SessionStore(app, null).use { store ->
            val unsupported = NativeChat.parse(JSONObject(chat().raw).put("kind", "unknown").toString())
            val bytes = zip(unsupported)
            assertTrue(store.previewTransfer(bytes).blockers.any { it.contains("android-projection-unsupported") })
            assertTrue(store.previewTransfer(zip(chat("x".repeat(128 * 1024 + 1)))).blockers.any { it.contains("android-display-limit") })
            assertTrue(store.list().isEmpty())
            install(store, zip())
            store.save(store.load("chat_import")!!.copy(status = "running"))
            assertTrue(store.previewTransfer(zip()).blockers.any { it.contains("active or unrecovered") })
        }
    }

    @Test fun incomingGraphCannotUseTheHistoryOfASkippedConflictingVersion() {
        SessionStore(app, null).use { store ->
            install(store, zip(nodes = ""))
            val other = chat(id = "chat_other")
            val nodes = node() + node(id = "n_other", chat = other.id)
            val chain = JSONObject().put("id", "c_import").put("premises", JSONArray().put("n_import"))
                .put("conclusion", "n_other").put("provenance", JSONArray().put(JSONObject()
                    .put("chatId", other.id).put("messageId", "m_import"))).toString()
            val bytes = NativeTransferPackage.write(listOf(chat("Different incoming history"), other), nodes, chain)
            val preview = store.previewTransfer(bytes)
            assertTrue(preview.blockers.toString(), preview.blockers.isEmpty())
            try { store.installTransfer(bytes, preview, emptySet()); fail("Graph linked to a skipped version") } catch (_: IllegalStateException) { }
            assertNull(store.load(other.id))
            assertEquals(chat().raw, store.load("chat_import")!!.nativeRecord!!.raw)
            assertEquals(1, store.transferVersions().size)
            store.installTransfer(bytes, preview, setOf("chat_import"))
            assertNotNull(store.load(other.id))
        }
    }

    @Test fun sharedGraphAmbiguityAndRollbackAreBlocked() {
        SessionStore(app, null).use { store ->
            install(store, zip())
            val other = chat(id = "chat_other")
            val conflict = zip(other, node("Conflicting definition", chat = other.id))
            val preview = store.previewTransfer(conflict)
            assertTrue(preview.blockers.any { it.startsWith("Shared node conflict") })
            try { store.installTransfer(conflict, preview, emptySet()); fail("Shared definition overwritten") } catch (_: IllegalStateException) { }
            assertNull(store.load(other.id))
            assertEquals(node(), store.importedGraph().first)
            val fresh = zip(other, node(id = "n_other", chat = other.id))
            val accepted = store.previewTransfer(fresh)
            store.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON native_transfer_graphs BEGIN SELECT RAISE(ABORT,'authored rollback'); END")
            try { store.installTransfer(fresh, accepted, emptySet()); fail("Partial import committed") } catch (_: SQLiteConstraintException) { }
            assertNull(store.load(other.id))
            assertEquals(1, store.transferVersions().size)
            assertEquals(node(), store.importedGraph().first)
            store.writableDatabase.execSQL("DROP TRIGGER reject_import")
            assertEquals(accepted, store.previewTransfer(fresh))
            install(store, fresh)
            assertNotNull(store.load(other.id))
        }
    }
}
