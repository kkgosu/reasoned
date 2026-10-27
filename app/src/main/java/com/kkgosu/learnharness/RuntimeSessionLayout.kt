package com.kkgosu.learnharness

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Keep native weighted sizing, transferring up to 50 dp from the transcript to the open panel. */
class RuntimeSessionLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val panel = findViewById<View>(R.id.question_panel)
        if (panel.visibility != View.VISIBLE) return
        val transcript = findViewById<View>(R.id.lesson_list)
        val extra = resources.getDimensionPixelSize(R.dimen.runtime_question_extra_height)
            .coerceAtMost(transcript.measuredHeight)
        if (extra == 0) return
        // Only redistribute measured height: total size, margins and the composer's space stay intact.
        panel.measure(
            MeasureSpec.makeMeasureSpec(panel.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(panel.measuredHeight + extra, MeasureSpec.EXACTLY)
        )
        transcript.measure(
            MeasureSpec.makeMeasureSpec(transcript.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(transcript.measuredHeight - extra, MeasureSpec.EXACTLY)
        )
    }
}
