package com.kkgosu.reasoned.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Menu
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toolbar
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ui.adapters.TextListAdapter
import me.grishka.appkit.utils.SingleViewRecyclerAdapter
import me.grishka.appkit.views.BottomSheet
import me.grishka.appkit.views.UsableRecyclerView

class ChoiceBottomSheet(
    context: Context,
    title: Int,
    choices: List<String>,
    selected: Int,
    onConfirm: (Int) -> Unit,
    onRefresh: (() -> Unit)? = null,
    onManual: (() -> Unit)? = null,
    message: Int = 0
) : BottomSheet(context) {
    override fun dismissWithoutAnimation() {
        // Finish even an in-flight animated dismissal before the owner's window is destroyed.
        dismissed = false
        super.dismissWithoutAnimation()
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.sheet_choice)
        setNavigationBarBackground(ColorDrawable(context.getColor(R.color.accent_surface)), false)
        findViewById<Toolbar>(R.id.choice_toolbar).apply {
            setTitle(title)
            for ((label, action) in listOf(
                R.string.chatgpt_refresh_models to onRefresh,
                R.string.provider_model_manual to onManual
            )) {
                if (action != null) menu.add(Menu.NONE, label, Menu.NONE, label)
                    .setOnMenuItemClickListener {
                        dismissWithoutAnimation()
                        action()
                        true
                    }
            }
        }
        val list = findViewById<UsableRecyclerView>(R.id.choice_list)
        list.layoutManager = LinearLayoutManager(context)
        val confirm = findViewById<Button>(android.R.id.button1)
        var selection = selected
        val adapter = TextListAdapter(choices, R.layout.item_sheet_choice,
            isChecked = { it == selection }, onClick = { index ->
                val previous = selection
                selection = index
                if (previous in choices.indices) {
                    list.adapter?.notifyItemChanged(previous + if (message != 0) 1 else 0)
                }
                confirm.isEnabled = true
            })
        list.adapter = if (message == 0) adapter else ConcatAdapter(
            SingleViewRecyclerAdapter(TextView(context).apply {
                setText(message)
                val horizontal = resources.getDimensionPixelSize(R.dimen.space_24)
                val vertical = resources.getDimensionPixelSize(R.dimen.space_8)
                setPadding(horizontal, vertical, horizontal, vertical)
            }), adapter
        )
        if (selected in choices.indices) list.scrollToPosition(selected + if (message != 0) 1 else 0)
        confirm.isEnabled = choices.isEmpty() || selected in choices.indices
        confirm.setOnClickListener {
            if (choices.isEmpty()) dismiss()
            else if (selection in choices.indices) {
                dismiss()
                onConfirm(selection)
            }
        }
        findViewById<Button>(android.R.id.button2).setOnClickListener { dismiss() }
    }
}
