package com.android.calendar;

import org.junit.Test;
import java.time.LocalDate;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class RDateSupportTest {
    @Test public void providerAndIcsRoundTrip() {
        long first = RDateSupport.midnight(LocalDate.of(2027, 9, 15));
        String set = RDateSupport.includeStart("20281003T000000Z,20290922T000000Z", first);
        assertEquals("20270915T000000Z,20281003T000000Z,20290922T000000Z", set);
        assertEquals("20281003,20290922", RDateSupport.icsDates(set, true, first));
    }
    @Test public void lunarMidAutumnThirtyOccurrences() {
        TreeSet<Long> dates = RDateSupport.dates(RDateSupport.lunarDates(LocalDate.of(2027, 9, 15), "8,15,0"));
        assertEquals(30, dates.size());
        assertTrue(dates.contains(RDateSupport.midnight(LocalDate.of(2027, 9, 15))));
        assertTrue(dates.contains(RDateSupport.midnight(LocalDate.of(2028, 10, 3))));
        assertTrue(dates.contains(RDateSupport.midnight(LocalDate.of(2029, 9, 22))));
    }
}
