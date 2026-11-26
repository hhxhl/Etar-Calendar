/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *     Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.calendar;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.icu.util.Calendar;
import android.icu.util.GregorianCalendar;
import android.util.LruCache;
import android.text.TextUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Locale;

public class LunarUtils {

    // The flags used for get the lunar info.
    public static final int FORMAT_LUNAR_LONG = 0x00001;
    public static final int FORMAT_LUNAR_SHORT = 0x00002;
    public static final int FORMAT_ONE_FESTIVAL = 0x00004;
    public static final int FORMAT_MULTI_FESTIVAL = 0x00008;
    public static final int FORMAT_ANIMAL = 0x00010;

    private static final String INFO_SEPARATE = " ";
    private static final String MORE_FESTIVAL_SUFFIX = "*";

    private static final LruCache<String, LunarInfo> sLunarInfos = new LruCache<>(512);
    private static final String[] MONTHS = {"正", "二", "三", "四", "五", "六",
            "七", "八", "九", "十", "冬", "腊"};
    private static final String[] DAYS = {"初一", "初二", "初三", "初四", "初五",
            "初六", "初七", "初八", "初九", "初十", "十一", "十二", "十三", "十四",
            "十五", "十六", "十七", "十八", "十九", "二十", "廿一", "廿二", "廿三",
            "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};
    private static final String STEMS = "甲乙丙丁戊己庚辛壬癸";
    private static final String BRANCHES = "子丑寅卯辰巳午未申酉戌亥";
    private static final String[] ANIMALS = {"鼠", "牛", "虎", "兔", "龙", "蛇",
            "马", "羊", "猴", "鸡", "狗", "猪"};

    /**
     * If need show the lunar info now. As default, it will need shown if the current
     * language is zh-cn.
     */
    public static boolean showLunar(Context context) {
        return LocalizationSettings.lunar(context) || LocalizationSettings.festivals(context)
                || LocalizationSettings.terms(context);
    }

    /**
     * Used to clear the saved info.
     */
    public static void clearInfo() {
        sLunarInfos.evictAll();
    }

    /**
     * Used to get lunar information for a Gregorian date (zero-based month).
     * @param format Format which info need append to the result.
     *     The format {@link #FORMAT_LUNAR_LONG} and {@link #FORMAT_LUNAR_SHORT},
     *     {@link #FORMAT_ONE_FESTIVAL} and {@link #FORMAT_MULTI_FESTIVAL} could not
     *     selected at once.
     * @param showLunarBeforeFestival If the festival is exist for the date, if need append the
     *     lunar info before the festival info.
     * @param result [out] The result will be saved in this list as your given format.
     * @return The result as string for your given format.
     */
    public static String get(Context context, int year, int month, int day, int format,
            boolean showLunarBeforeFestival, ArrayList<String> result) {
        if (context == null || format < FORMAT_LUNAR_LONG) return null;

        LunarInfo info = getOrCalculate(year, month, day);
        if (info == null) return null;
        LunarInfo visible = new LunarInfo();
        if (LocalizationSettings.lunar(context)) {
            visible._label_long = info._label_long;
            visible._label_short = info._label_short;
            visible._animal = info._animal;
        }
        ArrayList<String> labels = new ArrayList<>();
        if (LocalizationSettings.festivals(context)) {
            labels.addAll(info.festivals);
        }
        if (LocalizationSettings.terms(context) && info.term != null
                && !(LocalizationSettings.festivals(context) && "清明".equals(info.term))) {
            labels.add(info.term);
        }
        if (labels.size() > 0) visible._festival1 = labels.get(0);
        if (labels.size() > 1) visible._festival2 = labels.get(1);
        if (labels.size() > 2) visible._festival3 = labels.get(2);
        if (labels.size() > 3) visible._festival4 = labels.get(3);
        return buildInfo(visible, format, showLunarBeforeFestival, result);
    }

    private static LunarInfo getOrCalculate(int year, int month, int day) {
        String key = getKey(year, month, day);
        LunarInfo cached = sLunarInfos.get(key);
        if (cached != null) return cached;

        LocalDate date;
        LunarDate lunar;
        try {
            date = LocalDate.of(year, month + 1, day);
            lunar = LunarDate.of(date);
        } catch (RuntimeException e) {
            return null;
        }
        int lunarMonth = lunar.month - 1;
        int lunarDay = lunar.day;
        String monthLabel = (lunar.leap ? "闰" : "") + MONTHS[lunarMonth] + "月";
        LunarInfo info = new LunarInfo();
        info.monthLabel = monthLabel;
        info._label_long = monthLabel + DAYS[lunarDay - 1];
        info._label_short = lunarDay == 1 ? monthLabel : DAYS[lunarDay - 1];
        int cycleYear = lunar.cycleIndex();
        info.yearLabel = "" + STEMS.charAt(Math.floorMod(cycleYear, 10))
                + BRANCHES.charAt(Math.floorMod(cycleYear, 12)) + "年";
        info._animal = ANIMALS[Math.floorMod(cycleYear, 12)];
        info.festivals = ChineseFestivals.get(date, lunar);
        info.term = SolarTerms.get(year, month, day);
        sLunarInfos.put(key, info);
        return info;
    }

    /** Use today's lunar month when visible; otherwise use the displayed date. */
    public static String header(Context context, LocalDate reference, LocalDate start,
            LocalDate end, LocalDate today, boolean monthOnly) {
        if (!LocalizationSettings.lunar(context)) return "";
        LocalDate date = reference;
        if (monthOnly) {
            if (!today.isBefore(start) && !today.isAfter(end)) {
                date = today;
            } else if (reference.isBefore(start) || reference.isAfter(end)) {
                date = start;
            }
        }
        LunarInfo info = getOrCalculate(date.getYear(), date.getMonthValue() - 1,
                date.getDayOfMonth());
        if (info == null) return "";
        return info.yearLabel + (monthOnly ? info.monthLabel : info._label_long);
    }

    private static String getKey(int year, int month, int day) {
        return year + "-" + month + "-" + day;
    }

    private static String buildInfo(LunarInfo info, int format, boolean showLunarBeforeFestival,
            ArrayList<String> list) {
        if (info == null || format < FORMAT_LUNAR_LONG) return null;

        StringBuilder result = new StringBuilder();

        if (showLunarBeforeFestival || TextUtils.isEmpty(info._festival1)
                || (format & (FORMAT_ONE_FESTIVAL | FORMAT_MULTI_FESTIVAL)) == 0) {
            // The format should not support long and short at one time.
            if ((format & FORMAT_LUNAR_LONG) == FORMAT_LUNAR_LONG) {
                appendInfo(result, info._label_long, list);
            } else if ((format & FORMAT_LUNAR_SHORT) == FORMAT_LUNAR_SHORT) {
                appendInfo(result, info._label_short, list);
            }
        }

        // The format should not support only one festival and multiple festivals.
        if ((format & FORMAT_ONE_FESTIVAL) == FORMAT_ONE_FESTIVAL) {
            String festival = info._festival1;
            if (!TextUtils.isEmpty(info._festival2)) {
                festival = festival + MORE_FESTIVAL_SUFFIX;
            }
            appendInfo(result, festival, list);
        } else if ((format & FORMAT_MULTI_FESTIVAL) == FORMAT_MULTI_FESTIVAL) {
            appendInfo(result, info._festival1, list);
            appendInfo(result, info._festival2, list);
            appendInfo(result, info._festival3, list);
            appendInfo(result, info._festival4, list);
        }

        if ((format & FORMAT_ANIMAL) == FORMAT_ANIMAL) {
            appendInfo(result, info._animal, list);
        }

        return result.toString();
    }

    private static void appendInfo(StringBuilder builder, String info, ArrayList<String> list) {
        if (builder == null || TextUtils.isEmpty(info)) return;

        String prefix = builder.length() > 0 ? INFO_SEPARATE : "";
        builder.append(prefix).append(info);

        if (list != null) list.add(info);
    }

    public static class LunarInfoLoader extends AsyncTaskLoader<Void> {
        private volatile int[] range;

        public LunarInfoLoader(Context context) {
            super(context);
        }

        public void load(int year, int month, int day) {
            load(year, month, day, year, month, day);
        }

        public void load(int year, int month) {
            GregorianCalendar calendar = new GregorianCalendar(year, month, 1);
            load(year, month, 1, year, month, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        }

        public void load(int fromYear, int fromMonth, int fromDay,
                int toYear, int toMonth, int toDay) {
            reset();
            range = new int[] {fromYear, fromMonth, fromDay, toYear, toMonth, toDay};
            startLoading();
            forceLoad();
        }

        @Override
        public Void loadInBackground() {
            int[] dates = range;
            if (dates == null) return null;
            GregorianCalendar current = new GregorianCalendar(dates[0], dates[1], dates[2]);
            GregorianCalendar end = new GregorianCalendar(dates[3], dates[4], dates[5]);
            while (!current.after(end) && !isLoadInBackgroundCanceled()) {
                getOrCalculate(current.get(Calendar.YEAR), current.get(Calendar.MONTH),
                        current.get(Calendar.DAY_OF_MONTH));
                current.add(Calendar.DAY_OF_MONTH, 1);
            }
            return null;
        }
    }

    private static class LunarInfo {
        String monthLabel;
        String yearLabel;
        ArrayList<String> festivals;
        String term;
        public String _label_long;
        public String _label_short;
        public String _animal;
        public String _festival1;
        public String _festival2;
        public String _festival3;
        public String _festival4;
    }
}
