package com.kkgosu.reasoned

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationTest {
    @Test
    fun navigationFontsAndDraftSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openHomeAction(R.id.style_reference)
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
                assertEquals(
                    "A reasoning draft",
                    activity.findViewById<EditText>(R.id.draft_sample).text.toString()
                )
                assertEquals(2, activity.fragmentManager.fragments.size)
                activity.findViewById<Button>(R.id.return_home).performClick()
            }
            scenario.recreate() // AppKit finishes pending transitions when saving the stack.
            scenario.onActivity { activity ->
                assertNotNull(
                    activity.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).menu.findItem(
                        R.id.style_reference
                    )
                )
                assertEquals(1, activity.fragmentManager.fragments.size)
            }
        }
    }

    @Test
    fun navigationUsesMastodonsAppKitDefaults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for ((name, duration) in listOf(
                    "createFragmentEnterTransition" to 300L,
                    "createFragmentExitTransition" to 200L
                )) {
                    org.junit.Assert.assertFalse(MainActivity::class.java.declaredMethods.any { it.name == name })
                    val method =
                        me.grishka.appkit.FragmentStackActivity::class.java.getDeclaredMethod(
                            name,
                            android.view.View::class.java,
                            android.view.View::class.java
                        )
                    method.isAccessible = true
                    val animation = method.invoke(
                        activity,
                        android.view.View(activity),
                        android.view.View(activity)
                    ) as android.animation.AnimatorSet
                    assertEquals(duration, animation.duration)
                    assertEquals(3, animation.childAnimations.size)
                }
            }
        }
    }

    @Test
    fun dialogsUseRoundedSurfacesAndNativeTextActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = AlertDialog.Builder(activity).setTitle(R.string.chatgpt_reset)
                    .setMessage(R.string.chatgpt_reset_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.chatgpt_reset, null).show()
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                try {
                    org.junit.Assert.assertTrue(dialog.window!!.decorView.background is InsetDrawable)
                    val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
                        val button = dialog.getButton(id)
                        val visible = android.graphics.Rect()
                        val shown = button.getGlobalVisibleRect(visible)
                        org.junit.Assert.assertTrue(
                            "Dialog action clipped: $visible, size=${button.width}x${button.height}",
                            shown && visible.width() == button.width && visible.height() == button.height
                        )
                    }
                    org.junit.Assert.assertTrue(cancel.background is RippleDrawable)
                    org.junit.Assert.assertTrue(
                        cancel.minimumHeight >= activity.resources.getDimensionPixelSize(
                            R.dimen.touch_target
                        )
                    )
                    val text = activity.getString(android.R.string.cancel)
                    assertEquals(
                        text,
                        cancel.transformationMethod?.getTransformation(text, cancel)?.toString()
                            ?: text
                    )
                    assertEquals(
                        Typeface.create("sans-serif-medium", Typeface.NORMAL),
                        cancel.typeface
                    )
                    assertEquals(
                        activity.getString(R.string.chatgpt_reset_warning),
                        dialog.findViewById<TextView>(android.R.id.message).text.toString()
                    )
                } finally {
                    dialog.dismiss()
                }
                var selected = -1
                val picker = AlertDialog.Builder(activity).setTitle(R.string.chatgpt_models)
                    .setItems(arrayOf("Model one", "Model two")) { _, index -> selected = index }
                    .show()
                try {
                    assertEquals(2, picker.listView.count)
                    picker.listView.performItemClick(null, 1, picker.listView.adapter.getItemId(1))
                    assertEquals(1, selected)
                } finally {
                    picker.dismiss()
                }
            }
        }
    }

}

internal fun MainActivity.openHomeAction(id: Int) {
    check(findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).menu.performIdentifierAction(id, 0))
}
