package com.android.calendar.widget;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.android.calendar.HolidayManager;
import com.android.calendar.LocalizedDay;
import com.android.calendar.LunarUtils;
import com.android.calendar.theme.DynamicThemeKt;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import ws.xsoh.etar.R;

/** Shared widget header presentation, independent of widget refresh scheduling. */
final class WidgetHeaderUtils {
    private WidgetHeaderUtils() {}

    private static int background(Context context) {
        return ContextCompat.getColor(context, DynamicThemeKt.getWidgetBackgroundStyle(context));
    }

    static int secondaryColor(Context context) {
        return ColorUtils.blendARGB(DynamicThemeKt.getColor(context, "widget_text"),
                background(context), 0.28f);
    }

    static int dividerColor(Context context) {
        return ColorUtils.blendARGB(background(context),
                DynamicThemeKt.getColor(context, "widget_text"), 0.28f);
    }

    static String vertical(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (i > 0) text.append('\n');
            text.append(value.charAt(i));
        }
        return text.toString();
    }

    static CharSequence specialDates(Context context, LocalDate date) {
        boolean dark = ColorUtils.calculateLuminance(background(context)) < 0.5;
        int festivalColor = dark ? 0xffffb78b : 0xffa04419;
        int termColor = Build.VERSION.SDK_INT >= 31
                ? ContextCompat.getColor(context, dark ? android.R.color.system_accent1_200
                        : android.R.color.system_accent1_700)
                : ContextCompat.getColor(context, R.color.colorPrimaryDark);
        Map<String, Integer> labels = new LinkedHashMap<>();
        for (String name : LunarUtils.festivalLabels(context, date)) {
            labels.put(name, festivalColor);
        }
        String term = LunarUtils.solarTermLabel(context, date);
        if (!term.isEmpty()) labels.putIfAbsent(term, termColor);
        String marker = LocalizedDay.marker(context, date);
        if ("休".equals(marker)) {
            String name = HolidayManager.getInstance(context).getHolidayName(date);
            if (name != null && !name.isEmpty()) {
                labels.remove(name);
                labels.put(name + " 休", 0xffd84343);
            } else {
                labels.put(marker, 0xffd84343);
            }
        } else if ("班".equals(marker)) {
            labels.put("调休 班", 0xff39865a);
        }
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Map.Entry<String, Integer> label : labels.entrySet()) {
            if (text.length() > 0) text.append("  ");
            int start = text.length();
            text.append(label.getKey());
            text.setSpan(new ForegroundColorSpan(label.getValue()), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
