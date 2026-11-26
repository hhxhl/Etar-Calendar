package com.android.calendar;

import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.CalendarContract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import ws.xsoh.etar.R;

/** Local display preference; never changes an event's title or recurrence. */
public final class EventCountdown {
    private static final String PREFS = "event_countdowns";

    private EventCountdown() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context, long eventId) {
        return eventId >= 0 && preferences(context).getBoolean(Long.toString(eventId), false);
    }

    private static void set(Context context, long eventId, boolean enabled) {
        if (eventId < 0) return;
        SharedPreferences.Editor editor = preferences(context).edit();
        if (enabled) editor.putBoolean(Long.toString(eventId), true);
        else editor.remove(Long.toString(eventId));
        editor.apply();
    }

    public static void setEnabled(Context context, long eventId, boolean enabled) {
        set(context, eventId, enabled);
        refresh(context);
    }

    private static void refresh(Context context) {
        Utils.sendUpdateWidgetIntent(context);
        context.getContentResolver().notifyChange(CalendarContract.Instances.CONTENT_URI, null, 0);
    }

    private static ZoneId calendarZone(Context context) {
        try {
            return ZoneId.of(Utils.getTimeZone(context, null));
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    public static long daysBetween(LocalDate today, LocalDate target) {
        return ChronoUnit.DAYS.between(today, target);
    }

    public static LocalDate targetDate(long start, boolean allDay, ZoneId zone) {
        return Instant.ofEpochMilli(start).atZone(allDay ? ZoneOffset.UTC : zone).toLocalDate();
    }

    public static String label(Context context, long eventId, long start, boolean allDay) {
        if (!enabled(context, eventId)) return "";
        ZoneId zone = calendarZone(context);
        return format(context, daysBetween(LocalDate.now(zone), targetDate(start, allDay, zone)));
    }

    /** Widget model already converts all-day instants to the calendar's local time zone. */
    public static String widgetLabel(Context context, long eventId, long localStart) {
        if (!enabled(context, eventId)) return "";
        ZoneId zone = calendarZone(context);
        return format(context, daysBetween(LocalDate.now(zone), targetDate(localStart, false, zone)));
    }

    private static String format(Context context, long days) {
        if (days == 0) return context.getString(R.string.countdown_today);
        int count = (int) Math.min(Integer.MAX_VALUE, Math.abs(days));
        return context.getResources().getQuantityString(days > 0
                ? R.plurals.countdown_remaining : R.plurals.countdown_elapsed, count, count);
    }

    /** Applied only after the calendar provider's delayed/undoable batch succeeds. */
    public static final class SavedEvent {
        private final long oldId;
        private final int insertIndex;
        private final boolean enabled;
        private final boolean moved;

        public SavedEvent(long oldId, int insertIndex, boolean enabled, boolean moved) {
            this.oldId = oldId;
            this.insertIndex = insertIndex;
            this.enabled = enabled;
            this.moved = moved;
        }

        public void complete(Context context, ContentProviderResult[] results) {
            if (results == null || results.length == 0) return;
            long id = oldId;
            if (insertIndex >= 0) {
                if (insertIndex >= results.length || results[insertIndex] == null
                        || results[insertIndex].uri == null) return;
                try {
                    id = ContentUris.parseId(results[insertIndex].uri);
                } catch (RuntimeException e) {
                    return;
                }
            }
            if (id < 0) return;
            set(context, id, enabled);
            if (moved && oldId != id) set(context, oldId, false);
            refresh(context);
        }
    }
}
