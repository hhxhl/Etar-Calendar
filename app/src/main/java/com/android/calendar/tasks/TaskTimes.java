package com.android.calendar.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Domain time calculations. UTC date-only values are distinct from timed instants. */
public final class TaskTimes {
    private TaskTimes() {}

    public static Long alarm(TaskStore.Task task) {
        if (!task.alarm) return null;
        if (task.alarmMode == TaskStore.Task.ALARM_ABSOLUTE) return task.alarmAbsolute;
        Long base = task.alarmMode == TaskStore.Task.ALARM_START ? task.start : task.due;
        if (base == null) return null;
        if (task.allDay) base = day(base, true, task.zone()).atStartOfDay(task.zone()).toInstant().toEpochMilli();
        return base - task.alarmOffset * 60_000L;
    }

    public static Long display(TaskStore.Task task) {
        return task.due != null ? task.due : task.start != null ? task.start : alarm(task);
    }

    public static boolean dateOnly(TaskStore.Task task) {
        return (task.due != null || task.start != null) && task.allDay;
    }

    public static boolean isRange(Long start, Long due) {
        return start != null && due != null && start < due;
    }

    public static LocalDate day(long millis, boolean dateOnly, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(dateOnly ? ZoneOffset.UTC : zone).toLocalDate();
    }

    public static LocalDate displayDay(TaskStore.Task task, ZoneId zone) {
        Long millis = display(task);
        return millis == null ? null : day(millis, dateOnly(task), zone);
    }

    public static long rangeStart(TaskStore.Task task) {
        return isRange(task.start, task.due) ? task.start : display(task);
    }

    /** Real exclusive range end. A timed point has no duration. */
    public static long rangeEnd(TaskStore.Task task) {
        long end = isRange(task.start, task.due) ? task.due : display(task);
        return dateOnly(task) ? day(end, true, ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() : end;
    }

    /** Drawing only: never feed this synthetic interval into stored deadlines or labels. */
    public static long drawingEnd(TaskStore.Task task) {
        long start = rangeStart(task);
        long end = rangeEnd(task);
        if (dateOnly(task) || isRange(task.start, task.due)) return end;
        return start + 60_000L;
    }
}
