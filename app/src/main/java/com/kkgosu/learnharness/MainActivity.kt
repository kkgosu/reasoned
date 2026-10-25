package com.kkgosu.learnharness

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import me.grishka.appkit.FragmentStackActivity

class MainActivity : FragmentStackActivity() {
    lateinit var mockLesson: MockLesson
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // AppKit may restore fragments inside super.onCreate; their state owner must exist first.
        mockLesson = MockLesson(resources, savedInstanceState?.getBundle("mockLesson"))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) showFragmentClearingBackStack(HomeFragment())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("mockLesson", mockLesson.save())
        super.onSaveInstanceState(outState)
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
