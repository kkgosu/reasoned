package com.kkgosu.reasoned

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import me.grishka.appkit.views.UsableRecyclerView
import com.kkgosu.reasoned.ui.adapters.TextListAdapter
import android.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.fragments.SettingsFragment
import com.kkgosu.reasoned.model.RuntimeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NavigationTest {
    private fun settle() =
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)

    private fun awaitDrawer(scenario: ActivityScenario<MainActivity>, open: Boolean = true) =
        runBlocking {
            withTimeout(5000) {
                var loaded = false
                while (!loaded) {
                    scenario.onActivity { activity ->
                        val drawer = activity.findViewById<DrawerLayout>(R.id.navigation_drawer)
                        loaded = if (open) drawer.isDrawerOpen(GravityCompat.START) &&
                                activity.findViewById<UsableRecyclerView>(R.id.drawer_sessions).count > 0 &&
                                activity.findViewById<View>(R.id.drawer_status).visibility == View.GONE
                        else !drawer.isDrawerVisible(GravityCompat.START)
                    }
                    delay(20)
                }
            }
        }

    @Test
    fun settingsBackReturnsToHomeAfterDrawerNavigationAndRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { it.openDrawer() }
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.drawer_settings).performClick() }
            settle()
            scenario.recreate()
            settle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<View>(R.id.knowledge_graph).isShown)
                activity.openDrawer()
            }
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.drawer_settings).performClick() }
            settle()
            scenario.onActivity { activity ->
                val settings =
                    activity.fragmentManager.fragments.filterIsInstance<SettingsFragment>().last()
                val toolbar = settings.view!!.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar)
                val back = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
                    .filterIsInstance<ImageButton>()
                    .single { it.contentDescription == activity.getString(R.string.navigation_back) }
                assertTrue(back.performClick())
            }
            settle()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<View>(R.id.knowledge_graph).isShown)
            }
        }
    }

    @Test
    fun drawerSettingsPreferencesAndSavedSessionNavigationSurviveRecreation() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReasonedApplication
        val preferencesFile = File(app.filesDir, "AGENTS.md")
        val previous = if (preferencesFile.exists()) preferencesFile.readBytes() else null
        val fixture = RuntimeSession(
            goal = "Why gradient descent works",
            model = "fake-model",
            draft = "A saved local draft"
        )
        app.sessions.save(fixture)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar)
                    val menu = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
                        .filterIsInstance<ImageButton>()
                        .first { it.contentDescription == activity.getString(R.string.navigation_open) }
                    assertTrue(menu.performClick())
                }
                awaitDrawer(scenario)
                scenario.onActivity { activity ->
                    val drawer = activity.findViewById<DrawerLayout>(R.id.navigation_drawer)
                    assertTrue(drawer.isDrawerOpen(GravityCompat.START))
                    val list = activity.findViewById<UsableRecyclerView>(R.id.drawer_sessions)
                    assertTrue((list.adapter as TextListAdapter<*>).items.contains(fixture.goal))
                    assertTrue(activity.findViewById<View>(R.id.drawer_settings).isShown)
                }
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitDrawer(scenario, open = false)
                scenario.onActivity { activity ->
                    assertFalse(
                        activity.findViewById<DrawerLayout>(R.id.navigation_drawer)
                            .isDrawerOpen(GravityCompat.START)
                    )
                    assertNotNull(activity.findViewById<View>(R.id.knowledge_graph))
                    activity.openDrawer()
                }
                awaitDrawer(scenario)
                scenario.onActivity { it.findViewById<View>(R.id.drawer_settings).performClick() }
                settle()
                scenario.onActivity { activity ->
                    for (id in listOf(R.id.settings_providers, R.id.settings_learning, R.id.settings_about)) {
                        assertTrue(activity.findViewById<View>(id).isShown)
                    }
                    assertNull(activity.findViewById<View>(R.id.runtime_goal))
                }
                scenario.onActivity { it.findViewById<View>(R.id.settings_learning).performClick() }
                settle()
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.runtime_instructions)
                        .setText("Explain slowly in plain English.")
                    activity.findViewById<Button>(R.id.settings_save_preferences).performClick()
                }
                runBlocking {
                    withTimeout(5000) {
                        while (!preferencesFile.exists() || preferencesFile.readText() != "Explain slowly in plain English.") delay(
                            10
                        )
                    }
                }
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "Explain slowly in plain English.",
                        activity.findViewById<EditText>(R.id.runtime_instructions).text.toString()
                    )
                    activity.onBackPressed()
                }
                settle()
                scenario.onActivity { it.findViewById<View>(R.id.settings_about).performClick() }
                settle()
                scenario.onActivity { activity ->
                    assertTrue(
                        activity.getString(R.string.settings_privacy_body)
                            .contains("does not guarantee zero provider retention")
                    )
                    activity.onBackPressed()
                }
                settle()
                scenario.onActivity { it.openDrawer() }
                awaitDrawer(scenario)
                var position = -1
                scenario.onActivity { activity ->
                    val list = activity.findViewById<UsableRecyclerView>(R.id.drawer_sessions)
                    val items = (list.adapter as TextListAdapter<*>).items
                    assertTrue(
                        "Restored drawer rows: $items; status: ${activity.findViewById<android.widget.TextView>(R.id.drawer_status).text}",
                        items.contains(fixture.goal))
                    position = items.indexOf(fixture.goal)
                    list.scrollToPosition(position)
                }
                settle()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<UsableRecyclerView>(R.id.drawer_sessions)
                    assertTrue(list.findViewHolderForAdapterPosition(position)!!.itemView.performClick())
                }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "A saved local draft",
                        activity.findViewById<EditText>(R.id.lesson_composer).text.toString()
                    )
                    assertEquals(0L, app.runtime.state.value!!.inputTokens)
                }
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "A saved local draft",
                        activity.findViewById<EditText>(R.id.lesson_composer).text.toString()
                    )
                    activity.openDrawer()
                }
                awaitDrawer(scenario)
                scenario.onActivity {
                    it.findViewById<View>(R.id.drawer_new_session).performClick()
                }
                settle()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.runtime_goal))
                    assertNull(activity.findViewById<View>(R.id.runtime_key))
                    assertEquals(
                        activity.window.decorView.width,
                        activity.findViewById<View>(R.id.session_settings).width
                    )
                    assertTrue(activity.findViewById<View>(R.id.runtime_start).width < activity.window.decorView.width)
                }
            }
        } finally {
            if (previous == null) preferencesFile.delete() else preferencesFile.writeBytes(previous)
        }
    }
}
