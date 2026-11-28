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
            createTask(context, millis, timedReminder && !allDay, 0);
        } else {
            controller.sendEventRelatedEventWithExtra(context, EventType.CREATE_EVENT, -1,
                    millis, 0, 0, 0, allDay ? CalendarController.EXTRA_CREATE_ALL_DAY : 0, -1);
        }
    }

    public static void createTask(Context context, long listId) {
        context.startActivity(new Intent(context, TaskListActivity.class)
                .putExtra("list_id", listId));
    }

    public static void createTask(Context context, long millis, boolean timed, long listId) {
        context.startActivity(new Intent(context, TaskListActivity.class)
                .putExtra("date", millis).putExtra("timed_task", timed)
                .putExtra("list_id", listId).putExtra("calendar_zone", Utils.getTimeZone(context, null)));
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
    /** Individual color overrides the list; zero means inherit the default. */
    public static int displayColor(Context context, TaskStore.Task task) {
        int color = task.color != 0 ? task.color : task.listColor;
        if (color == 0) return context.getColor(R.color.event_center);
        return color;
    }

    public static void append(Context context, ArrayList<Event> events, int firstDay, int lastDay) {
        append(context, events, firstDay, lastDay, null, false);
    }

    public static void append(Context context, ArrayList<Event> events, int firstDay, int lastDay,
            java.util.Set<String> listIds, boolean includeOverdue) {
        if (lastDay < firstDay) return;
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        LocalDate first = LocalDate.ofEpochDay(firstDay - JULIAN_EPOCH);
        LocalDate end = LocalDate.ofEpochDay(lastDay - JULIAN_EPOCH + 1);
        long from = first.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = end.atStartOfDay(zone).toInstant().toEpochMilli();
        long utcFrom = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long utcUntil = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        TaskStore store = TaskStore.get(context);
        java.util.List<TaskStore.Task> tasks = store.datedTasks(from, until, utcFrom, utcUntil);
        if (includeOverdue) tasks.addAll(store.overdueTasks(from, utcFrom));
        for (TaskStore.Task task : tasks) {
            if (listIds != null && !listIds.contains(Long.toString(task.listId))) continue;
            Event item = Event.newInstance();
            item.taskId = task.id;
            item.taskCompleted = task.done;
            item.taskNoDeadline = task.due == null;
            item.taskRange = task.hasRange();
            item.taskDue = task.due == null ? 0 : task.due;
            item.taskDueAllDay = task.allDay;
            // Negative identity is reserved for tasks in the display model only.
            item.id = -task.id - 1;
            item.title = task.title;
            item.allDay = task.hasRange() ? task.rangeAllDay() : task.displayAllDay();
            item.hasAlarm = task.alarm;
            item.isRepeating = task.rule != null;
            item.color = displayColor(context, task);
            item.taskCountdown = task.due != null && task.countdown && !task.done;
            ZoneId drawingZone = item.allDay ? ZoneOffset.UTC : zone;
            item.startMillis = task.rangeStart(zone);
            item.endMillis = Math.max(item.startMillis + 60000, task.rangeEnd(zone));
            ZonedDateTime startDate = Instant.ofEpochMilli(item.startMillis).atZone(drawingZone);
            ZonedDateTime endDate = Instant.ofEpochMilli(item.endMillis).atZone(drawingZone);
            // Midnight is the exclusive end of the preceding day, as in CalendarProvider.
            boolean midnightEnd = endDate.toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
            LocalDate lastDate = midnightEnd ? endDate.toLocalDate().minusDays(1) : endDate.toLocalDate();
            item.startDay = (int) (startDate.toLocalDate().toEpochDay() + JULIAN_EPOCH);
            item.endDay = (int) (lastDate.toEpochDay() + JULIAN_EPOCH);
            item.startTime = item.allDay ? 0 : startDate.getHour() * 60 + startDate.getMinute();
            item.endTime = item.allDay || midnightEnd ? 1440 : endDate.getHour() * 60 + endDate.getMinute();
            if (item.startDay > lastDay || (item.endDay < firstDay && !includeOverdue)) continue;
            if (item.endDay < firstDay && (task.done || task.due == null)) continue;
            events.add(item);
        }
        events.sort(Comparator.comparingInt((Event item) -> item.startDay)
                .thenComparingInt(item -> item.startTime)
                .thenComparing(Comparator.comparingLong((Event item) -> item.endMillis).reversed())
                .thenComparing(item -> item.title == null ? "" : item.title.toString()));
    }
}
