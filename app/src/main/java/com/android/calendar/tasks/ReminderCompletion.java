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

    /** Text-only calendar and RemoteViews projections share the same completion marker. */
    static String title(boolean completed, String title) {
        return (completed ? "\u2713\u20DD " : "\u25CB ") + title;
    }
}
