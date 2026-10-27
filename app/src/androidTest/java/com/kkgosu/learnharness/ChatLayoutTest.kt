package com.kkgosu.learnharness

import android.graphics.Insets
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.grishka.appkit.Nav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ChatLayoutTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()

    @Test
    fun expandedPanelGainsFiftyDpWithoutGrowingTheLayoutOrOverlappingTheComposer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = LayoutInflater.from(app).inflate(R.layout.fragment_runtime_session, null)
            val panel = root.findViewById<View>(R.id.question_panel)
            val transcript = root.findViewById<View>(R.id.lesson_list)
            val dock = root.findViewById<View>(R.id.dock_shell)
            val density = app.resources.displayMetrics.density
            val extra = (50 * density).roundToInt()
            val width = app.resources.displayMetrics.widthPixels
            fun measure(height: Int) {
                root.forceLayout()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, width, height)
            }
            panel.visibility = View.VISIBLE
            measure((1000 * density).roundToInt())
            val fixed = root.measuredHeight - panel.measuredHeight - transcript.measuredHeight
            for (room in listOf(600, 300, 80).map { (it * density).roundToInt() }) {
                repeat(2) { // A second measurement must not add another 50 dp.
                    measure(fixed + room)
                    assertEquals(room, transcript.measuredHeight + panel.measuredHeight)
                    val originalPanel = room - (room * 2f / 5f).toInt()
                    assertEquals((originalPanel + extra).coerceAtMost(room), panel.measuredHeight)
                    assertEquals(
                        dock.top - (dock.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin,
                        panel.bottom
                    )
                    assertTrue(dock.bottom <= root.measuredHeight)
                }
                panel.visibility = View.GONE
                measure(fixed + room)
                assertEquals(room, transcript.measuredHeight)
                panel.visibility = View.VISIBLE
            }
        }
    }

    @Suppress("DEPRECATION")
    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun imeFramesDoNotJumpToTheFinalInsetBeforeShowingOrHiding(): Unit = runBlocking {
        val saved = RuntimeSession(goal = "Local IME animation fixture", model = "fixture")
        app.sessions.save(saved)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    Nav.go(
                        activity, RuntimeSessionFragment::class.java,
                        android.os.Bundle().apply { putString("session", saved.id) })
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    val fragment =
                        activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>()
                            .last()
                    val root = fragment.view!!
                    val original = root.rootWindowInsets
                    val initialBottom = root.paddingBottom
                    val shownBottom =
                        initialBottom + (200 * root.resources.displayMetrics.density).roundToInt()

                    fun insets(bottom: Int) = WindowInsets.Builder(original)
                        .setSystemWindowInsets(
                            Insets.of(
                                root.paddingLeft,
                                root.paddingTop,
                                root.paddingRight,
                                bottom
                            )
                        )
                        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                        .build()
                    for (end in listOf(shownBottom, initialBottom)) {
                        val start = root.paddingBottom
                        val animation = WindowInsetsAnimation(
                            WindowInsets.Type.ime(),
                            LinearInterpolator(),
                            200
                        )
                        root.dispatchWindowInsetsAnimationPrepare(animation)
                        fragment.onApplyWindowInsets(insets(end))
                        assertEquals(
                            "Final layout must not be applied before animation frames",
                            start,
                            root.paddingBottom
                        )
                        root.dispatchWindowInsetsAnimationStart(
                            animation,
                            WindowInsetsAnimation.Bounds(
                                Insets.NONE,
                                Insets.of(0, 0, 0, shownBottom)
                            )
                        )
                        for (step in 0..4) {
                            val bottom = start + (end - start) * step / 4
                            root.dispatchWindowInsetsAnimationProgress(
                                insets(bottom),
                                listOf(animation)
                            )
                            assertEquals(bottom, root.paddingBottom)
                            fragment.onApplyWindowInsets(insets(end))
                            assertEquals(
                                "Repeated final insets must not interrupt an active animation",
                                bottom,
                                root.paddingBottom
                            )
                        }
                        root.dispatchWindowInsetsAnimationEnd(animation)
                        fragment.onApplyWindowInsets(insets(end))
                        assertEquals(end, root.paddingBottom)
                    }
                    root.requestApplyInsets()
                }
            }
        } finally {
            app.runtime.stop().join()
            app.runtime.delete(saved.id)
            app.sessions.writableDatabase.delete("sessions", "id=?", arrayOf(saved.id))
        }
    }
}
