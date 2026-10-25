package com.kkgosu.learnharness

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class HomeFragment : ToolbarFragment(R.layout.fragment_shell) {
    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.app_name)
        return inflater.inflate(R.layout.fragment_home, container, false).apply {
            findViewById<View>(R.id.open_sample_lesson).setOnClickListener {
                Nav.go(activity, LearningSessionFragment::class.java, null)
            }
            findViewById<View>(R.id.style_reference).setOnClickListener {
                Nav.go(activity, StyleReferenceFragment::class.java, null)
            }
        }
    }
}
