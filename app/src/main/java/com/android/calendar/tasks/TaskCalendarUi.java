package com.android.calendar.tasks;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.Event;
import com.android.calendar.Utils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.WeakHashMap;
import ws.xsoh.etar.R;

/** Shared calendar interaction and read-only projection of local dated reminders. */
public final class TaskCalendarUi {
    private static final long JULIAN_EPOCH = 2440588L;
    private static final WeakHashMap<CalendarController, Selection> selections = new WeakHashMap<>();
    private TaskCalendarUi() {}

    public static final class Selection {
        public final long time;
        public final boolean allDay;
        private final int view;
        Selection(long time, boolean allDay, int view) {
            this.time = time;
            this.allDay = allDay;
            this.view = view;
        }
    }

    public static void rememberSelection(CalendarController controller, long time, boolean allDay) {
        selections.put(controller, new Selection(time, allDay, controller.getViewType()));
    }

    public static void clearSelection(CalendarController controller) {
        selections.remove(controller);
    }

    public static Selection selection(CalendarController controller) {
        Selection selection = selections.get(controller);
        return selection != null && selection.view == controller.getViewType()
                && selection.time == controller.getTime() ? selection : null;
    }

    public static void create(Context context, CalendarController controller, long millis,
            boolean allDay, boolean timedReminder, boolean reminder) {
        if (reminder) {
            Intent intent = new Intent(context, TaskListActivity.class);
            intent.putExtra("new_task", true);
            intent.putExtra("date", millis);
            intent.putExtra("timed_task", timedReminder && !allDay);
            intent.putExtra("calendar_zone", Utils.getTimeZone(context, null));
            context.startActivity(intent);
        } else {
            controller.sendEventRelatedEventWithExtra(context, EventType.CREATE_EVENT, -1,
                    millis, 0, 0, 0, allDay ? CalendarController.EXTRA_CREATE_ALL_DAY : 0, -1);
        }
    }

    public static void showCreate(Context context, CalendarController controller, long millis,
            boolean allDay, boolean timedReminder) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.tasks_new_item)
                .setItems(new CharSequence[]{context.getString(R.string.tasks_create_event_type),
                        context.getString(R.string.tasks_title)},
                        (dialog, which) -> create(context, controller, millis, allDay, timedReminder, which == 1))
                .show();
    }

    public static void openTask(Context context, long taskId) {
        context.startActivity(new Intent(context, TaskListActivity.class).putExtra("task_id", taskId));
    }

    public static void openItem(Context context, CalendarController controller, Event item) {
        if (item.taskId > 0) {
            openTask(context, item.taskId);
        } else {
            long type = com.android.calendar.DayView.canEditCalendarItem(context, item)
                    ? EventType.EDIT_EVENT : EventType.VIEW_EVENT_DETAILS;
            controller.sendEventRelatedEvent(context, type, item.id,
                    item.startMillis, item.endMillis, 0, 0, -1);
        }
    }

    /** Project dated tasks into the existing display model; never write them to CalendarProvider. */
    public static void append(Context context, ArrayList<Event> events, int firstDay, int lastDay) {
        append(context, events, firstDay, lastDay, null, false);
    }

    public static void append(Context context, ArrayList<Event> events, int firstDay, int lastDay,
            java.util.Set<String> listIds, boolean rawColor) {
        if (lastDay < firstDay) return;
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        LocalDate first = LocalDate.ofEpochDay(firstDay - JULIAN_EPOCH);
        LocalDate end = LocalDate.ofEpochDay(lastDay - JULIAN_EPOCH + 1);
        long from = first.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = end.atStartOfDay(zone).toInstant().toEpochMilli();
        long utcFrom = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long utcUntil = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        TaskStore store = TaskStore.get(context);
        HashMap<Long, Integer> colors = new HashMap<>();
        for (TaskStore.TaskList list : store.lists()) colors.put(list.id, list.color);
        for (TaskStore.Task task : store.datedTasks(from, until, utcFrom, utcUntil)) {
            if (listIds != null && !listIds.contains(Long.toString(task.listId))) continue;
            Event item = Event.newInstance();
            item.taskId = task.id;
            // Negative identity is reserved for tasks in the display model only.
            item.id = -task.id - 1;
            item.title = (task.done ? "☑ " : "☐ ") + task.title;
            item.allDay = task.allDay;
            item.hasAlarm = task.alarm;
            item.isRepeating = task.rule != null;
            int color = colors.getOrDefault(task.listId, 0);
            item.color = color == 0 ? context.getColor(R.color.event_center)
                    : rawColor ? color : Utils.getDisplayColorFromColor(context, color);
            ZonedDateTime date = Instant.ofEpochMilli(task.due).atZone(task.allDay ? ZoneOffset.UTC : zone);
            item.startDay = item.endDay = (int) (date.toLocalDate().toEpochDay() + JULIAN_EPOCH);
            item.startMillis = task.due;
            item.startTime = task.allDay ? 0 : date.getHour() * 60 + date.getMinute();
            // A task is a point in time. Minimum drawing height is supplied by DayView.
            item.endMillis = task.allDay ? task.due + DateUtils.DAY_IN_MILLIS : task.due + 60000;
            item.endTime = task.allDay ? 1440 : Math.min(1440, item.startTime + 1);
            events.add(item);
        }
        events.sort(Comparator.comparingInt((Event item) -> item.startDay)
                .thenComparingInt(item -> item.startTime)
                .thenComparing(Comparator.comparingLong((Event item) -> item.endMillis).reversed())
                .thenComparing(item -> item.title == null ? "" : item.title.toString()));
    }
}
