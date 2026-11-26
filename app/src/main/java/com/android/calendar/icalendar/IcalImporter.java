package com.android.calendar.icalendar;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps VEVENTs to Calendar Provider rows without changing the provider schema. */
public final class IcalImporter {
    private IcalImporter() {}
    private static final long DAY = 86400000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile(
            "P(?:(\\d+)W|(?:(\\d+)D)?(?:T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)");

    public static final class Parsed {
        public final ContentValues values;
        public final long end;
        public final boolean limited;
        Parsed(ContentValues values, long end, boolean limited) {
            this.values = values;
            this.end = end;
            this.limited = limited;
        }
    }

    private static String param(String params, String key) {
        if (params == null) return null;
        for (String part : params.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && key.equalsIgnoreCase(part.substring(0, i))) {
                return part.substring(i + 1).replace("\"", "");
            }
        }
        return null;
    }

    private static boolean allDay(String value, String params) {
        return "DATE".equalsIgnoreCase(param(params, "VALUE"))
                || (value != null && value.matches("[0-9]{8}"));
    }

    private static ZoneId zone(String value, String params, ZoneId fallback) {
        if (value != null && value.endsWith("Z")) return ZoneOffset.UTC;
        String id = param(params, "TZID");
        if (id == null) return fallback;
        try {
            return ZoneId.of(id);
        } catch (DateTimeException ex) {
            String mapped = android.icu.util.TimeZone.getIDForWindowsID(id, "001");
            if (mapped == null) throw ex;
            return ZoneId.of(mapped);
        }
    }

    private static ZonedDateTime dateTime(String value, boolean date, ZoneId zone) {
        if (value == null) throw new IllegalArgumentException("Missing date");
        if (date) return LocalDate.parse(value, DATE).atStartOfDay(ZoneOffset.UTC);
        String local = value.endsWith("Z") ? value.substring(0, value.length() - 1) : value;
        return LocalDateTime.parse(local, TIME).atZone(zone);
    }

    private static long n(Matcher m, int i) {
        return m.group(i) == null ? 0 : Long.parseLong(m.group(i));
    }

    static ZonedDateTime addDuration(ZonedDateTime start, String duration) {
        Matcher m = DURATION.matcher(duration);
        if (!m.matches() || duration.equals("P") || duration.equals("PT"))
            throw new IllegalArgumentException("Invalid duration");
        return start.plusWeeks(n(m,1)).plusDays(n(m,2)).plusHours(n(m,3))
                .plusMinutes(n(m,4)).plusSeconds(n(m,5));
    }

    private static String recurrenceDates(VEvent event, String key, boolean date, ZoneId fallback) {
        String value = event.getProperty(key);
        if (value == null || value.isEmpty()) return null;
        String params = event.getPropertyParameters(key);
        StringJoiner result = new StringJoiner(",");
        for (String part : value.split(",", -1)) {
            if (allDay(part, params) != date) throw new IllegalArgumentException("Mixed date types");
            ZonedDateTime time = dateTime(part, date, zone(part, params, fallback));
            result.add(date ? DATE.format(time) : TIME.format(time.withZoneSameInstant(ZoneOffset.UTC)) + "Z");
        }
        return result.toString();
    }

    private static String property(VEvent event, String key) {
        return IcalendarUtils.uncleanseString(event.getProperty(key));
    }

    public static Parsed parse(VEvent event) {
        if (event.hasUnsupportedComponent || event.getProperty("RECURRENCE-ID") != null
                || "CANCELLED".equalsIgnoreCase(event.getProperty("STATUS"))
                || event.getProperty("EXRULE") != null)
            throw new IllegalArgumentException("Unsupported recurrence exception");
        String startValue = event.getProperty(VEvent.DTSTART);
        String startParams = event.getPropertyParameters(VEvent.DTSTART);
        boolean date = allDay(startValue, startParams);
        ZoneId startZone = date ? ZoneOffset.UTC : zone(startValue, startParams, ZoneId.systemDefault());
        ZonedDateTime start = dateTime(startValue, date, startZone);
        String endValue = event.getProperty(VEvent.DTEND);
        String endParams = event.getPropertyParameters(VEvent.DTEND);
        String duration = event.getProperty(VEvent.DURATION);
        if (endValue != null && duration != null) throw new IllegalArgumentException("Conflicting end");
        ZonedDateTime end;
        if (endValue != null) {
            if (date != allDay(endValue, endParams)) throw new IllegalArgumentException("Mixed date types");
            end = dateTime(endValue, date, date ? ZoneOffset.UTC : zone(endValue, endParams, startZone));
        } else if (duration != null) {
            end = addDuration(start, duration);
        } else {
            end = date ? start.plusDays(1) : start;
        }
        long startMs = start.toInstant().toEpochMilli(), endMs = end.toInstant().toEpochMilli();
        long span = Math.subtractExact(endMs, startMs);
        if (span < 0 || (date && (span < DAY || span % DAY != 0)))
            throw new IllegalArgumentException("Invalid event end");

        ContentValues values = new ContentValues();
        values.put(Events.TITLE, property(event, VEvent.SUMMARY));
        values.put(Events.DESCRIPTION, property(event, VEvent.DESCRIPTION));
        values.put(Events.EVENT_LOCATION, property(event, VEvent.LOCATION));
        values.put(Events.DTSTART, startMs);
        values.put(Events.ALL_DAY, date ? 1 : 0);
        values.put(Events.EVENT_TIMEZONE, date ? "UTC" : startZone.getId().equals("Z") ? "UTC" : startZone.getId());
        String uid = event.getProperty(VEvent.UID);
        if (uid != null && !uid.isEmpty()) values.put(Events.UID_2445, uid);
        String rule = event.getProperty(VEvent.RRULE);
        String rdate = recurrenceDates(event, "RDATE", date, startZone);
        String exdate = recurrenceDates(event, "EXDATE", date, startZone);
        if (rule != null && !rule.isEmpty()) {
            rule = rule.replace("\\;", ";");
            new com.android.calendar.calendarcommon2.EventRecurrence().parse(rule);
            values.put(Events.RRULE, rule);
        }
        if (rdate != null) values.put(Events.RDATE, rdate);
        if (exdate != null) values.put(Events.EXDATE, exdate);
        if (values.containsKey(Events.RRULE) || rdate != null) {
            values.put(Events.DURATION, date ? "P" + span / DAY + "D" : "P" + span / 1000 + "S");
            values.putNull(Events.DTEND);
        } else {
            values.put(Events.DTEND, endMs);
        }
        return new Parsed(values, endMs, event.hasAlarm || !event.mAttendees.isEmpty()
                || event.getProperty(VEvent.ORGANIZER) != null || event.getProperty(VEvent.URL) != null
                || event.getProperty("ATTACH") != null || event.getProperty("CATEGORIES") != null
                || event.getProperty("CLASS") != null || event.getProperty("TRANSP") != null);
    }

    private static final String[] KEY_COLUMNS = {Events.TITLE, Events.DTSTART, Events.DTEND,
            Events.ALL_DAY, Events.RRULE, Events.DURATION, Events.RDATE, Events.EXDATE,
            Events.DESCRIPTION, Events.EVENT_LOCATION, Events.EVENT_TIMEZONE};

    private static List<String> key(ContentValues values) {
        List<String> result = new ArrayList<>();
        for (String col : KEY_COLUMNS) {
            String value = values.getAsString(col);
            result.add(value == null ? "" : value);
        }
        return result;
    }

    public static final class Result {
        public int inserted, duplicates, failed;
    }

    public static Result insert(ContentResolver resolver, long calendarId, List<Parsed> events) {
        Result result = new Result();
        // Recheck access just before writing, in case the calendar was removed or changed.
        try (Cursor calendar = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars._ID + "=? AND " + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{Long.toString(calendarId), Integer.toString(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
            if (calendar == null || !calendar.moveToFirst()) throw new IllegalStateException("Calendar is not writable");
        }
        Set<String> uids = new HashSet<>();
        Set<List<String>> keys = new HashSet<>(), noUidKeys = new HashSet<>();
        String[] projection = Arrays.copyOf(KEY_COLUMNS, KEY_COLUMNS.length + 1);
        projection[KEY_COLUMNS.length] = Events.UID_2445;
        try (Cursor cursor = resolver.query(Events.CONTENT_URI, projection,
                Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0",
                new String[]{Long.toString(calendarId)}, null)) {
            if (cursor == null) throw new IllegalStateException("Cannot query events");
            while (cursor.moveToNext()) {
                ContentValues row = new ContentValues();
                for (int i = 0; i < KEY_COLUMNS.length; i++) row.put(KEY_COLUMNS[i], cursor.getString(i));
                String uid = cursor.getString(KEY_COLUMNS.length);
                List<String> k = key(row);
                keys.add(k);
                if (uid != null && !uid.isEmpty()) uids.add(uid);
                else noUidKeys.add(k);
            }
        }
        ArrayList<ContentProviderOperation> batch = new ArrayList<>();
        for (Parsed event : events) {
            ContentValues values = new ContentValues(event.values);
            String uid = values.getAsString(Events.UID_2445);
            List<String> k = key(values);
            boolean hasUid = uid != null && !uid.isEmpty();
            if (hasUid ? uids.contains(uid) || noUidKeys.contains(k) : keys.contains(k)) {
                result.duplicates++;
                continue;
            }
            values.put(Events.CALENDAR_ID, calendarId);
            batch.add(ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build());
            if (hasUid) uids.add(uid); else noUidKeys.add(k);
            keys.add(k);
            if (batch.size() == 50) {
                if (!flush(resolver, batch, result)) {
                    result.failed = events.size() - result.inserted - result.duplicates;
                    return result;
                }
            }
        }
        flush(resolver, batch, result);
        return result;
    }

    private static boolean flush(ContentResolver resolver, ArrayList<ContentProviderOperation> batch, Result result) {
        if (batch.isEmpty()) return true;
        int count = batch.size();
        try {
            resolver.applyBatch(CalendarContract.AUTHORITY, batch);
            result.inserted += count;
            return true;
        } catch (Exception e) {
            result.failed += count;
            return false;
        } finally {
            batch.clear();
        }
    }
}
