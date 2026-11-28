package com.android.calendar.widget;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Instances;
import com.android.calendar.Event;
import com.android.calendar.ItemStateSymbols;
import com.android.calendar.Utils;
import java.time.ZoneId;
import ws.xsoh.etar.R;
import com.android.calendar.tasks.TaskCalendarUi;
import java.util.ArrayList;
import java.util.Comparator;

/** Merge read-only task rows into widget instance projections without writing CalendarProvider. */
final class WidgetTaskRows {
    static final String TASK_COMPLETED = "etar_widget_task_completed";
    static final String TASK_COUNTDOWN = "etar_widget_task_countdown";
    static final String TASK_NO_DEADLINE = "etar_widget_task_no_deadline";
    static final String TASK_DUE = "etar_widget_task_due";
    static final String TASK_DUE_ALL_DAY = "etar_widget_task_due_all_day";
    static final String TASK_RANGE = "etar_widget_task_range";
    static final String ITEM_STATE = "etar_widget_item_state";
    private static final String TASK_IN_PROGRESS = "etar_widget_task_in_progress";
    private WidgetTaskRows() {}

    /** Shared grid query: the source cursor is closed before the merged snapshot is returned. */
    static MatrixCursor queryGrid(Context context, int widgetId, long start, long end,
            String[] projection, String sortOrder, int firstDay, int lastDay,
            BaseGridWidgetProvider provider) {
        long now = System.currentTimeMillis();
        try (Cursor source = queryCalendar(context, widgetId, start, end, projection, sortOrder)) {
            MatrixCursor snapshot = merge(context, widgetId, source, projection, firstDay, lastDay, true);
            try {
                provider.scheduleStateUpdate(context, widgetId, nextTimedTransition(snapshot, now));
                return snapshot;
            } catch (RuntimeException error) {
                snapshot.close();
                throw error;
            }
        } catch (RuntimeException error) {
            provider.scheduleStateUpdate(context, widgetId, now + 15 * 60_000L);
            throw error;
        }
    }

    /** All-day status changes are handled by the provider's midnight update. */
    private static long nextTimedTransition(Cursor cursor, long now) {
        int begin = cursor.getColumnIndexOrThrow(Instances.BEGIN);
        int end = cursor.getColumnIndexOrThrow(Instances.END);
        int id = cursor.getColumnIndexOrThrow(Instances.EVENT_ID);
        int allDay = cursor.getColumnIndexOrThrow(Instances.ALL_DAY);
        int done = cursor.getColumnIndexOrThrow(TASK_COMPLETED);
        int noDeadline = cursor.getColumnIndexOrThrow(TASK_NO_DEADLINE);
        int due = cursor.getColumnIndexOrThrow(TASK_DUE);
        int dueAllDay = cursor.getColumnIndexOrThrow(TASK_DUE_ALL_DAY);
        long next = Long.MAX_VALUE;
        while (cursor.moveToNext()) {
            boolean task = cursor.getLong(id) < -1;
            if (task) {
                if (cursor.getInt(noDeadline) != 0 || cursor.getInt(dueAllDay) != 0) continue;
                next = Math.min(next, ItemStateSymbols.nextTimedTransition(
                        cursor.getLong(due), cursor.getLong(due), true, cursor.getInt(done) != 0, now));
            } else if (cursor.getInt(allDay) == 0) {
                next = Math.min(next, ItemStateSymbols.nextTimedTransition(
                        cursor.getLong(begin), cursor.getLong(end), false, false, now));
            }
        }
        cursor.moveToPosition(-1);
        return next;
    }

    /** The local task source does not require CalendarProvider permission. */
    static Cursor queryCalendar(Context context, int widgetId, long start, long end,
            String[] projection, String sortOrder) {
        if (!Utils.isCalendarPermissionGranted(context, false)) return null;
        Uri uri = Uri.withAppendedPath(Instances.CONTENT_URI, start + "/" + end);
        try {
            Cursor cursor = context.getContentResolver().query(uri, projection,
                    WidgetUtils.getWidgetSelection(context, widgetId), null, sortOrder);
            if (cursor == null) throw new IllegalStateException("CalendarProvider returned no cursor");
            return cursor;
        } catch (SecurityException denied) {
            if (!Utils.isCalendarPermissionGranted(context, false)) return null;
            throw denied;
        }
    }

    static MatrixCursor merge(Context context, int widgetId, Cursor source, String[] projection,
            int firstDay, int lastDay, boolean allDayFirst) {
        return merge(context, widgetId, source, projection, firstDay, lastDay, allDayFirst, false);
    }

