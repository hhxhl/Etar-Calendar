package com.android.calendar.widget;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Instances;
import com.android.calendar.Event;
import com.android.calendar.tasks.TaskCalendarUi;
import java.util.ArrayList;
import java.util.Comparator;

/** Merge read-only task rows into widget instance projections without writing CalendarProvider. */
final class WidgetTaskRows {
    private WidgetTaskRows() {}

    static MatrixCursor merge(Context context, int widgetId, Cursor source, String[] projection,
            int firstDay, int lastDay, boolean allDayFirst) {
        ArrayList<Object[]> rows = new ArrayList<>();
        if (source != null) {
            source.moveToPosition(-1);
            while (source.moveToNext()) {
                Object[] row = new Object[projection.length];
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
                WidgetUtils.getWidgetTaskListIds(context, widgetId), true);
        for (Event task : tasks) {
            Object[] row = new Object[projection.length];
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
        MatrixCursor result = new MatrixCursor(projection, rows.size());
        for (Object[] row : rows) result.addRow(row);
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
