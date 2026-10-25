package com.kkgosu.learnharness

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import me.grishka.appkit.FragmentStackActivity

class MainActivity : FragmentStackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) showFragmentClearingBackStack(HomeFragment())
    }

    override fun createFragmentEnterTransition(prev: View, container: View): Animator =
        ObjectAnimator.ofFloat(container, View.ALPHA, 0f, 1f).apply {
            duration = motionDuration()
        }

    // Keep AppKit's predictive-back transform cleanup instead of replacing its exit path.
    override fun createFragmentExitTransition(prev: View, container: View): Animator =
        super.createFragmentExitTransition(prev, container).apply { duration = motionDuration() }

    private fun motionDuration(): Long =
        if (ValueAnimator.areAnimatorsEnabled()) resources.getInteger(R.integer.motion_short_ms).toLong() else 0L
}
