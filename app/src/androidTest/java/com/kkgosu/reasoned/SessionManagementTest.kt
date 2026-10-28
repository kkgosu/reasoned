package com.kkgosu.reasoned

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.fragments.GraphNodeFragment
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import com.kkgosu.reasoned.model.Finding
import com.kkgosu.reasoned.model.Findings
import com.kkgosu.reasoned.model.LessonDisplayItem
import com.kkgosu.reasoned.model.Passage
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.model.passages
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.storage.graphSessions
import com.kkgosu.reasoned.storage.knowledge
import com.kkgosu.reasoned.storage.mergeKnowledge
import com.kkgosu.reasoned.storage.recordExtraction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionManagementTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun session(goal: String = "Original goal") = RuntimeSession(
        goal = goal, model = "fixture", proseIds = setOf(2),
        items = listOf(LessonDisplayItem.TextDisplayItem(2, "A holds. B follows."))
    )

    private fun findings(batch: List<Passage>, pair: Boolean = true): Findings {
        fun proposition(id: String, statement: String) = JSONObject().put("id", id)
            .put("name", statement).put("statement", statement).put("aliases", JSONArray())
            .put("messageId", "2")

        val propositions = JSONArray().put(proposition("p1", "A holds."))
        val chains = JSONArray()
        if (pair) {
            propositions.put(proposition("p2", "B follows."))
            chains.put(
                JSONObject().put("premises", JSONArray().put("p1")).put("conclusion", "p2")
                    .put("messageId", "2")
            )
        }
        return Findings.parse(
            JSONObject().put("propositions", propositions).put("chains", chains),
            batch,
            emptySet()
        )
    }

    @Test
    fun graphSummaryShowsTheClaimAndExplicitDeduplicatedSessionLinks() {
        val statement = "A holds in fixture ${UUID.randomUUID()}."
        val saved = session().copy(
            proseIds = setOf(2, 3),
            items = listOf(
                LessonDisplayItem.TextDisplayItem(2, statement),
                LessonDisplayItem.TextDisplayItem(3, "Repeated evidence: $statement")
            )
        )
        app.sessions.save(saved)
        try {
            app.sessions.mergeKnowledge(
                saved.id, saved.passages(), Findings(
                    listOf(
                        Finding("p1", "A", statement, emptyList(), 2),
                        Finding("p2", "A", statement, emptyList(), 3)
                    ), emptyList()
                ), emptyMap(), emptySet()
            )
            app.sessions.rename(saved.id, "Source chat")
            val node = app.sessions.knowledge().nodes.single { it.birth == saved.id }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.showFragmentClearingBackStack(GraphNodeFragment().apply {
                        arguments = android.os.Bundle().apply { putString("node", node.id) }
                    })
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
                scenario.onActivity { activity ->
                    assertEquals(
                        "A",
                        activity.findViewById<android.widget.TextView>(R.id.graph_node_name).text.toString()
                    )
                    assertEquals(
                        statement,
                        activity.findViewById<android.widget.TextView>(R.id.graph_statement).text.toString()
                    )
                    val sources =
                        activity.findViewById<android.widget.LinearLayout>(R.id.graph_sources)
                    assertEquals(1, sources.childCount)
                    val link = sources.getChildAt(0) as android.widget.Button
                    assertEquals("Source chat", link.text.toString())
                    assertEquals(
                        android.view.Gravity.START,
                        link.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
                    )
                    val ripple = link.background as android.graphics.drawable.RippleDrawable
                    assertTrue(ripple.numberOfLayers > 0)
                    assertEquals(
                        android.graphics.Rect(0, 0, link.width, link.height),
                        ripple.bounds
                    )
                    val viewport =
                        activity.findViewById<android.widget.ScrollView>(R.id.graph_detail_scroll)
                    assertEquals(viewport.width, link.width)
                    val linkPosition = IntArray(2).also(link::getLocationInWindow)
                    val viewportPosition = IntArray(2).also(viewport::getLocationInWindow)
                    assertEquals(viewportPosition[0], linkPosition[0])
                    val inset = activity.resources.getDimensionPixelSize(R.dimen.space_24)
                    assertEquals(inset, link.paddingStart)
                    assertEquals(inset, link.paddingEnd)
                    assertEquals(
                        activity.getString(R.string.session_practice),
                        activity.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar).menu.getItem(
                            0
                        ).title.toString()
                    )
                    link.performClick()
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
                scenario.onActivity { activity ->
                    val chat =
                        activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>()
                            .last()
                    assertEquals(saved.id, chat.arguments!!.getString("session"))
                    assertFalse(chat.arguments!!.containsKey("message")) // Consumed by the saved-message scroll.
                    assertEquals(saved.id, app.runtime.state.value!!.id)
                }
            }
        } finally {
            kotlinx.coroutines.runBlocking { app.runtime.delete(saved.id) }
        }
    }

    @Test
    fun renameAndDeletePreserveKnowledgeButNotHistoryOrStaleWriters() {
        val name = "manage-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val q = RuntimeQuestion("answer", "Explain A", emptyList())
                val saved = session().copy(
                    question = q, items = session().items +
                            LessonDisplayItem.TextDisplayItem(
                                3,
                                "PRIVATE_ANSWER",
                                LessonDisplayItem.Role.LEARNER
                            )
                )
                store.save(saved)
                store.save(saved, q.answer("PRIVATE_ANSWER", -1, 60, "", false).toString())
                store.run("turn", saved, "complete")
                store.recordExtraction("extract", saved, "extractor", JSONObject())
                store.mergeKnowledge(
                    saved.id,
                    saved.passages(),
                    findings(saved.passages()),
                    emptyMap(),
                    emptySet()
                )
                val other = session("Another session")
                store.save(other)
                store.mergeKnowledge(
                    other.id,
                    other.passages(),
                    findings(other.passages(), false),
                    emptyMap(),
                    emptySet()
                )
                val graph = store.knowledge()

                store.rename(saved.id, "  Renamed topic  ")
                store.save(saved.copy(draft = "Later autosave"))
                assertEquals("Renamed topic", store.list().first { it.first == saved.id }.second)
                assertEquals(saved.goal, store.load(saved.id)!!.goal)
                assertEquals(saved.protocol, store.load(saved.id)!!.protocol)
                assertEquals(listOf("Renamed topic"), store.knowledge().data().clusters)
                assertEquals(
                    "Renamed topic",
                    store.knowledge().sources.values.flatten()
                        .first { it.session == saved.id }.title
                )
                assertEquals(
                    "Renamed topic",
                    store.graphSessions().getJSONArray("sessions").getJSONObject(0)
                        .getString("name")
                )
                for (invalid in listOf("   ", "\n", "A\nB", "x".repeat(2001))) {
                    try {
                        store.rename(saved.id, invalid); fail("Invalid name accepted")
                    } catch (_: IllegalArgumentException) {
                    }
                }

                store.delete(saved.id)
                store.delete(saved.id) // Idempotent; it never touches another chat's evidence.
                assertNull(store.load(saved.id))
                assertFalse(store.list().any { it.first == saved.id })
                assertNotNull(store.load(other.id))
                assertEquals(graph.nodes, store.knowledge().nodes)
                assertEquals(graph.chains, store.knowledge().chains)
                assertEquals(listOf("Renamed topic"), store.knowledge().data().clusters)
                assertTrue(
                    store.knowledge().sources.values.flatten().all { it.session == other.id })
                for (table in listOf(
                    "messages",
                    "questions",
                    "answers",
                    "runs",
                    "knowledge_sources",
                    "knowledge_coverage",
                    "knowledge_runs"
                )) {
                    store.readableDatabase.rawQuery(
                        "SELECT count(*) FROM $table WHERE session=?",
                        arrayOf(saved.id)
                    ).use {
                        assertTrue(it.moveToFirst()); assertEquals(
                        "$table retains deleted content",
                        0,
                        it.getInt(0)
                    )
                    }
                }
                store.readableDatabase.rawQuery(
                    "SELECT goal,model,checkpoint FROM sessions WHERE id=?",
                    arrayOf(saved.id)
                ).use {
                    assertTrue(it.moveToFirst()); assertEquals(
                    listOf("", "", ""),
                    (0..2).map(it::getString)
                )
                }
                try {
                    store.save(saved); fail("A late writer resurrected the deleted chat")
                } catch (_: SQLiteConstraintException) {
                }
                store.recover()
                assertNull(store.load(saved.id))
                store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null)
                    .use { assertFalse(it.moveToFirst()) }
            }
            SessionStore(app, name).use {
                assertEquals(1, it.list().size)
                assertEquals(2, it.knowledge().nodes.size)
                assertEquals(listOf("Renamed topic"), it.knowledge().data().clusters)
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun deletingTheActiveChatCancelsItsRequestAndCannotBeUndoneByStop() = runBlocking {
        val name = "delete-active-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val saved = session()
                store.save(saved)
                val entered = CompletableDeferred<Unit>()
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
                    calls++; entered.complete(Unit); awaitCancellation()
                })
                try {
                    runtime.open(saved.id).join()
                    val turn = runtime.retry()
                    withTimeout(3000) { entered.await(); runtime.delete(saved.id) }
                    assertTrue(turn.isCancelled)
                    assertNull(runtime.state.value)
                    runtime.stop().join()
                    assertNull(store.load(saved.id))
                    assertEquals(1, calls)
                } finally {
                    runtime.stop().join()
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun drawerProvidesANativeContextMenuAndVerticallyCenteredNameField() = runBlocking {
        val saved = session("Context menu ${UUID.randomUUID()}")
        app.sessions.save(saved)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val loaded = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<android.widget.ListView>(R.id.drawer_sessions)
                    list.viewTreeObserver.addOnGlobalLayoutListener(object :
                        android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if ((0 until list.count).any { list.getItemAtPosition(it) == saved.goal }) {
                                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                loaded.countDown()
                            }
                        }
                    })
                    activity.openDrawer()
                }
                assertTrue(
                    "Wait for the asynchronous session list",
                    loaded.await(5, java.util.concurrent.TimeUnit.SECONDS)
                )
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(300, 3000)
                var position = -1
                scenario.onActivity { activity ->
                    val list = activity.findViewById<android.widget.ListView>(R.id.drawer_sessions)
                    position =
                        (0 until list.count).first { list.getItemAtPosition(it) == saved.goal }
                    list.setSelection(position)
                    val body = activity.layoutInflater.inflate(R.layout.dialog_session_name, null)
                    val input = body.findViewById<android.widget.EditText>(R.id.session_name)
                    assertEquals(
                        android.view.Gravity.CENTER_VERTICAL,
                        input.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                    )
                }
                automation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<android.widget.ListView>(R.id.drawer_sessions)
                    val row = list.getChildAt(position - list.firstVisiblePosition)
                    // Use the native row long-press path, which supplies AdapterContextMenuInfo.
                    assertTrue(
                        row.performAccessibilityAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, null
                        )
                    )
                }
                automation.waitForIdle(300, 3000)
                val root = automation.rootInActiveWindow
                assertTrue(
                    root.findAccessibilityNodeInfosByText(app.getString(R.string.session_rename))
                        .isNotEmpty()
                )
                assertTrue(
                    root.findAccessibilityNodeInfosByText(app.getString(R.string.session_delete))
                        .isNotEmpty()
                )
            }
        } finally {
            app.runtime.stop().join()
            app.sessions.delete(saved.id)
            app.sessions.writableDatabase.delete("sessions", "id=?", arrayOf(saved.id))
        }
    }

    @Test
    fun versionTwoMigrationPreservesHistoryAndSupportsRenaming() {
        val name = "manage-migrate-${UUID.randomUUID()}.db"
        try {
            val saved = session()
            SessionStore(app, name).use { it.save(saved) }
            SQLiteDatabase.openDatabase(
                app.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                // Rebuild the actual v2 parent schema; this also works on SQLite versions without DROP COLUMN.
                db.execSQL("CREATE TABLE sessions_v2(id TEXT PRIMARY KEY, goal TEXT NOT NULL, model TEXT NOT NULL, checkpoint TEXT NOT NULL, updated INTEGER NOT NULL)")
                db.execSQL("INSERT INTO sessions_v2 SELECT id,goal,model,checkpoint,updated FROM sessions")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("ALTER TABLE sessions_v2 RENAME TO sessions")
                db.version = 2
            }
            SessionStore(app, name).use { store ->
                assertEquals(saved, store.load(saved.id))
                assertEquals(saved.goal, store.list().single().second)
                store.rename(saved.id, "Migrated name")
                assertEquals("Migrated name", store.list().single().second)
                assertEquals(saved, store.load(saved.id))
                assertEquals(3, store.readableDatabase.version)
                store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null)
                    .use { assertFalse(it.moveToFirst()) }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }
}
