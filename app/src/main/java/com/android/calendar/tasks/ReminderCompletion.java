package com.android.calendar.tasks;

import android.content.Context;
import android.widget.CheckBox;

import ws.xsoh.etar.R;

/** A reminder remains an independently toggleable checkbox, with a round indicator. */
final class ReminderCompletion {
    private ReminderCompletion() {}

    static CheckBox create(Context context) {
        CheckBox box = new CheckBox(context);
        box.setButtonDrawable(R.drawable.reminder_completion_button);
        return box;
    }

}
