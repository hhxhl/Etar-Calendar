package com.android.calendar.tasks;

import android.content.Context;
import com.android.calendar.settings.GeneralPreferences;
import java.util.Objects;

/** Read-only time semantics shared by task projections, labels and the editor.
 * Date-only values are not equal to a timed reminder, even at midnight.
 */
public final class TaskTimePresentation {
    public static final String KEY = "preferences_merge_equal_task_times";
    public final boolean range, alarmMatchesStart, alarmMatchesDue, equalEndpoints;
    public final boolean hideStart, hideDue;

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(GeneralPreferences.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    public static boolean isRange(Long start, Long due) {
        return TaskTimes.isRange(start, due);
    }

    public TaskTimePresentation(Long start, Long due, Long alarm, boolean allDay, boolean merge) {
        range = isRange(start, due);
        equalEndpoints = start != null && Objects.equals(start, due);
        alarmMatchesStart = !allDay && alarm != null && Objects.equals(start, alarm);
        alarmMatchesDue = !allDay && alarm != null && Objects.equals(due, alarm);
        hideStart = merge && (alarmMatchesStart || equalEndpoints);
        hideDue = merge && alarmMatchesDue;
    }

    public static TaskTimePresentation of(TaskStore.Task task, boolean merge) {
        return new TaskTimePresentation(task.start, task.due, task.alarmTime(), task.allDay, merge);
    }

    public boolean hasDuplicates() { return equalEndpoints || alarmMatchesStart || alarmMatchesDue; }

    /** A point can be labelled as an actual reminder only when an alarm exists. */
    public boolean reminderPoint() { return !range && (alarmMatchesStart || alarmMatchesDue); }

    /** Existing imported equal endpoints must remain editable without rewriting their values. */
    public static boolean validRange(Long start, Long due, boolean local, boolean originallyEqual) {
        if (start == null || due == null) return true;
        return start < due || (Objects.equals(start, due) && (local || originallyEqual));
    }
}
