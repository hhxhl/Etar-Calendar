package com.android.calendar;

import android.content.Context;
import android.text.TextUtils;
import com.android.calendar.calendarcommon2.RecurrenceSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TreeSet;
import java.util.StringJoiner;

/** Calendar Provider stores recurrence dates as comma separated UTC date-times. */
public final class RDateSupport {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private RDateSupport() {}
    public static TreeSet<Long> dates(String value) {
        TreeSet<Long> result = new TreeSet<>();
        if (!TextUtils.isEmpty(value)) {
            RecurrenceSet set = new RecurrenceSet(null, value, null, null);
            if (set.rdates != null) for (long date : set.rdates) result.add(date);
        }
        return result;
    }
    public static String encode(Iterable<Long> dates) {
        StringJoiner result = new StringJoiner(",");
        for (long date : dates) result.add(UTC.format(Instant.ofEpochMilli(date)));
        return result.toString();
    }
    public static String includeStart(String value, long start) {
        TreeSet<Long> dates = dates(value);
        dates.add(start);
        return encode(dates);
    }
    public static String icsDates(String value, boolean allDay, Long exclude) {
        TreeSet<Long> dates = dates(value);
        if (exclude != null) dates.remove(exclude);
        StringJoiner result = new StringJoiner(",");
        for (long date : dates) {
            Instant instant = Instant.ofEpochMilli(date);
            result.add(allDay ? DATE.format(instant.atZone(ZoneOffset.UTC).toLocalDate()) : UTC.format(instant));
        }
        return result.toString();
    }
    public static long midnight(LocalDate date) { return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
    public static String isoDates(String value) {
        StringJoiner result = new StringJoiner("\n");
        for (long date : dates(value)) result.add(Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC).toLocalDate().toString());
        return result.toString();
    }
    public static String parseIsoDates(String text) {
        TreeSet<Long> result = new TreeSet<>();
        for (String line : text.trim().split("[,;\\s]+")) {
            LocalDate date = LocalDate.parse(line);
            if (date.getYear() < 1900 || date.getYear() > 2100) throw new IllegalArgumentException();
            result.add(midnight(date));
        }
        if (result.size() < 2 || result.size() > 1000) throw new IllegalArgumentException();
        return encode(result);
    }
    /** Uses the same authoritative date conversion as the calendar views. */
    public static String ruleFor(LocalDate date) {
        LunarDate lunar = LunarDate.of(date);
        return lunar.month + "," + lunar.day + "," + (lunar.leap ? 1 : 0);
    }

    /** month 1..12; day 1..30. A missing leap month falls back to the normal month. */
    public static String lunarDates(LocalDate from, String rule) {
        String[] parts = rule.split(",");
        if (parts.length != 3) throw new IllegalArgumentException();
        int month = Integer.parseInt(parts[0]), day = Integer.parseInt(parts[1]), leap = Integer.parseInt(parts[2]);
        if (month < 1 || month > 12 || day < 1 || day > 30 || leap < 0 || leap > 1)
            throw new IllegalArgumentException();
        TreeSet<Long> result = new TreeSet<>();
        // Start sufficiently early to see both ordinary and leap months of
        // the first lunar year, even when the requested date is in a leap month.
        LocalDate first = from.minusYears(1);
        for (int i = 0; LunarDate.of(first).day != 1; i++) {
            if (i >= 31) throw new IllegalArgumentException("Cannot locate lunar month");
            first = first.plusDays(1);
        }
        int currentYear = Integer.MIN_VALUE;
        LocalDate normal = null, preferred = null;
        for (int i = 0; i < 430 && result.size() < 30; i++) {
            LunarDate lunar = LunarDate.of(first);
            if (lunar.year != currentYear) {
                LocalDate chosen = preferred != null ? preferred : normal;
                if (chosen != null && !chosen.isBefore(from)) result.add(midnight(chosen));
                normal = preferred = null;
                currentYear = lunar.year;
                if (result.size() >= 30) break;
            }
            LocalDate next = first.plusDays(29);
            if (LunarDate.of(next).day != 1) next = next.plusDays(1);
            if (LunarDate.of(next).day != 1)
                throw new IllegalArgumentException("Inconsistent lunar month boundary");
            int length = (int) java.time.temporal.ChronoUnit.DAYS.between(first, next);
            if (lunar.month == month) {
                LocalDate candidate = first.plusDays(Math.min(day, length) - 1L);
                if (!lunar.leap) normal = candidate;
                else if (leap == 1) preferred = candidate;
            }
            first = next;
        }
        if (result.size() != 30) throw new IllegalArgumentException("Cannot generate lunar dates");
        return encode(result);
    }
    public static boolean hasExceptions(Context context, long eventId) {
        android.net.Uri uri = android.provider.CalendarContract.Events.CONTENT_URI;
        try (android.database.Cursor c = context.getContentResolver().query(uri,
                new String[]{"_id"}, "original_id=? AND deleted=0",
                new String[]{Long.toString(eventId)}, null)) {
            return c == null || c.moveToFirst();
        } catch (RuntimeException ex) {
            return true;
        }
    }
    public static String lunarRule(Context context, long id) {
        return id < 0 ? null : context.getSharedPreferences("lunar_recurrences", Context.MODE_PRIVATE).getString(Long.toString(id), null);
    }
    public static void saveRule(Context context, long id, String rule) {
        if (id < 0) return;
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("lunar_recurrences", Context.MODE_PRIVATE).edit();
        if (rule == null) edit.remove(Long.toString(id)); else edit.putString(Long.toString(id), rule);
        edit.apply();
    }
}
