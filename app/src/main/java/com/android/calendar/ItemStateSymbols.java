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
    public static final String REMINDER_PENDING = "\u25CB";
    public static final String REMINDER_DONE = "\u2713\u20DD";
    public static final String REMINDER_OVERDUE = "\u25CB\u0338";
    public static final String TASK_PLAIN = "\u25A1";
    public static final String TASK_DONE = "\u2611\uFE0E";
    public static final String TASK_PENDING = "\u25A1\u0338";

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

    public static String reminder(boolean done, long due, boolean allDay, ZoneId zone, long now) {
        if (done) return REMINDER_DONE;
        return isReminderOverdue(false, due, allDay, zone, now) ? REMINDER_OVERDUE : REMINDER_PENDING;
    }

    /** All-day due values are UTC calendar dates, not local instants. */
    public static boolean isReminderOverdue(boolean done, long due, boolean allDay, ZoneId zone, long now) {
        if (done) return false;
        return allDay ? EventCountdown.targetDate(due, true, zone)
                .isBefore(EventCountdown.targetDate(now, false, zone)) : due < now;
    }

    public static String task(boolean done) {
        return done ? TASK_DONE : TASK_PENDING;
    }

    public static String prefix(String symbol, CharSequence title) {
        return symbol + " " + (title == null ? "" : title);
    }

    public static String forEvent(Context context, Event event, long now) {
        ZoneId zone = ZoneId.of(Utils.getTimeZone(context, null));
        return event.taskId > 0
                ? reminder(event.taskCompleted, event.startMillis, event.allDay, zone, now)
                : event(event.startMillis, event.endMillis, event.allDay, zone, now);
    }
}
