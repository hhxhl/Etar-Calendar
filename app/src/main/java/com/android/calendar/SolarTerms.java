package com.android.calendar;

import android.util.LruCache;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;

/** Approximate apparent solar longitude, using the low-order Meeus solar equations.
 * Dates near midnight may differ from authoritative astronomical calendars.
 */
final class SolarTerms {
    private static final String[] NAMES = {"小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
            "清明", "谷雨", "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋",
            "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"};
    private static final LruCache<Integer, LocalDate[]> CACHE = new LruCache<>(8);
    private static final ZoneOffset CHINA = ZoneOffset.ofHours(8);
    private SolarTerms() {}

    static String get(int year, int month, int day) {
        if (year < 1900 || year > 2100) return null;
        LocalDate[] dates = CACHE.get(year);
        if (dates == null) {
            dates = calculate(year);
            CACHE.put(year, dates);
        }
        LocalDate date = LocalDate.of(year, month + 1, day);
        for (int i = 0; i < dates.length; i++) {
            if (date.equals(dates[i])) return NAMES[i];
        }
        return null;
    }

    private static LocalDate[] calculate(int year) {
        LocalDate[] dates = new LocalDate[24];
        double jan1 = LocalDate.of(year, 1, 1).toEpochDay() + 2440587.5;
        for (int i = 0; i < dates.length; i++) {
            double target = (285 + 15 * i) % 360;
            double jd = jan1 + 5 + i * 365.2422 / 24;
            for (int n = 0; n < 8; n++) {
                double error = (longitude(jd) - target + 540) % 360 - 180;
                jd -= error / 0.98564736;
            }
            // Approximate TT to UTC correction in seconds.
            double y = year - 2000;
            double deltaT = 62.92 + 0.32217 * y + 0.005589 * y * y;
            long seconds = Math.round((jd - 2440587.5) * 86400 - deltaT);
            dates[i] = Instant.ofEpochSecond(seconds).atOffset(CHINA).toLocalDate();
        }
        return dates;
    }

    private static double longitude(double jd) {
        double t = (jd - 2451545.0) / 36525;
        double l = 280.46646 + t * (36000.76983 + 0.0003032 * t);
        double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        double c = (1.914602 - t * (0.004817 + 0.000014 * t)) * sin(m)
                + (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m);
        double apparent = l + c - 0.00569 - 0.00478 * sin(125.04 - 1934.136 * t);
        return ((apparent % 360) + 360) % 360;
    }

    private static double sin(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }
}
