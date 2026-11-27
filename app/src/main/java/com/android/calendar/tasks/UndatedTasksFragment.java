package com.android.calendar.tasks;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.android.calendar.CalendarController;
import com.android.calendar.DatedListUi;
import com.android.calendar.theme.DynamicThemeKt;
import java.util.ArrayList;
import java.util.List;
import ws.xsoh.etar.R;

/** Undated tasks share the reminder list presentation without date grouping. */
public final class UndatedTasksFragment extends Fragment implements CalendarController.EventHandler {
    private Spinner filter;
    private ListView list;
    private TaskAdapter adapter;
    private long listId;
    private TaskStore store;
    private final List<TaskStore.Task> rows = new ArrayList<>();

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        store = TaskStore.get(context);
        if (state != null) listId = state.getLong("list_id");
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        filter = new Spinner(context);
        root.addView(filter, new LinearLayout.LayoutParams(-1, -2));
        adapter = new TaskAdapter();
        list = DatedListUi.createList(context, adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        reloadLists();
        refresh(true);
    }

    @Override public void onSaveInstanceState(Bundle out) {
        out.putLong("list_id", listId);
        super.onSaveInstanceState(out);
    }

    @Override public void onDestroyView() {
        list = null;
        filter = null;
        adapter = null;
        rows.clear();
        super.onDestroyView();
    }

    private void reloadLists() {
        if (filter == null) return;
        listId = TaskListUi.bindFilter(filter, store.visibleLists(), R.string.tasks_all, listId,
                selected -> {
                    if (selected != listId) {
                        listId = selected;
                        refresh(false);
                    }
                });
    }

    public long getListId() { return listId; }

    public void createTask() {
        if (!isAdded()) return;
        startActivity(new Intent(requireContext(), TaskListActivity.class)
                .putExtra("new_task", true).putExtra("list_id", listId));
    }

    private void refresh(boolean preservePosition) {
        if (list == null || !isAdded()) return;
        int first = list.getFirstVisiblePosition();
        long anchor = first < rows.size() ? rows.get(first).id : -1;
        int offset = DatedListUi.topOffset(list);
        rows.clear();
        rows.addAll(store.visibleUndatedTasks(listId));
        adapter.notifyDataSetChanged();
        if (!preservePosition) {
            list.setSelection(0);
        } else if (anchor != -1 && !rows.isEmpty()) {
            int index = Math.min(first, rows.size() - 1);
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == anchor) { index = i; break; }
            }
            list.setSelectionFromTop(index, offset);
        }
    }

    private final class TaskRow {
        final DatedListUi.RowLayout layout;
        final CheckBox done;
        final TextView title;
        TaskRow(Context context) {
            layout = new DatedListUi.RowLayout(context);
            done = TaskListUi.completion(context, false);
            layout.addLeading(done);
            title = layout.addText(18);
            layout.root.setTag(this);
        }

        void bind(TaskStore.Task task) {
            TaskListUi.bindRow(layout.root, done, title, task, store, () -> refresh(true));
        }
    }

    private final class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, rows.size()); }
        @Override public Object getItem(int position) { return rows.isEmpty() ? null : rows.get(position); }
        @Override public long getItemId(int position) { return rows.isEmpty() ? -1 : rows.get(position).id; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return !rows.isEmpty(); }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.isEmpty() ? 0 : 1; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Context context = parent.getContext();
            if (rows.isEmpty()) {
                TextView empty = recycled instanceof TextView ? (TextView) recycled : new TextView(context);
                int p = DatedListUi.dp(context, 16);
                empty.setPadding(p, DatedListUi.dp(context, 20), p, DatedListUi.dp(context, 20));
                empty.setTextColor(DynamicThemeKt.getColor(context, "agenda_item_where_text_color"));
                empty.setBackgroundResource(DynamicThemeKt.getDrawableId(context, "agenda_item_bg_primary"));
                empty.setText(R.string.tasks_empty);
                return empty;
            }
            TaskRow row = recycled != null && recycled.getTag() instanceof TaskRow
                    ? (TaskRow) recycled.getTag() : new TaskRow(context);
            row.bind(rows.get(position));
            return row.layout.root;
        }
    }

    @Override public long getSupportedEventTypes() {
        return CalendarController.EventType.EVENTS_CHANGED | CalendarController.EventType.GO_TO;
    }

    @Override public void handleEvent(CalendarController.EventInfo event) {
        if (event.eventType == CalendarController.EventType.GO_TO) {
            if ((event.extraLong & CalendarController.EXTRA_GOTO_TODAY) != 0 && list != null)
                list.setSelection(0);
        } else if (event.eventType == CalendarController.EventType.EVENTS_CHANGED) {
            eventsChanged();
        }
    }

    @Override public void eventsChanged() {
        reloadLists();
        refresh(true);
    }
}
