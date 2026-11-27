package com.android.calendar.tasks;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.android.calendar.CalendarController;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import ws.xsoh.etar.R;

/** The reminders navigation destination, sharing the calendar's toolbar and drawer. */
public final class UndatedTasksFragment extends Fragment implements CalendarController.EventHandler {
    private Spinner filter;
    private CheckBox scheduled;
    private LinearLayout rows;
    private ScrollView scroll;
    private long listId;
    private boolean showScheduled;
    private List<TaskStore.TaskList> lists;
    private TaskStore store;

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        store = TaskStore.get(context);
        if (state != null) {
            listId = state.getLong("list_id");
            showScheduled = state.getBoolean("show_scheduled");
        }
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        filter = new Spinner(context);
        root.addView(filter, new LinearLayout.LayoutParams(-1, -2));
        scheduled = new CheckBox(context);
        scheduled.setText(R.string.tasks_show_scheduled);
        scheduled.setChecked(showScheduled);
        scheduled.setOnCheckedChangeListener((button, checked) -> {
            showScheduled = checked;
            refresh();
        });
        root.addView(scheduled);
        scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        reloadLists();
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (lists == null || position > lists.size()) return;
                listId = position == 0 ? 0 : lists.get(position - 1).id;
                refresh();
            }
        });
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        reloadLists();
        TaskAlarmReceiver.schedule(requireContext());
    }

    @Override public void onSaveInstanceState(Bundle out) {
        out.putLong("list_id", listId);
        out.putBoolean("show_scheduled", showScheduled);
        super.onSaveInstanceState(out);
    }

    @Override public void onDestroyView() {
        rows = null;
        scroll = null;
        filter = null;
        scheduled = null;
        super.onDestroyView();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void reloadLists() {
        if (filter == null) return;
        lists = store.lists();
        ArrayList<String> names = new ArrayList<>();
        names.add(getString(R.string.tasks_all));
        int position = 0;
        for (int i = 0; i < lists.size(); i++) {
            TaskStore.TaskList list = lists.get(i);
            names.add(list.name.isEmpty() ? getString(R.string.tasks_default_list) : list.name);
            if (list.id == listId) position = i + 1;
        }
        if (position == 0) listId = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(adapter);
        filter.setSelection(position);
        refresh();
    }

    public void createTask() {
        if (!isAdded()) return;
        Intent intent = new Intent(requireContext(), TaskListActivity.class)
                .putExtra("new_task", true).putExtra("list_id", listId);
        startActivity(intent);
    }

    private void refresh() {
        if (rows == null || !isAdded()) return;
        Context context = requireContext();
        rows.removeAllViews();
        for (TaskStore.Task task : store.tasks(listId)) {
            if (showScheduled != (task.due != null)) continue;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox done = new CheckBox(context);
            done.setChecked(task.done);
            done.setContentDescription(getString(R.string.tasks_complete) + " " + task.title);
            done.setOnCheckedChangeListener((button, checked) -> {
                store.complete(task.id, checked, task.due);
                refresh();
            });
            row.addView(done);
            LinearLayout details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(6), dp(10), dp(6), dp(10));
            TextView title = new TextView(context);
            title.setText(task.title);
            title.setTextSize(18);
            if (task.done) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            details.addView(title);
            if (showScheduled && task.due != null) {
                TextView date = new TextView(context);
                java.time.ZonedDateTime value = Instant.ofEpochMilli(task.due)
                        .atZone(task.allDay ? ZoneOffset.UTC : ZoneId.systemDefault());
                date.setText(task.allDay ? value.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                        : value.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)));
                details.addView(date);
            }
            details.setOnClickListener(v -> TaskCalendarUi.openTask(context, task.id));
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            rows.addView(row);
        }
        if (rows.getChildCount() == 0) {
            TextView empty = new TextView(context);
            empty.setPadding(0, dp(20), 0, 0);
            empty.setText(R.string.tasks_empty);
            rows.addView(empty);
        }
    }

    @Override public long getSupportedEventTypes() {
        return CalendarController.EventType.EVENTS_CHANGED | CalendarController.EventType.GO_TO;
    }
    @Override public void handleEvent(CalendarController.EventInfo event) {
        if (event.eventType == CalendarController.EventType.GO_TO) {
            if ((event.extraLong & CalendarController.EXTRA_GOTO_TODAY) != 0 && scroll != null) {
                scroll.scrollTo(0, 0);
            }
        } else if (event.eventType == CalendarController.EventType.EVENTS_CHANGED) {
            eventsChanged();
        }
    }
    @Override public void eventsChanged() { refresh(); }
}
