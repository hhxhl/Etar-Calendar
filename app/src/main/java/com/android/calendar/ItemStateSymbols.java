package com.android.calendar;

import android.content.Context;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Shared Unicode status markers; never changes stored item titles. */
public final class ItemStateSymbols {
    public static final String EVENT_FUTURE = "\u25C7";
    public static final String EVENT_ACTIVE = "\u25C8";
    public static final String EVENT_PAST = "\u25C7\u0338";
    public static final String TASK_PENDING = "\u25CB";
    public static final String TASK_DONE = "\u2713\u20DD";
    public static final String TASK_OVERDUE = "\u25CB\u0338";

    private ItemStateSymbols() {}

    private static long localBoundary(long utc, boolean allDay, ZoneId zone) {
        return allDay ? Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli() : utc;
    }

    public static String event(long start, long end, boolean allDay, ZoneId zone, long now) {
        long first = localBoundary(start, allDay, zone);
        long last = localBoundary(end, allDay, zone);
        return now < first ? EVENT_FUTURE : now < last ? EVENT_ACTIVE : EVENT_PAST;
    }

    public static String task(boolean done, long due, boolean allDay, ZoneId zone, long now) {
        if (done) return TASK_DONE;
        return isTaskOverdue(false, due, allDay, zone, now) ? TASK_OVERDUE : TASK_PENDING;
    }

    /** All-day due values are UTC calendar dates, not local instants. */
    public static boolean isTaskOverdue(boolean done, long due, boolean allDay, ZoneId zone, long now) {
        if (done) return false;
        return allDay ? EventCountdown.targetDate(due, true, zone)
                .isBefore(EventCountdown.targetDate(now, false, zone)) : due < now;
    }

    /** Next transition for a timed item; all-day changes are handled at midnight. */
    public static long nextTimedTransition(long start, long end, boolean reminder, boolean done, long now) {
        if (reminder) return !done && start >= now && start < Long.MAX_VALUE
                ? start + 1 : Long.MAX_VALUE;
        if (start > now) return start;
        return end > now ? end : Long.MAX_VALUE;
    }

    public static String prefix(String symbol, CharSequence title) {
        return symbol + " " + (title == null ? "" : title);
    }

    public static String forEvent(Context context, Event event, long now) {
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        return event.taskId > 0
                ? (event.taskNoDeadline ? (event.taskCompleted ? TASK_DONE : TASK_PENDING)
                        : task(event.taskCompleted, event.taskDue, event.taskDueAllDay, zone, now))
                : event(event.startMillis, event.endMillis, event.allDay, zone, now);
    }
}
