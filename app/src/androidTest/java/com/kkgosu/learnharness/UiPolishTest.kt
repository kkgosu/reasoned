package com.kkgosu.learnharness

import android.content.res.Configuration
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class UiPolishTest {
    private val layouts = listOf(
        R.layout.activity_main,
        R.layout.fragment_about,
        R.layout.fragment_diagram,
        R.layout.fragment_graph_node,
        R.layout.fragment_graph_search,
        R.layout.fragment_home,
        R.layout.fragment_learning_preferences,
        R.layout.fragment_learning_session,
        R.layout.fragment_new_session,
        R.layout.fragment_research_sources,
        R.layout.fragment_runtime_session,
        R.layout.fragment_runtime_setup,
        R.layout.fragment_session_plan,
        R.layout.fragment_settings,
        R.layout.fragment_style_reference,
        R.layout.dialog_session_name,
        R.layout.view_graph_search_toolbar,
        R.layout.item_lesson_answered,
        R.layout.item_lesson_text,
        R.layout.item_session_navigation,
        R.layout.item_graph_search,
        R.layout.item_lesson_question,
        R.layout.item_lesson_formula,
        R.layout.item_lesson_generating,
        R.layout.fragment_shell
    )

    @Test
    fun screensKeepBoundedRipplesCenteredActionsAndPaddedStartAlignedInputs() {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        val startAlignedRows = setOf(
            R.id.session_settings,
            R.id.practice_targets,
            R.id.runtime_chatgpt_usage,
            R.id.runtime_forget_key,
            R.id.research_forget_key,
            R.id.chatgpt_sign_out,
            R.id.chatgpt_reset,
            R.id.settings_reference,
            R.id.drawer_new_session,
            R.id.drawer_graph,
            R.id.drawer_settings,
            R.id.answered_thinking_toggle,
            R.id.answered_reading_toggle,
            R.id.answered_why_toggle,
            R.id.graph_search_open,
            R.id.settings_providers,
            R.id.settings_learning,
            R.id.settings_about,
            R.id.item_label,
            R.id.graph_details,
            R.id.runtime_chatgpt_account
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((widthDp, scale, rtl) in listOf(
                Triple(320, 1f, false), Triple(320, 2f, false),
                Triple(320, 2f, true), Triple(600, 2f, false)
            )) {
                val config = Configuration(app.resources.configuration).apply {
                    fontScale = scale
                    setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.ENGLISH)
                }
                val context = ContextThemeWrapper(
                    app.createConfigurationContext(config),
                    R.style.Theme_Learnharness
                )
                val density = context.resources.displayMetrics.density
                val touch = context.resources.getDimensionPixelSize(R.dimen.touch_target)
                val inputInset = context.resources.getDimensionPixelSize(R.dimen.space_12)
                val width = (widthDp * density).roundToInt()
                val height = (640 * density).roundToInt()
                fun checkRipple(view: View) {
                    val ripple = view.background as? RippleDrawable
                    assertTrue(
                        "Unbounded or missing ripple: ${view.javaClass.simpleName} ${view.id}",
                        ripple != null && ripple.numberOfLayers > 0
                    )
                    if (view.width > 0 && view.height > 0) {
                        // Synthetic layout only: no window capture or screenshot. View.draw sets background bounds.
                        val picture = Picture()
                        view.draw(picture.beginRecording(view.width, view.height))
                        picture.endRecording()
                        assertEquals(Rect(0, 0, view.width, view.height), ripple!!.bounds)
                    }
                }

                fun check(view: View) {
                    if (view is ImageButton || view.javaClass.simpleName == "ActionMenuItemView") checkRipple(
                        view
                    )
                    if (view is Button) {
                        checkRipple(view)
                        assertTrue("Small touch target: ${view.id}", view.minimumHeight >= touch)
                        assertEquals(
                            Gravity.CENTER_VERTICAL,
                            view.gravity and Gravity.VERTICAL_GRAVITY_MASK
                        )
                        if (view.height > 0 && view.text.isNotEmpty() && view.maxLines == Int.MAX_VALUE) {
                            assertTrue(
                                "Clipped button text: ${view.id}",
                                view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                            )
                        }
                        if (view !is CompoundButton) {
                            assertEquals(
                                "Wrong horizontal alignment: ${view.id}",
                                if (view.id in startAlignedRows) Gravity.START else Gravity.CENTER_HORIZONTAL,
                                view.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
                            )
                            if (view.id != R.id.session_context) {
                                assertTrue(
                                    "Missing button gutters: ${view.id}",
                                    view.paddingStart > 0 && view.paddingEnd > 0
                                )
                            }
                        }
                    }
                    if (view is TextView && view !is Button &&
                        view.javaClass.simpleName != "ActionMenuItemView"
                    ) {
                        assertTrue(
                            "Centered label/chat title: ${view.id}",
                            view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL
                        )
                    }
                    if (view is EditText) {
                        val multiline = view.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                        assertEquals(
                            "Wrong input alignment: ${view.id}",
                            if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL,
                            view.gravity and Gravity.VERTICAL_GRAVITY_MASK
                        )
                        assertEquals(
                            Gravity.START,
                            view.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
                        )
                        assertTrue(
                            "Missing input gutters: ${view.id}",
                            view.paddingStart >= inputInset && view.paddingEnd >= inputInset
                        )
                    }
                    if (view is ListView) {
                        val selector = view.selector as? RippleDrawable
                        assertTrue(selector != null && selector.numberOfLayers > 0)
                        assertTrue(view.isDrawSelectorOnTop)
                    }
                    if (view is ViewGroup) repeat(view.childCount) { check(view.getChildAt(it)) }
                }
                for (layout in layouts) {
                    val inflated = LayoutInflater.from(context).inflate(layout, null)
                    // DrawerLayout itself needs an attached window's insets; measure its content here.
                    val root = if (layout == R.layout.activity_main)
                        inflated.findViewById<View>(R.id.drawer_panel) else inflated
                    root.layoutDirection =
                        if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    if (layout == R.layout.item_lesson_answered) {
                        for (id in listOf(
                            R.id.answered_thinking_toggle,
                            R.id.answered_reading_toggle,
                            R.id.answered_why_toggle
                        )) {
                            root.findViewById<View>(id).visibility = View.VISIBLE
                        }
                    }
                    if (layout == R.layout.fragment_home) {
                        root.findViewById<TextView>(R.id.graph_loading).apply {
                            setText(R.string.knowledge_empty)
                            val gutter = resources.getDimensionPixelSize(R.dimen.space_24)
                            assertEquals(gutter, paddingStart)
                            assertEquals(gutter, paddingEnd)
                            assertEquals(Gravity.TOP or Gravity.START, gravity)
                        }
                    }
                    if (layout == R.layout.fragment_shell) {
                        root.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).apply {
                            setNavigationIcon(R.drawable.ic_close)
                            menu.add("Action").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add("Overflow")
                        }
                    }
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, width, height)
                    check(root)
                    if (layout == R.layout.item_lesson_answered) {
                        val contentInset = context.resources.getDimensionPixelSize(R.dimen.space_16)
                        for (id in listOf(
                            R.id.answered_thinking_toggle,
                            R.id.answered_reading_toggle,
                            R.id.answered_why_toggle
                        )) {
                            val toggle = root.findViewById<Button>(id)
                            assertEquals(
                                "Disclosure must reach the card's start edge",
                                0,
                                toggle.left
                            )
                            assertEquals(
                                "Disclosure must reach the card's end edge",
                                root.width,
                                toggle.right
                            )
                            assertEquals(contentInset, toggle.paddingStart)
                            assertEquals(contentInset, toggle.paddingEnd)
                        }
                        assertEquals(
                            contentInset,
                            root.findViewById<TextView>(R.id.answered_question).left
                        )
                        assertEquals(
                            contentInset,
                            root.width - root.findViewById<TextView>(R.id.answered_question).right
                        )
                    }
                    if (layout == R.layout.fragment_learning_preferences || layout == R.layout.fragment_new_session) {
                        val field = root.findViewById<EditText>(
                            if (layout == R.layout.fragment_learning_preferences) R.id.runtime_instructions else R.id.runtime_goal
                        )

                        fun checkFirstLine() = assertEquals(
                            "First line must stay at the top, including the hint",
                            field.extendedPaddingTop + field.layout.getLineBaseline(0),
                            field.baseline
                        )
                        checkFirstLine()
                        field.setText("One line")
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                        )
                        root.layout(0, 0, width, height)
                        checkFirstLine()
                    }
                    if (layout == R.layout.fragment_runtime_session) {
                        assertEquals(
                            context.resources.getFont(R.font.prose),
                            root.findViewById<TextView>(R.id.question_dont_know).typeface
                        )
                    }
                    if (layout == R.layout.fragment_style_reference) {
                        val prose = root.findViewById<TextView>(R.id.prose_sample)
                        assertTrue(prose.gravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL)
                    }
                }
                for (choice in listOf(
                    RadioButton(context, null, 0, R.style.Widget_Harness_Choice),
                    RadioButton(context, null, 0, R.style.Widget_Harness_BillingChoice),
                    CheckBox(context, null, 0, R.style.Widget_Harness_MultiChoice)
                )) check(choice)
            }
        }
    }

    @Test
    fun textStatesKeepScreenGuttersIncludingInitiallyHiddenContent() {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((widthDp, scale, rtl) in listOf(
                Triple(320, 1f, false), Triple(320, 2f, false),
                Triple(320, 2f, true), Triple(600, 2f, false)
            )) {
                val config = Configuration(app.resources.configuration).apply {
                    fontScale = scale
                    setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.ENGLISH)
                }
                val context = ContextThemeWrapper(
                    app.createConfigurationContext(config),
                    R.style.Theme_Learnharness
                )
                val density = context.resources.displayMetrics.density
                val gutter = context.resources.getDimensionPixelSize(R.dimen.space_16)
                val width = (widthDp * density).roundToInt()
                val height = (640 * density).roundToInt()
                for (layout in layouts.filterNot {
                    // AppKit's toolbar supplies its own navigation/title insets.
                    it == R.layout.fragment_shell || it == R.layout.view_graph_search_toolbar
                }) {
                    val inflated = LayoutInflater.from(context).inflate(layout, null)
                    val content = if (layout == R.layout.activity_main)
                        inflated.findViewById<View>(R.id.drawer_panel) else inflated
                    val lessonRow = layout in listOf(
                        R.layout.item_lesson_answered,
                        R.layout.item_lesson_text,
                        R.layout.item_lesson_question,
                        R.layout.item_lesson_formula,
                        R.layout.item_lesson_generating
                    )
                    val root = android.widget.FrameLayout(context).apply {
                        layoutDirection =
                            if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        // Match the real lesson list's gutters and unbounded vertical row measurement.
                        if (lessonRow) {
                            val inset = resources.getDimensionPixelSize(R.dimen.space_24)
                            setPadding(inset, 0, inset, 0)
                        }
                        (content.parent as? ViewGroup)?.removeView(content)
                        if (lessonRow) addView(android.widget.ScrollView(context).apply {
                            addView(content, ViewGroup.LayoutParams(-1, -2))
                        }, ViewGroup.LayoutParams(-1, -1))
                        else addView(content, ViewGroup.LayoutParams(-1, -1))
                    }

                    fun populate(view: View) {
                        view.visibility = View.VISIBLE
                        if (view is TextView && view !is Button && view !is EditText && view.text.isEmpty()) {
                            view.text =
                                "Saved text, status or explanation that wraps onto another line."
                        }
                        if (view is ViewGroup) repeat(view.childCount) { populate(view.getChildAt(it)) }
                    }
                    populate(content)
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, width, height)
                    fun check(view: View) {
                        if (view is TextView && view.width > 0 && view.height > 0) {
                            val bounds = Rect(0, 0, view.width, view.height)
                            root.offsetDescendantRectToMyCoords(view, bounds)
                            // Formulae and composer controls intentionally scroll horizontally: check their viewport.
                            var parent = view.parent
                            while (parent is View && parent !== root) {
                                if (parent is android.widget.HorizontalScrollView) {
                                    parent.getDrawingRect(bounds)
                                    root.offsetDescendantRectToMyCoords(parent, bounds)
                                    break
                                }
                                parent = parent.parent
                            }
                            val name = context.resources.getResourceEntryName(layout) + ":" +
                                    if (view.id == View.NO_ID) view.javaClass.simpleName else context.resources.getResourceEntryName(
                                        view.id
                                    )
                            assertTrue(
                                "Missing left gutter: $name ($widthDp dp, $scale, RTL=$rtl): $bounds",
                                bounds.left + view.compoundPaddingLeft >= gutter
                            )
                            assertTrue(
                                "Missing right gutter: $name ($widthDp dp, $scale, RTL=$rtl): $bounds",
                                root.width - bounds.right + view.compoundPaddingRight >= gutter
                            )
                            if (view !is EditText && view.maxLines == Int.MAX_VALUE && view.layout != null) {
                                assertTrue(
                                    "Clipped text: $name", view.layout.height <=
                                            view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                                )
                            }
                        }
                        if (view is ViewGroup) repeat(view.childCount) { check(view.getChildAt(it)) }
                    }
                    check(content)
                }
            }
        }
    }
}
