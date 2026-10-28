package com.kkgosu.reasoned

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.kkgosu.reasoned.controllers.MockLesson
import com.kkgosu.reasoned.fragments.GraphNodeFragment
import com.kkgosu.reasoned.fragments.HomeFragment
import com.kkgosu.reasoned.fragments.NewSessionFragment
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import com.kkgosu.reasoned.fragments.SettingsFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.FragmentStackActivity
import me.grishka.appkit.Nav

class MainActivity : FragmentStackActivity() {
    lateinit var mockLesson: MockLesson
        private set

    private lateinit var drawer: DrawerLayout
    private val drawerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var drawerLoad: Job? = null
    private var sessionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // AppKit may restore fragments inside super.onCreate; their state owner must exist first.
        if (BuildConfig.DEBUG) {
            mockLesson = MockLesson(resources, savedInstanceState?.getBundle("mockLesson"))
        }
        super.onCreate(savedInstanceState)
        // Keep AppKit's actual stack/inset owner; DrawerLayout supplies native scrim, gestures and back handling.
        (content.parent as ViewGroup).removeView(content)
        drawer = layoutInflater.inflate(R.layout.activity_main, null) as DrawerLayout
        drawer.addView(
            content,
            0,
            DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(drawer)
        drawer.setDrawerTitle(GravityCompat.START, getString(R.string.navigation_open))
        drawer.findViewById<View>(R.id.drawer_new_session)
            .setOnClickListener { navigate(NewSessionFragment()) }
        drawer.findViewById<View>(R.id.drawer_graph).setOnClickListener { navigate(HomeFragment()) }
        drawer.findViewById<View>(R.id.drawer_settings)
            .setOnClickListener { navigate(SettingsFragment()) }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                loadDrawerSessions()
            }
        })
        if (savedInstanceState == null) showFragmentClearingBackStack(HomeFragment())
    }

    fun openDrawer() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(
            content.windowToken,
            0
        )
        drawer.openDrawer(GravityCompat.START)
    }

    private fun navigate(fragment: android.app.Fragment) {
        drawer.closeDrawer(GravityCompat.START)
        if (fragment is HomeFragment) showFragmentClearingBackStack(fragment)
        else {
            if (fragmentManager.fragments.none { it is HomeFragment && it.isVisible })
                showFragmentClearingBackStack(HomeFragment())
            Nav.go(this, fragment.javaClass, fragment.arguments)
        }
    }

    fun openSession(id: String, start: Boolean = false, message: Long? = null) =
        navigate(RuntimeSessionFragment().apply {
            arguments = Bundle().apply {
                putString("session", id); putBoolean(
                "start_session",
                start
            ); message?.let { putLong("message", it) }
            }
        })

    private fun loadDrawerSessions() {
        val status = drawer.findViewById<TextView>(R.id.drawer_status)
        val list = drawer.findViewById<ListView>(R.id.drawer_sessions)
        status.setText(R.string.navigation_loading)
        status.visibility = View.VISIBLE
        drawerLoad?.cancel()
        drawerLoad = drawerScope.launch {
            try {
                val sessions =
                    withContext(Dispatchers.IO) { (application as ReasonedApplication).sessions.list() }
                list.adapter = ArrayAdapter(
                    this@MainActivity,
                    R.layout.item_session_navigation,
                    sessions.map { it.second })
                status.setText(R.string.navigation_empty)
                status.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                val active = fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>()
                    .firstOrNull { it.isVisible }?.arguments?.getString("session")
                sessions.indexOfFirst { it.first == active }.takeIf { it >= 0 }
                    ?.let { list.setItemChecked(it, true) }
                list.setOnItemClickListener { _, _, position, _ -> openSession(sessions[position].first) }
                list.setOnCreateContextMenuListener { menu, _, info ->
                    val position = (info as? AdapterView.AdapterContextMenuInfo)?.position
                        ?: return@setOnCreateContextMenuListener
                    val (id, title) = sessions[position]
                    menu.add(R.string.session_rename).setOnMenuItemClickListener {
                        renameSession(id, title)
                        true
                    }
                    menu.add(R.string.session_delete).setOnMenuItemClickListener {
                        deleteSession(id, title)
                        true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.setText(R.string.runtime_storage_error)
            }
        }
    }

    private fun renameSession(id: String, title: String) {
        val body = layoutInflater.inflate(R.layout.dialog_session_name, null)
        val input = body.findViewById<EditText>(R.id.session_name)
        input.setText(title)
        input.setSelection(input.length())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.session_rename)
            .setView(body).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.any { it.isISOControl() }) input.error =
                    getString(R.string.session_name_invalid)
                else updateSessions(dialog) {
                    withContext(Dispatchers.IO) {
                        (application as ReasonedApplication).sessions.rename(
                            id,
                            name
                        )
                    }
                }
            }
        }
        sessionDialog = dialog
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun deleteSession(id: String, title: String) {
        val dialog = AlertDialog.Builder(this).setTitle(R.string.session_delete)
            .setMessage(getString(R.string.session_delete_notice, title))
            .setPositiveButton(R.string.session_delete, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                updateSessions(dialog) {
                    (application as ReasonedApplication).runtime.delete(id)
                    if (fragmentManager.fragments.any {
                            it is RuntimeSessionFragment && it.arguments?.getString(
                                "session"
                            ) == id
                        })
                        navigate(HomeFragment())
                }
            }
        }
        sessionDialog = dialog
        dialog.show()
    }

    private fun updateSessions(dialog: AlertDialog, action: suspend () -> Unit) {
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        drawerScope.launch {
            try {
                action()
                dialog.dismiss()
                loadDrawerSessions()
                fragmentManager.fragments.forEach {
                    when (it) {
                        is HomeFragment -> it.refreshGraph()
                        is GraphNodeFragment -> it.refreshGraph()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                button.isEnabled = true
                Toast.makeText(this@MainActivity, R.string.session_action_failed, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        sessionDialog?.dismiss()
        drawerScope.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        (application as ReasonedApplication).extraction.foreground = true
    }

    override fun onStop() {
        (application as ReasonedApplication).pauseRuntime()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (BuildConfig.DEBUG) outState.putBundle("mockLesson", mockLesson.save())
        super.onSaveInstanceState(outState)
    }

}
