package com.android.calendar;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TodayIndicatorTest {
    @Test public void monthProgressUsesWallClockMinutes() {
        assertEquals(0f, TodayIndicator.dayFraction(0, 0), 0.00001f);
        assertEquals(0.25f, TodayIndicator.dayFraction(6, 0), 0.00001f);
        assertEquals(0.5f, TodayIndicator.dayFraction(12, 0), 0.00001f);
        assertEquals(0.75f, TodayIndicator.dayFraction(18, 0), 0.00001f);
        assertEquals(1439f / 1440f, TodayIndicator.dayFraction(23, 59), 0.00001f);
    }
}
