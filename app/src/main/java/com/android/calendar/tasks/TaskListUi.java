package com.android.calendar.tasks;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import com.android.calendar.ItemStateSymbols;
import com.android.calendar.ItemAppearance;
import com.android.calendar.EventCountdown;
import android.text.format.DateUtils;
import com.android.calendar.DatedListUi;
import com.android.calendar.Utils;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import ws.xsoh.etar.R;

/** Common list filter and row binding for reminders. */
public final class TaskListUi {
    private TaskListUi() {}

    static long bindFilter(Spinner filter, List<TaskStore.TaskList> lists, int allLabel,
            long selected, LongConsumer onSelected) {
        Context context = filter.getContext();
        List<String> names = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        names.add(context.getString(allLabel));
        ids.add(0L);
        for (TaskStore.TaskList list : lists) {
            names.add(list.name.isEmpty() ? context.getString(R.string.tasks_default_list) : list.name);
            ids.add(list.id);
        }
        return DatedListUi.bindFilter(filter, ids, names, selected, onSelected);
    }

    static CheckBox completion(Context context) {
        CheckBox box = new CheckBox(context);
        box.setButtonDrawable((android.graphics.drawable.Drawable) null);
        box.setPadding(0, 0, 0, 0);
        box.setGravity(Gravity.CENTER);
        DatedListUi.setTextSize(box, R.dimen.item_list_marker_size);
        box.setSingleLine(true);
        return box;
    }

    private static String stateSymbol(TaskStore.Task task, boolean checked, ZoneId zone, long now) {
        return task.due == null ? (checked ? ItemStateSymbols.TASK_DONE : ItemStateSymbols.TASK_PENDING)
                : ItemStateSymbols.task(checked, task.due, task.allDay, zone, now);
    }

    /** Read-only task presentation, shared by the reminders list and global search. */
    public static int bindPresentation(View root, TextView marker, TextView title, TaskStore.Task task,
            ZoneId zone, long now, TextView... details) {
        int color = ItemAppearance.bindRow(root,
                TaskCalendarUi.displayColor(root.getContext(), task), marker, title);
        ItemAppearance.setTextColor(color, details);
        marker.setText(stateSymbol(task, task.done, zone, now));
        ItemAppearance.bindTitle(title, task.title, !task.done, task.done);
        return color;
    }

    public static String dueLabel(Context context, TaskStore.Task task, ZoneId zone, long now) {
        if (task.due == null) {
            Long display = task.displayTime();
            if (display == null) return "";
            String label = context.getString(task.start != null ? R.string.tasks_start_time : R.string.tasks_alarm_time);
            return label + " · " + (task.displayAllDay() ? context.getString(R.string.tasks_all_day)
                    : Utils.formatDateRange(context, display, display, DateUtils.FORMAT_SHOW_TIME));
        }
        String time = task.allDay ? context.getString(R.string.tasks_all_day)
                : Utils.formatDateRange(context, task.due, task.due, DateUtils.FORMAT_SHOW_TIME);
        if (task.hasRange()) {
            int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL;
            String startLabel = com.android.calendar.ItemEditorUi.formatDateTime(context, task.start,
                    flags | (task.allDay ? 0 : DateUtils.FORMAT_SHOW_TIME),
                    task.allDay ? "UTC" : zone.getId());
            String dueLabel = com.android.calendar.ItemEditorUi.formatDateTime(context, task.due,
                    flags | (task.allDay ? 0 : DateUtils.FORMAT_SHOW_TIME),
                    task.allDay ? "UTC" : zone.getId());
            time = startLabel + " – " + dueLabel;
        }
        boolean overdue = ItemStateSymbols.isTaskOverdue(task.done, task.due, task.allDay, zone, now);
        return overdue ? time + " · " + context.getString(R.string.tasks_overdue) : time;
    }

    public static String countdownLabel(Context context, TaskStore.Task task) {
        return EventCountdown.taskLabel(context, task.countdown, task.done, task.due, task.allDay);
    }

    static int bindRow(View root, CheckBox done, TextView title, TaskStore.Task task,
            TaskStore store, Runnable afterComplete) {
        Context context = root.getContext();
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        done.setOnCheckedChangeListener(null);
        done.setChecked(task.done);
        done.setEnabled(task.writable);
        int color = bindPresentation(root, done, title, task, zone, System.currentTimeMillis());
        done.setContentDescription(context.getString(R.string.tasks_complete) + " " + task.title);
        done.setOnCheckedChangeListener((button, checked) -> {
            done.setText(stateSymbol(task, checked, zone, System.currentTimeMillis()));
            store.complete(task.id, checked, task.displayTime());
            if (afterComplete != null) afterComplete.run();
        });
        root.setOnClickListener(v -> TaskCalendarUi.openTask(context, task.id));
        return color;
    }
}
