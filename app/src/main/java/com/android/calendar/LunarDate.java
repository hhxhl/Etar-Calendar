package com.android.calendar;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.icu.util.TimeZone;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/** One date-conversion entry point shared by display, festivals and recurrence generation. */
final class LunarDate {
    final int year, month, day;
    final boolean leap;
    LunarDate(int year, int month, int day, boolean leap) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.leap = leap;
    }
    static LunarDate of(LocalDate date) {
        LunarDate table = LunisolarData.lookup(date);
        if (table != null) return table;
        ChineseCalendar cal = new ChineseCalendar(TimeZone.getTimeZone("GMT+08:00"), Locale.CHINA);
        cal.setTimeInMillis(date.atTime(12, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli());
        int month = cal.get(Calendar.MONTH) + 1;
        // A Chinese lunar year starts in January or February of its Gregorian year.
        int year = date.getYear() - (date.getMonthValue() <= 2 && month >= 11 ? 1 : 0);
        return new LunarDate(year, month, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(ChineseCalendar.IS_LEAP_MONTH) != 0);
    }
    int cycleIndex() { return Math.floorMod(year - 1984, 60); }
}
