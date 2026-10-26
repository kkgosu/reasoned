package com.kkgosu.learnharness

import android.os.Bundle
import androidx.core.view.GravityCompat
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // AppKit may restore fragments inside super.onCreate; their state owner must exist first.
        mockLesson = MockLesson(resources, savedInstanceState?.getBundle("mockLesson"))
        super.onCreate(savedInstanceState)
        // Keep AppKit's actual stack/inset owner; DrawerLayout supplies native scrim, gestures and back handling.
        (content.parent as ViewGroup).removeView(content)
        drawer = layoutInflater.inflate(R.layout.activity_main, null) as DrawerLayout
        drawer.addView(content, 0, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(drawer)
        drawer.setDrawerTitle(GravityCompat.START, getString(R.string.navigation_open))
        drawer.findViewById<View>(R.id.drawer_new_session).setOnClickListener { navigate(NewSessionFragment()) }
        drawer.findViewById<View>(R.id.drawer_graph).setOnClickListener { navigate(HomeFragment()) }
        drawer.findViewById<View>(R.id.drawer_settings).setOnClickListener { navigate(SettingsFragment()) }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { loadDrawerSessions() }
        })
        if (savedInstanceState == null) showFragmentClearingBackStack(HomeFragment())
    }

    fun openDrawer() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(content.windowToken, 0)
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

    fun openSession(id: String, start: Boolean = false) = navigate(RuntimeSessionFragment().apply {
        arguments = Bundle().apply { putString("session", id); putBoolean("start_session", start) }
    })

    private fun loadDrawerSessions() {
        val status = drawer.findViewById<TextView>(R.id.drawer_status)
        val list = drawer.findViewById<ListView>(R.id.drawer_sessions)
        status.setText(R.string.navigation_loading)
        status.visibility = View.VISIBLE
        drawerScope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) { (application as ReasonedApplication).sessions.list() }
                list.adapter = ArrayAdapter(this@MainActivity, R.layout.item_session_navigation, sessions.map { it.second })
                status.setText(R.string.navigation_empty)
                status.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                val active = fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().firstOrNull { it.isVisible }?.arguments?.getString("session")
                sessions.indexOfFirst { it.first == active }.takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
                list.setOnItemClickListener { _, _, position, _ -> openSession(sessions[position].first) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.setText(R.string.runtime_storage_error) }
        }
    }

    override fun onDestroy() {
        drawerScope.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        (application as ReasonedApplication).pauseRuntime()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("mockLesson", mockLesson.save())
        super.onSaveInstanceState(outState)
    }

}
