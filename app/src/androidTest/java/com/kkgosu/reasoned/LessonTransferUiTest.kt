package com.kkgosu.reasoned

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.fragments.LessonTransferFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class LessonTransferUiTest {
    @Test
    fun pairedActionsAndDisconnectFitLargeTextAndRtl() {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (widthDp in listOf(320, 600)) for (scale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
                val config = Configuration(app.resources.configuration).apply {
                    fontScale = scale
                    setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.ENGLISH)
                }
                val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Reasoned)
                val root = LayoutInflater.from(context).inflate(R.layout.fragment_lesson_transfer, null).apply {
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                val touch = context.resources.getDimensionPixelSize(R.dimen.touch_target)
                val disconnect = root.findViewById<ImageButton>(R.id.lan_forget)
                disconnect.visibility = View.VISIBLE
                root.findViewById<TextView>(R.id.lan_status).text = "Authored test device with a longer display name"
                for (id in listOf(R.id.lan_pending_status, R.id.lan_retry, R.id.lan_discard, R.id.transfer_install, R.id.transfer_open))
                    root.findViewById<View>(id).visibility = View.VISIBLE
                val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
                val height = (640 * context.resources.displayMetrics.density).roundToInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                for ((left, right) in listOf(R.id.transfer_choose to R.id.transfer_export, R.id.lan_send to R.id.lan_receive)) {
                    val first = root.findViewById<Button>(left)
                    val second = root.findViewById<Button>(right)
                    val row = first.parent as LinearLayout
                    assertSame(row, second.parent)
                    assertEquals(LinearLayout.HORIZONTAL, row.orientation)
                    assertFalse(row.isBaselineAligned)
                    assertTrue(abs(first.width - second.width) <= 1)
                    assertEquals(first.top, second.top)
                    assertEquals(first.height, second.height)
                    for (button in listOf(first, second)) {
                        assertNotNull(button.compoundDrawablesRelative[1])
                        assertTrue(button.width >= touch && button.height >= touch)
                        assertTrue("Clipped action at $widthDp dp / $scale / RTL=$rtl",
                            button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        for (line in 0 until button.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
                    }
                }
                assertEquals(touch, disconnect.width)
                assertEquals(touch, disconnect.height)
                assertTrue((disconnect.background as RippleDrawable).radius > 0)
                assertEquals(context.getString(R.string.lan_forget), disconnect.contentDescription.toString())
                assertEquals(disconnect.contentDescription, disconnect.tooltipText)
                val label = root.findViewById<TextView>(R.id.lan_status)
                assertSame(label.parent, disconnect.parent)
                if (rtl) assertTrue(label.left >= disconnect.right) else assertTrue(label.right <= disconnect.left)
                assertFalse(root.findViewById<Button>(R.id.lan_send).isEnabled)
                assertFalse(root.findViewById<Button>(R.id.lan_receive).isEnabled)
            }
        }
    }

    @Test
    fun connectionDialogHasExternalGuttersAndDoesNotSaveThePairingString() = runBlocking<Unit> {
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
            scenario.onActivity { it.findViewById<Button>(R.id.lan_enter).performClick() }
            val dialogField = LessonTransferFragment::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            withTimeout(5000) {
                var laidOut = false
                while (!laidOut) {
                    scenario.onActivity {
                        val prompt = dialogField.get(fragment) as AlertDialog
                        laidOut = prompt.findViewById<EditText>(R.id.session_name).let { input -> input.width > 0 && !input.isLayoutRequested }
                    }
                    if (!laidOut) delay(20)
                }
            }
            scenario.onActivity { activity ->
                val prompt = dialogField.get(fragment) as AlertDialog
                val input = prompt.findViewById<EditText>(R.id.session_name)
                val frame = input.parent as ViewGroup
                val gutter = activity.resources.getDimensionPixelSize(R.dimen.space_24)
                assertEquals(gutter, frame.paddingStart)
                assertEquals(gutter, frame.paddingEnd)
                assertEquals(frame.paddingLeft, input.left)
                assertEquals(frame.width - frame.paddingRight, input.right)
                assertTrue(input.paddingStart > 0 && input.paddingEnd > 0)
                assertFalse(input.isSaveEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertEquals(InputType.TYPE_TEXT_VARIATION_URI, input.inputType and InputType.TYPE_MASK_VARIATION)
                assertEquals(0, input.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                assertTrue(input.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                val authored = "X".repeat(2048)
                input.setText(authored)
                assertEquals(authored, input.text.toString())
                // Never press Connect or send/receive anything: this only exercises the local dialog.
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
        }
    }
}