    static MatrixCursor merge(Context context, int widgetId, Cursor source, String[] projection,
            int firstDay, int lastDay, boolean allDayFirst, boolean includeOverdue) {
        String[] columns = java.util.Arrays.copyOf(projection, projection.length + 8);
        columns[projection.length] = TASK_COMPLETED;
        columns[projection.length + 1] = TASK_COUNTDOWN;
        columns[projection.length + 2] = TASK_NO_DEADLINE;
        columns[projection.length + 3] = TASK_DUE;
        columns[projection.length + 4] = TASK_DUE_ALL_DAY;
        columns[projection.length + 5] = TASK_RANGE;
        columns[projection.length + 6] = TASK_IN_PROGRESS;
        columns[projection.length + 7] = ITEM_STATE;
        ArrayList<Object[]> rows = new ArrayList<>();
        if (source != null) {
            source.moveToPosition(-1);
            while (source.moveToNext()) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < projection.length; i++) {
                    switch (source.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: row[i] = source.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: row[i] = source.getDouble(i); break;
                        case Cursor.FIELD_TYPE_STRING: row[i] = source.getString(i); break;
                        case Cursor.FIELD_TYPE_BLOB: row[i] = source.getBlob(i); break;
                        default: row[i] = null;
                    }
                }
                rows.add(row);
            }
        }
        ArrayList<Event> tasks = new ArrayList<>();
        TaskCalendarUi.append(context, tasks, firstDay, lastDay,
                WidgetUtils.getWidgetTaskListIds(context, widgetId), includeOverdue);
        for (Event task : tasks) {
            Object[] row = new Object[columns.length];
            row[projection.length] = task.taskCompleted ? 1 : 0;
            row[projection.length + 1] = task.taskCountdown ? 1 : 0;
            row[projection.length + 2] = task.taskNoDeadline ? 1 : 0;
            row[projection.length + 3] = task.taskDue;
            row[projection.length + 4] = task.taskDueAllDay ? 1 : 0;
            row[projection.length + 5] = task.taskRange ? 1 : 0;
            row[projection.length + 6] = task.taskInProgress ? 1 : 0;
            for (int i = 0; i < projection.length; i++) {
                String field = projection[i];
                if (Instances.ALL_DAY.equals(field)) row[i] = task.allDay ? 1 : 0;
                else if (Instances.BEGIN.equals(field)) row[i] = task.startMillis;
                else if (Instances.END.equals(field)) row[i] = task.endMillis;
                else if (Instances.TITLE.equals(field)) row[i] = task.title.toString();
                else if (Instances.EVENT_ID.equals(field)) row[i] = task.id;
                else if (Instances.START_DAY.equals(field)) row[i] = task.startDay;
                else if (Instances.END_DAY.equals(field)) row[i] = task.endDay;
                else if (Instances.START_MINUTE.equals(field)) row[i] = task.startTime;
                else if (Instances.END_MINUTE.equals(field)) row[i] = task.endTime;
                else if (Instances.DISPLAY_COLOR.equals(field)) row[i] = task.color;
                else if (Instances.SELF_ATTENDEE_STATUS.equals(field)) row[i] = Attendees.ATTENDEE_STATUS_NONE;
            }
            rows.add(row);
        }
        int allDay = index(projection, Instances.ALL_DAY);
        int day = index(projection, Instances.START_DAY);
        int minute = index(projection, Instances.START_MINUTE);
        int begin = index(projection, Instances.BEGIN);
        int title = index(projection, Instances.TITLE);
        // Stable sorting preserves the order of otherwise identical source rows.
        Comparator<Object[]> comparator = Comparator.comparingLong(row -> number(row, day));
        if (allDayFirst) comparator = Comparator.<Object[]>comparingLong(row -> -number(row, allDay))
                .thenComparing(comparator);
        comparator = comparator.thenComparingLong(row -> number(row, minute >= 0 ? minute : begin))
                .thenComparing(row -> title < 0 || row[title] == null ? "" : row[title].toString());
        rows.sort(comparator);
        MatrixCursor result = new MatrixCursor(columns, rows.size());
        int end = index(projection, Instances.END);
        int id = index(projection, Instances.EVENT_ID);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        for (Object[] row : rows) {
            if (title >= 0 && begin >= 0 && end >= 0 && id >= 0) {
                boolean wholeDay = number(row, allDay) != 0;
                String symbol = number(row, id) < -1
                        ? ItemStateSymbols.task(number(row, projection.length) != 0,
                                number(row, projection.length + 6) != 0,
                                number(row, projection.length + 2) != 0 ? null : number(row, projection.length + 3),
                                number(row, projection.length + 4) != 0, zone, now)
                        : ItemStateSymbols.event(number(row, begin), number(row, end), wholeDay, zone, now);
                String label = row[title] == null || row[title].toString().isEmpty()
                        ? context.getString(R.string.no_title_label) : row[title].toString();
                row[title] = label;
                row[projection.length + 7] = symbol;
            }
            result.addRow(row);
        }
        return result;
    }

    private static int index(String[] projection, String name) {
        for (int i = 0; i < projection.length; i++) if (name.equals(projection[i])) return i;
        return -1;
    }

    private static long number(Object[] row, int index) {
        return index >= 0 && row[index] instanceof Number ? ((Number) row[index]).longValue() : 0;
    }
}
