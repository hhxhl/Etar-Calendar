package com.android.calendar;

import org.junit.Test;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.Assert.assertEquals;

public class EventCountdownTest {
    @Test public void calendarDaysAcrossYearAndLeapDay() {
        assertEquals(1L, EventCountdown.daysBetween(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)));
        assertEquals(2L, EventCountdown.daysBetween(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1)));
        assertEquals(-1L, EventCountdown.daysBetween(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 1)));
        assertEquals(0L, EventCountdown.daysBetween(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1)));
    }

    @Test public void allDayUsesUtcDateButTimedEventsUseCalendarZone() {
        long start = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();
        ZoneId zone = ZoneId.of("America/Phoenix");
        assertEquals(LocalDate.of(2027, 1, 1), EventCountdown.targetDate(start, true, zone));
        assertEquals(LocalDate.of(2026, 12, 31), EventCountdown.targetDate(start, false, zone));
    }
}
