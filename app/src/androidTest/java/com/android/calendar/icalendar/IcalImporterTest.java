package com.android.calendar.icalendar;

import android.provider.CalendarContract.Events;
import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

public class IcalImporterTest {
    private VCalendar calendar(String... lines) {
        VCalendar calendar = new VCalendar();
        calendar.populateFromString(new ArrayList<>(Arrays.asList(lines)));
        return calendar;
    }

    private VEvent event(String... properties) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("BEGIN:VEVENT");
        lines.addAll(Arrays.asList(properties));
        lines.add("END:VEVENT");
        VEvent event = new VEvent();
        event.populateFromEntries(lines.listIterator());
        return event;
    }

    @Test public void multiDayAllDayUsesUtcAndExclusiveEnd() {
        IcalImporter.Parsed p = IcalImporter.parse(event("UID:test", "DTSTART;VALUE=DATE:20270101",
                "DTEND;VALUE=DATE:20270104", "SUMMARY:New Year"));
        assertEquals(1, p.values.getAsInteger(Events.ALL_DAY).intValue());
        assertEquals("UTC", p.values.getAsString(Events.EVENT_TIMEZONE));
        assertEquals(Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(),
                p.values.getAsLong(Events.DTSTART).longValue());
        assertEquals(3 * 86400000L, p.end - p.values.getAsLong(Events.DTSTART));
    }

    @Test public void recurrenceUsesDurationAndKeepsTimezone() {
        IcalImporter.Parsed p = IcalImporter.parse(event("DTSTART;TZID=Asia/Shanghai:20270101T090000",
                "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=5",
                "EXDATE;TZID=Asia/Shanghai:20270108T090000"));
        assertEquals("Asia/Shanghai", p.values.getAsString(Events.EVENT_TIMEZONE));
        assertEquals("P3600S", p.values.getAsString(Events.DURATION));
        assertNull(p.values.getAsLong(Events.DTEND));
        assertEquals("20270108T010000Z", p.values.getAsString(Events.EXDATE));
        assertFalse(p.values.containsKey(Events.UID_2445));
    }

    @Test public void nestedAlarmDoesNotReplaceEventDuration() {
        VEvent e = event("DTSTART:20270101T090000Z", "DURATION:PT1H", "SUMMARY:Meeting",
                "BEGIN:VALARM", "DURATION:PT5M", "DESCRIPTION:Reminder", "END:VALARM");
        IcalImporter.Parsed p = IcalImporter.parse(e);
        assertEquals(3600000L, p.end - p.values.getAsLong(Events.DTSTART));
        assertEquals("Meeting", p.values.getAsString(Events.TITLE));
        assertTrue(p.limited);
    }

    @Test public void multipleEventsAndDateParametersRoundTrip() {
        VCalendar c = calendar("BEGIN:VCALENDAR", "VERSION:2.0",
                "BEGIN:VEVENT", "DTSTART;VALUE=DATE:20270101", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20270102T090000Z", "END:VEVENT", "END:VCALENDAR");
        assertEquals(2, c.getAllEvents().size());
        assertTrue(c.getICalFormattedString().contains("DTSTART;VALUE=DATE:20270101"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDetachedException() {
        IcalImporter.parse(event("DTSTART:20270101T090000Z", "RECURRENCE-ID:20270101T080000Z"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedCalendar() {
        calendar("BEGIN:VCALENDAR", "BEGIN:VEVENT", "DTSTART:20270101T090000Z", "END:VEVENT");
    }

    @Test(expected = RuntimeException.class)
    public void rejectsInvalidDate() {
        IcalImporter.parse(event("DTSTART;VALUE=DATE:20270230"));
    }
}
