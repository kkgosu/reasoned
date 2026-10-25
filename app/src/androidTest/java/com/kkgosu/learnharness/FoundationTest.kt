package com.kkgosu.learnharness

import android.graphics.Bitmap
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FoundationTest {
    @Test
    fun navigationFontsAndDraftSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<Button>(R.id.style_reference))
                activity.findViewById<Button>(R.id.style_reference).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val prose = activity.findViewById<TextView>(R.id.prose_sample)
                val chrome = activity.findViewById<TextView>(R.id.ui_sample)
                assertEquals(activity.resources.getFont(R.font.prose), prose.typeface)
                assertEquals(activity.resources.getFont(R.font.ui), chrome.typeface)
                assertNotEquals(chrome.typeface, prose.typeface)
                assertEquals(0xFF141414.toInt(), activity.getColor(R.color.background))
                activity.findViewById<EditText>(R.id.draft_sample).setText("A reasoning draft")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("A reasoning draft", activity.findViewById<EditText>(R.id.draft_sample).text.toString())
                assertEquals(2, activity.fragmentManager.fragments.size)
                activity.findViewById<Button>(R.id.return_home).performClick()
            }
            scenario.recreate() // AppKit finishes pending transitions when saving the stack.
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<Button>(R.id.style_reference))
                assertEquals(1, activity.fragmentManager.fragments.size)
            }
        }
    }

    // Captures for manual comparison, not an approved pixel-diff baseline yet.
    @Test
    fun captureFoundationScreens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture("home")
            scenario.onActivity { it.findViewById<Button>(R.id.style_reference).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.recreate() // Ensure the enter animation has finished before capturing.
            capture("style-reference")
        }
    }

}

// Capture the composed device frame, including system UI and native view layers.
internal fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Let native dock/fragment animations finish, not just the current UI queue.
    instrumentation.uiAutomation.waitForIdle(500, 5000)
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory = (output?.let { File(it) }
        ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
