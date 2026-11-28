package com.android.calendar;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemStateSymbolsTest {
    private static long millis(String value) {
        return Instant.parse(value).toEpochMilli();
    }

    @Test public void timedTaskChangesStateAfterDueInstant() {
        long due = millis("2027-01-01T00:00:00Z");
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        assertFalse(ItemStateSymbols.isTaskOverdue(false, due, false, zone, due));
        assertTrue(ItemStateSymbols.isTaskOverdue(false, due, false, zone, due + 1));
        assertFalse(ItemStateSymbols.isTaskOverdue(true, due, false, zone, due + 1));
        assertEquals(ItemStateSymbols.TASK_OVERDUE,
                ItemStateSymbols.task(false, due, false, zone, due + 1));
    }

    @Test public void allDayTaskUsesCalendarDateAcrossTimeZones() {
        long due = millis("2027-01-01T00:00:00Z");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZoneId phoenix = ZoneId.of("America/Phoenix");
        long now = millis("2027-01-01T15:00:00Z");
        assertTrue(ItemStateSymbols.isTaskOverdue(false, due, true, tokyo, now));
        assertFalse(ItemStateSymbols.isTaskOverdue(false, due, true, phoenix, now));
        assertFalse(ItemStateSymbols.isTaskOverdue(true, due, true, tokyo, now));
    }
}
