package com.android.calendar;

import android.content.Context;
import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.icu.util.TimeZone;
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
    private static ChineseCalendar chinese(LocalDate date) {
        ChineseCalendar cal = new ChineseCalendar(TimeZone.getTimeZone("Asia/Shanghai"), Locale.CHINA);
        cal.setTimeInMillis(date.atTime(12, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli());
        return cal;
    }
    /** month 1..12; day 1..30. A missing leap month falls back to the normal month. */
    public static String ruleFor(LocalDate date) {
        ChineseCalendar cal = chinese(date);
        return (cal.get(Calendar.MONTH) + 1) + "," + cal.get(Calendar.DAY_OF_MONTH) + "," + cal.get(ChineseCalendar.IS_LEAP_MONTH);
    }
    public static String lunarDates(LocalDate from, String rule) {
        String[] parts = rule.split(",");
        if (parts.length != 3) throw new IllegalArgumentException();
        int month = Integer.parseInt(parts[0]) - 1, day = Integer.parseInt(parts[1]), leap = Integer.parseInt(parts[2]);
        if (month < 0 || month > 11 || day < 1 || day > 30 || leap < 0 || leap > 1) throw new IllegalArgumentException();
        TreeSet<Long> result = new TreeSet<>();
        // Iterate actual lunar months, not Gregorian years. This avoids ICU lenient
        // normalization of missing leap months and days in short months.
        ChineseCalendar cal = chinese(from.minusMonths(2));
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int currentYear = Integer.MIN_VALUE;
        LocalDate normal = null, preferred = null;
        for (int i = 0; i < 410 && result.size() < 30; i++) {
            int year = cal.get(Calendar.EXTENDED_YEAR);
            if (year != currentYear) {
                LocalDate chosen = preferred != null ? preferred : normal;
                if (chosen != null && !chosen.isBefore(from)) result.add(midnight(chosen));
                normal = preferred = null;
                currentYear = year;
                if (result.size() >= 30) break;
            }
            if (cal.get(Calendar.MONTH) == month) {
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                LocalDate first = Instant.ofEpochMilli(cal.getTimeInMillis()).atOffset(ZoneOffset.ofHours(8)).toLocalDate();
                LocalDate candidate = first.plusDays(Math.min(day, days) - 1L);
                if (cal.get(ChineseCalendar.IS_LEAP_MONTH) == 0) normal = candidate;
                else if (leap == 1) preferred = candidate;
            }
            cal.add(Calendar.MONTH, 1);
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
