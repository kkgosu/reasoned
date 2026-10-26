package com.kkgosu.learnharness

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.util.AtomicFile
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment
import java.io.File

/** A small native category index; focused pages share only their toolbar/lifecycle. */
class SettingsFragment : ToolbarFragment(R.layout.fragment_shell) {
    private val page get() = arguments?.getString("page")
    private var pageView: View? = null
    private var scope: CoroutineScope? = null
    private var loadPreferences = true

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        loadPreferences = savedInstanceState == null
        val layout = when (page) {
            "learning" -> R.layout.fragment_learning_preferences
            "about" -> R.layout.fragment_about
            else -> R.layout.fragment_settings
        }
        setTitle(when (page) {
            "learning" -> R.string.settings_learning
            "about" -> R.string.settings_about
            else -> R.string.settings_title
        })
        val views = inflater.inflate(layout, container, false)
        pageView = views
        when (page) {
            "learning" -> views.findViewById<Button>(R.id.settings_save_preferences).setOnClickListener { button ->
                val value = views.findViewById<EditText>(R.id.runtime_instructions).text.toString()
                button.isEnabled = false
                scope?.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val file = AtomicFile(File(activity.application.filesDir, "AGENTS.md"))
                            val out = file.startWrite()
                            try { out.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                            catch (error: Exception) { file.failWrite(out); throw error }
                        }
                        views.findViewById<TextView>(R.id.settings_status).setText(R.string.settings_saved)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { views.findViewById<TextView>(R.id.settings_status).setText(R.string.runtime_storage_error) }
                    finally { button.isEnabled = true }
                }
            }
            "about" -> views.findViewById<Button>(R.id.settings_reference).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amosblomqvist/learnharness")))
            }
            else -> {
                for ((id, title, subtitle) in listOf(
                    Triple(R.id.settings_providers, R.string.settings_providers, R.string.settings_providers_summary),
                    Triple(R.id.settings_learning, R.string.settings_learning, R.string.settings_learning_summary),
                    Triple(R.id.settings_about, R.string.settings_about, R.string.settings_about_summary),
                )) {
                    val label = SpannableStringBuilder(getString(title)).append("\n")
                    val start = label.length
                    label.append(getString(subtitle))
                    label.setSpan(TextAppearanceSpan(activity, R.style.TextAppearance_Harness_Meta), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    views.findViewById<Button>(id).text = label
                }
                views.findViewById<Button>(R.id.settings_providers).setOnClickListener { Nav.go(activity, ProviderSettingsFragment::class.java, null) }
                views.findViewById<Button>(R.id.settings_learning).setOnClickListener { openPage("learning") }
                views.findViewById<Button>(R.id.settings_about).setOnClickListener { openPage("about") }
            }
        }
        return views
    }

    private fun openPage(page: String) = Nav.go(activity, SettingsFragment::class.java, Bundle().apply { putString("page", page) })

    override fun onShown() {
        super.onShown()
        val views = pageView ?: return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (page == "learning" && loadPreferences) {
            loadPreferences = false
            val file = File(activity.application.filesDir, "AGENTS.md")
            scope?.launch {
                try {
                    val value = withContext(Dispatchers.IO) {
                        if (file.exists()) { check(file.length() <= 16_000); file.readText() } else ""
                    }
                    val editor = views.findViewById<EditText>(R.id.runtime_instructions)
                    if (editor.text.isEmpty()) editor.setText(value)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { views.findViewById<TextView>(R.id.settings_status).setText(R.string.runtime_storage_error) }
            }
        }
    }

    override fun onHidden() { scope?.cancel(); scope = null; super.onHidden() }
    override fun onDestroyView() { scope?.cancel(); scope = null; pageView = null; super.onDestroyView() }
}
