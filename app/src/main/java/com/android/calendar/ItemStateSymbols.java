package com.android.calendar;

import android.content.Context;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Shared presentation state identifiers; never changes stored item titles. */
public final class ItemStateSymbols {
    public static final String EVENT_FUTURE = "event_future";
    public static final String EVENT_ACTIVE = "event_active";
    public static final String EVENT_PAST = "event_past";
    public static final String TASK_PENDING = "task_pending";
    public static final String TASK_ACTIVE = "task_active";
    public static final String TASK_DONE = "task_done";
    public static final String TASK_OVERDUE = "task_overdue";

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
        return task(done, false, due, allDay, zone, now);
    }

    /** A synchronized IN-PROCESS task remains active until completed or overdue. */
    public static String task(boolean done, boolean inProgress, Long due, boolean allDay, ZoneId zone, long now) {
        if (done) return TASK_DONE;
        if (due != null && isTaskOverdue(false, due, allDay, zone, now)) return TASK_OVERDUE;
        return inProgress ? TASK_ACTIVE : TASK_PENDING;
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

    public static String forEvent(Context context, Event event, long now) {
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        return event.taskId > 0
                ? task(event.taskCompleted, event.taskInProgress, event.taskNoDeadline ? null : event.taskDue,
                        event.taskDueAllDay, zone, now)
                : event(event.startMillis, event.endMillis, event.allDay, zone, now);
    }
}
