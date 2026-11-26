package com.android.calendar;

import org.junit.Test;
import java.time.LocalDate;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class LunisolarDataTest {
    private void check(String iso, int year, int month, int day, boolean leap) {
        LunarDate date = LunarDate.of(LocalDate.parse(iso));
        assertEquals(iso, year, date.year);
        assertEquals(iso, month, date.month);
        assertEquals(iso, day, date.day);
        assertEquals(iso, leap, date.leap);
    }
    @Test public void authoritativeDatesAndBoundaries() {
        check("1901-01-01", 1900, 11, 11, false);
        check("2025-07-25", 2025, 6, 1, true);
        check("2026-02-17", 2026, 1, 1, false);
        check("2027-02-05", 2026, 12, 29, false);
        check("2027-02-06", 2027, 1, 1, false);
        check("2030-02-02", 2029, 12, 30, false);
        check("2030-02-03", 2030, 1, 1, false);
        check("2033-12-22", 2033, 11, 1, true);
        check("2100-12-31", 2100, 12, 1, false);
    }
    @Test public void festivalsAndTerms() {
        LocalDate eve = LocalDate.of(2027, 2, 5);
        assertTrue(ChineseFestivals.get(eve, LunarDate.of(eve)).contains("除夕"));
        LocalDate spring = eve.plusDays(1);
        assertTrue(ChineseFestivals.get(spring, LunarDate.of(spring)).contains("春节"));
        assertEquals("小暑", SolarTerms.get(2045, 6, 7));
        assertEquals("惊蛰", SolarTerms.get(2047, 2, 6));
        assertEquals("春分", SolarTerms.get(2051, 2, 20));
    }
    @Test public void recurrenceSharesAuthoritativeDates() {
        TreeSet<Long> dates = RDateSupport.dates(RDateSupport.lunarDates(LocalDate.of(2026, 1, 1), "1,1,0"));
        assertEquals(30, dates.size());
        assertTrue(dates.contains(RDateSupport.midnight(LocalDate.of(2027, 2, 6))));
        assertTrue(dates.contains(RDateSupport.midnight(LocalDate.of(2030, 2, 3))));
        for (long value : dates) {
            LocalDate date = java.time.Instant.ofEpochMilli(value).atOffset(java.time.ZoneOffset.UTC).toLocalDate();
            assertEquals("1,1,0", RDateSupport.ruleFor(date));
        }
    }
    @Test public void tableEveryDayIsConsecutive() {
        LunarDate previous = null;
        int days = 0;
        for (LocalDate date = LunisolarData.FIRST; !date.isAfter(LunisolarData.LAST); date = date.plusDays(1)) {
            LunarDate current = LunisolarData.lookup(date);
            assertNotNull(current);
            assertTrue(current.day >= 1 && current.day <= 30);
            if (previous != null) {
                if (current.day == 1) assertTrue(previous.day == 29 || previous.day == 30);
                else {
                    assertEquals(previous.day + 1, current.day);
                    assertEquals(previous.month, current.month);
                    assertEquals(previous.year, current.year);
                    assertEquals(previous.leap, current.leap);
                }
            }
            previous = current;
            days++;
        }
        assertEquals(73049, days);
    }
}
