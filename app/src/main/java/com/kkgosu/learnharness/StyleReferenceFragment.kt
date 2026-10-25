package com.kkgosu.learnharness

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class StyleReferenceFragment : ToolbarFragment(R.layout.fragment_shell) {
    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setTitle(R.string.style_reference)
        return inflater.inflate(R.layout.fragment_style_reference, container, false).apply {
            findViewById<View>(R.id.return_home).setOnClickListener { Nav.finish(this@StyleReferenceFragment) }
        }
    }
}
