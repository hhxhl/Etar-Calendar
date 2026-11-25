package com.android.calendar;

import android.content.Context;
import java.time.LocalDate;

public final class LocalizedDay {
    private LocalizedDay() {}
    public static boolean enabled(Context c) {
        return LunarUtils.showLunar(c) || LocalizationSettings.holidays(c);
    }
    public static String label(Context c, LocalDate date) {
        if (!LunarUtils.showLunar(c)) return "";
        String text = LunarUtils.get(c, date.getYear(), date.getMonthValue() - 1,
                date.getDayOfMonth(), LunarUtils.FORMAT_LUNAR_SHORT | LunarUtils.FORMAT_ONE_FESTIVAL,
                false, null);
        return text == null ? "" : text;
    }
    public static String marker(Context c, LocalDate date) {
        if (!LocalizationSettings.holidays(c)) return "";
        switch (HolidayManager.getInstance(c).getStatus(date)) {
            case HOLIDAY: return "休";
            case WORKDAY: return "班";
            default: return "";
        }
    }
    public static String combined(Context c, LocalDate date) {
        String label = label(c, date), marker = marker(c, date);
        return marker.isEmpty() ? label : label.isEmpty() ? marker : marker + " " + label;
    }
    public static LocalDate fromJulian(int day) {
        return LocalDate.ofEpochDay(day - 2440588L);
    }
}
