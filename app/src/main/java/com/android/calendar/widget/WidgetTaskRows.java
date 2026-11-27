package com.android.calendar.widget;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
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
    private WidgetTaskRows() {}

    static MatrixCursor merge(Context context, int widgetId, Cursor source, String[] projection,
            int firstDay, int lastDay, boolean allDayFirst) {
        return merge(context, widgetId, source, projection, firstDay, lastDay, allDayFirst, false);
    }

    static MatrixCursor merge(Context context, int widgetId, Cursor source, String[] projection,
            int firstDay, int lastDay, boolean allDayFirst, boolean includeOverdue) {
        String[] columns = java.util.Arrays.copyOf(projection, projection.length + 2);
        columns[projection.length] = TASK_COMPLETED;
        columns[projection.length + 1] = TASK_COUNTDOWN;
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
                WidgetUtils.getWidgetTaskListIds(context, widgetId), true, includeOverdue);
        for (Event task : tasks) {
            Object[] row = new Object[columns.length];
            row[projection.length] = task.taskCompleted ? 1 : 0;
            row[projection.length + 1] = task.taskCountdown ? 1 : 0;
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
                        ? ItemStateSymbols.reminder(number(row, projection.length) != 0,
                            number(row, begin), wholeDay, zone, now)
                        : ItemStateSymbols.event(number(row, begin), number(row, end), wholeDay, zone, now);
                String label = row[title] == null || row[title].toString().isEmpty()
                        ? context.getString(R.string.no_title_label) : row[title].toString();
                row[title] = ItemStateSymbols.prefix(symbol, label);
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
