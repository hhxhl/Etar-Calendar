package com.android.calendar;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemStateSymbolsTest {
    private static long millis(String value) {
        return Instant.parse(value).toEpochMilli();
    }

    @Test public void timedReminderChangesStateAfterDueInstant() {
        long due = millis("2027-01-01T00:00:00Z");
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        assertFalse(ItemStateSymbols.isReminderOverdue(false, due, false, zone, due));
        assertTrue(ItemStateSymbols.isReminderOverdue(false, due, false, zone, due + 1));
        assertFalse(ItemStateSymbols.isReminderOverdue(true, due, false, zone, due + 1));
        assertEquals(ItemStateSymbols.REMINDER_OVERDUE,
                ItemStateSymbols.reminder(false, due, false, zone, due + 1));
    }

    @Test public void allDayReminderUsesCalendarDateAcrossTimeZones() {
        long due = millis("2027-01-01T00:00:00Z");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZoneId phoenix = ZoneId.of("America/Phoenix");
        long now = millis("2027-01-01T15:00:00Z");
        assertTrue(ItemStateSymbols.isReminderOverdue(false, due, true, tokyo, now));
        assertFalse(ItemStateSymbols.isReminderOverdue(false, due, true, phoenix, now));
        assertFalse(ItemStateSymbols.isReminderOverdue(true, due, true, tokyo, now));
    }
}
