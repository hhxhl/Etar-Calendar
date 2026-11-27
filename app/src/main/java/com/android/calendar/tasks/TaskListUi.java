package com.android.calendar.tasks;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import com.android.calendar.DatedListUi;
import com.android.calendar.theme.DynamicThemeKt;
import com.android.calendar.ItemStateSymbols;
import com.android.calendar.Utils;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import ws.xsoh.etar.R;

/** Common list filter and row binding for tasks and reminders. */
final class TaskListUi {
    private TaskListUi() {}

    static long bindFilter(Spinner filter, List<TaskStore.TaskList> lists, int allLabel,
            long selected, LongConsumer onSelected) {
        Context context = filter.getContext();
        ArrayList<String> names = new ArrayList<>();
        names.add(context.getString(allLabel));
        int position = 0;
        for (int i = 0; i < lists.size(); i++) {
            TaskStore.TaskList list = lists.get(i);
            names.add(list.name.isEmpty() ? context.getString(R.string.tasks_default_list) : list.name);
            if (list.id == selected) position = i + 1;
        }
        // Keep the existing adapter if the visible sources have not changed.
        List<Long> ids = new ArrayList<>();
        for (TaskStore.TaskList item : lists) ids.add(item.id);
        boolean unchanged = ids.equals(filter.getTag()) && filter.getAdapter() != null
                && filter.getAdapter().getCount() == names.size();
        if (unchanged) {
            for (int i = 0; i < names.size(); i++) {
                if (!names.get(i).equals(filter.getAdapter().getItem(i))) {
                    unchanged = false;
                    break;
                }
            }
        }
        filter.setOnItemSelectedListener(null);
        if (!unchanged) {
            ArrayAdapter<String> labels = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, names);
            labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            filter.setAdapter(labels);
            filter.setTag(ids);
        }
        if (filter.getSelectedItemPosition() != position) filter.setSelection(position);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int p, long id) {
                if (p >= 0 && p <= lists.size() && p == filter.getSelectedItemPosition())
                    onSelected.accept(p == 0 ? 0 : lists.get(p - 1).id);
            }
        });
        return position == 0 ? 0 : lists.get(position - 1).id;
    }

    static CheckBox completion(Context context, boolean reminder) {
        CheckBox box = new CheckBox(context);
        box.setButtonDrawable((android.graphics.drawable.Drawable) null);
        box.setPadding(0, 0, 0, 0);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(18);
        box.setSingleLine(true);
        return box;
    }

    private static String stateSymbol(Context context, TaskStore.Task task, boolean checked) {
        return task.due == null ? ItemStateSymbols.task(checked)
                : ItemStateSymbols.reminder(checked, task.due, task.allDay,
                    ZoneId.of(Utils.getTimeZone(context, null)), System.currentTimeMillis());
    }

    static void bindRow(View root, CheckBox done, TextView title, TaskStore.Task task,
            TaskStore store, Runnable afterComplete) {
        Context context = root.getContext();
        root.setBackgroundResource(DynamicThemeKt.getDrawableId(context,
                task.done ? "agenda_item_bg_secondary" : "agenda_item_bg_primary"));
        done.setOnCheckedChangeListener(null);
        done.setChecked(task.done);
        done.setTextColor(TaskCalendarUi.displayColor(context, task, false));
        done.setText(stateSymbol(context, task, task.done));
        done.setContentDescription(context.getString(R.string.tasks_complete) + " " + task.title);
        done.setOnCheckedChangeListener((button, checked) -> {
            done.setText(stateSymbol(context, task, checked));
            store.complete(task.id, checked, task.due);
            if (afterComplete != null) afterComplete.run();
        });
        title.setText(task.title);
        title.setTextColor(DynamicThemeKt.getColor(context, "agenda_item_standard_color"));
        title.setTypeface(task.done ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        title.setPaintFlags(task.done ? title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        root.setOnClickListener(v -> TaskCalendarUi.openTask(context, task.id));
    }
}
