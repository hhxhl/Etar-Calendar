package com.android.calendar;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import java.util.ArrayList;

final class ChineseFestivals {
    private ChineseFestivals() {}

    static ArrayList<String> get(int year, int month, int day, ChineseCalendar lunar) {
        ArrayList<String> result = new ArrayList<>();
        int lm = lunar.get(Calendar.MONTH) + 1;
        int ld = lunar.get(Calendar.DAY_OF_MONTH);
        if (lunar.get(ChineseCalendar.IS_LEAP_MONTH) == 0) {
            switch (lm * 100 + ld) {
                case 101: result.add("春节"); break;
                case 115: result.add("元宵节"); break;
                case 202: result.add("龙抬头"); break;
                case 505: result.add("端午节"); break;
                case 707: result.add("七夕"); break;
                case 715: result.add("中元节"); break;
                case 815: result.add("中秋节"); break;
                case 909: result.add("重阳节"); break;
                case 1001: result.add("寒衣节"); break;
                case 1015: result.add("下元节"); break;
                case 1208: result.add("腊八节"); break;
                default: break;
            }
        }
        if (lm == 12) {
            ChineseCalendar next = (ChineseCalendar) lunar.clone();
            next.add(Calendar.DAY_OF_MONTH, 1);
            if (next.get(Calendar.MONTH) == 0 && next.get(Calendar.DAY_OF_MONTH) == 1
                    && next.get(ChineseCalendar.IS_LEAP_MONTH) == 0) {
                result.add("除夕");
            }
        }
        String term = SolarTerms.get(year, month, day);
        if ("清明".equals(term)) result.add("清明节");
        if (month == 0 && day == 1) result.add("元旦");
        if (month == 4 && day == 1) result.add("劳动节");
        if (month == 9 && day == 1) result.add("国庆节");
        return result;
    }
}
