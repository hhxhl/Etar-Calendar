package com.android.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Instances;
import com.android.calendar.tasks.TaskStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Read-only access to dated items; existing stores remain authoritative. */
public final class DatedItemsRepository {
    private DatedItemsRepository() {}
    private static final long JULIAN_EPOCH = 2440588L;
    private static final String[] PROJECTION = {
            Instances._ID, Instances.EVENT_ID, Instances.TITLE, Instances.EVENT_LOCATION,
            Instances.ALL_DAY, Instances.DISPLAY_COLOR, Instances.BEGIN, Instances.END,
            Instances.START_DAY, Instances.END_DAY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS,
            Instances.ORGANIZER, Instances.OWNER_ACCOUNT, Instances.CAN_ORGANIZER_RESPOND,
            Instances.EVENT_TIMEZONE
    };

    public static final class Item<T> {
        public final LocalDate day;
        public final T value;
        public Item(LocalDate day, T value) { this.day = day; this.value = value; }
    }

    public static final class AgendaEntry {
        public final long instanceId, id, begin, end;
        public final int startDay, endDay, color, status, response;
        public final String title, location, organizer, owner, timezone;
        public final boolean allDay, organizerCanRespond;

        private AgendaEntry(Cursor cursor) {
            instanceId = cursor.getLong(0);
            id = cursor.getLong(1);
            title = cursor.getString(2);
            location = cursor.getString(3);
            allDay = cursor.getInt(4) != 0;
            color = cursor.getInt(5);
            begin = cursor.getLong(6);
            end = cursor.getLong(7);
            startDay = cursor.getInt(8);
            endDay = cursor.getInt(9);
            status = cursor.getInt(10);
            response = cursor.getInt(11);
            organizer = cursor.getString(12);
            owner = cursor.getString(13);
            organizerCanRespond = cursor.getInt(14) != 0;
            timezone = cursor.getString(15);
        }
    }

    private static int julian(LocalDate date) { return (int) (date.toEpochDay() + JULIAN_EPOCH); }
    private static LocalDate day(int julian) { return LocalDate.ofEpochDay(julian - JULIAN_EPOCH); }

    /** Invoke on a worker; CalendarProvider expands instances in a bounded range. */
    public static List<Item<AgendaEntry>> agenda(Context context, LocalDate first, LocalDate last,
            long selected, boolean hideDeclined, CancellationSignal signal) {
        return agenda(context, first, last, selected, hideDeclined, null, signal);
    }

    /** Search uses the same instance projection, visibility filters and date grouping. */
    public static List<Item<AgendaEntry>> agenda(Context context, LocalDate first, LocalDate last,
            long selected, boolean hideDeclined, String searchQuery, CancellationSignal signal) {
        if (searchQuery != null && searchQuery.trim().isEmpty()) return new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri.Builder builder = (searchQuery == null ? Instances.CONTENT_BY_DAY_URI
                : Instances.CONTENT_SEARCH_BY_DAY_URI).buildUpon();
        ContentUris.appendId(builder, julian(first));
        ContentUris.appendId(builder, julian(last));
        if (searchQuery != null) builder.appendPath(searchQuery);
        String selection = Calendars.VISIBLE + "=1";
        if (hideDeclined) selection += " AND " + Instances.SELF_ATTENDEE_STATUS
                + "!=" + Attendees.ATTENDEE_STATUS_DECLINED;
        if (selected >= 0) selection += " AND " + Instances.CALENDAR_ID + "=?";
        String[] args = selected < 0 ? null : new String[]{Long.toString(selected)};
        ArrayList<Item<AgendaEntry>> items = new ArrayList<>();
        int start = julian(first), end = julian(last);
        try (Cursor cursor = resolver.query(builder.build(), PROJECTION, selection, args,
                Instances.START_DAY + " ASC, " + Instances.BEGIN + " ASC, " + Instances.TITLE + " ASC", signal)) {
            if (cursor == null) throw new IllegalStateException("CalendarProvider returned no cursor");
            while (cursor.moveToNext()) {
                signal.throwIfCanceled();
                AgendaEntry entry = new AgendaEntry(cursor);
                for (int d = Math.max(start, entry.startDay); d <= Math.min(end, entry.endDay); d++) {
                    items.add(new Item<>(day(d), entry));
                }
            }
        }
        items.sort(Comparator.comparing((Item<AgendaEntry> row) -> row.day)
                .thenComparingLong(row -> row.value.begin)
                .thenComparing(row -> row.value.title == null ? "" : row.value.title)
                .thenComparingLong(row -> row.value.instanceId));
        return items;
    }

    /** Search visible local items, including completed items. Undated tasks ignore the date range. */
    public static List<Item<TaskStore.Task>> searchTasks(Context context, LocalDate first,
            LocalDate last, long listId, String query, CancellationSignal signal) {
        ArrayList<Item<TaskStore.Task>> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return result;
        String keyword = query.trim().toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        LocalDate until = last.plusDays(1);
        TaskStore store = TaskStore.get(context);
        List<TaskStore.Task> candidates = store.datedTasks(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                until.atStartOfDay(zone).toInstant().toEpochMilli(),
                first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        signal.throwIfCanceled();
        candidates.addAll(store.visibleUndatedTasks(listId));
        for (TaskStore.Task task : candidates) {
            signal.throwIfCanceled();
            if (listId != 0 && task.listId != listId) continue;
            if (!matches(task.title, keyword) && !matches(task.notes, keyword)
                    && !matches(task.url, keyword)) continue;
            LocalDate date = task.due == null ? null : Instant.ofEpochMilli(task.due)
                    .atZone(task.allDay ? ZoneOffset.UTC : zone).toLocalDate();
            result.add(new Item<>(date, task));
        }
        return result;
    }

    private static boolean matches(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    public static List<Item<TaskStore.Task>> reminders(TaskStore store, long listId, ZoneId zone) {
        ArrayList<Item<TaskStore.Task>> items = new ArrayList<>();
        for (TaskStore.Task task : store.visibleReminders(listId)) {
            if (task.due == null) continue;
            LocalDate date = Instant.ofEpochMilli(task.due)
                    .atZone(task.allDay ? ZoneOffset.UTC : zone).toLocalDate();
            items.add(new Item<>(date, task));
        }
        items.sort(Comparator.comparing((Item<TaskStore.Task> item) -> item.day)
                .thenComparing(item -> !item.value.allDay)
                .thenComparingLong(item -> item.value.due)
                .thenComparingLong(item -> item.value.id));
        return items;
    }
}
