package com.kkgosu.reasoned

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.fragments.LessonTransferFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonTransferUiTest {
    @Test
    fun cancelledPairingDoesNotRetainCredentials() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = LessonTransferFragment()
            scenario.onActivity { it.showFragmentClearingBackStack(fragment) }
            withTimeout(5000) {
                var ready = false
                while (!ready) {
                    scenario.onActivity { ready = it.findViewById<Button>(R.id.lan_enter)?.isEnabled == true }
                    if (!ready) delay(20)
                }
            }
            val dialogField = LessonTransferFragment::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.lan_enter).performClick()
                val prompt = dialogField.get(fragment) as AlertDialog
                val input = prompt.findViewById<EditText>(R.id.session_name)
                assertFalse(input.isSaveEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertTrue(input.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                val authored = "X".repeat(2048)
                input.setText(authored)
                assertEquals(authored, input.text.toString())
                // Never press Connect or send/receive anything: this only exercises local entry/cancel.
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.lan_enter).performClick()
                val prompt = dialogField.get(fragment) as AlertDialog
                assertEquals("", prompt.findViewById<EditText>(R.id.session_name).text.toString())
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
        }
    }
}
