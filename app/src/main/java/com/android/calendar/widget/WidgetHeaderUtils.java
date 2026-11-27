package com.android.calendar.widget;

import android.content.Context;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.android.calendar.LocalizationSettings;
import com.android.calendar.LunarUtils;
import com.android.calendar.theme.DynamicThemeKt;

import java.time.LocalDate;
import java.util.ArrayList;

import ws.xsoh.etar.R;

/** Shared widget header presentation, independent of widget refresh scheduling. */
final class WidgetHeaderUtils {
    private WidgetHeaderUtils() {}

    private static int background(Context context) {
        return ContextCompat.getColor(context, DynamicThemeKt.getWidgetBackgroundStyle(context));
    }

    static int accentColor(Context context) {
        boolean dark = ColorUtils.calculateLuminance(background(context)) < 0.5;
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.getColor(context, dark ? R.color.etar_primary_dark
                    : R.color.etar_primary_light);
        }
        return ContextCompat.getColor(context, R.color.colorPrimaryDark);
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

    static int festivalColor(Context context) {
        return ColorUtils.calculateLuminance(background(context)) < 0.5
                ? 0xffffb78b : 0xffa04419;
    }

    static int markerColor(String marker) {
        return "休".equals(marker) ? 0xffd84343 : 0xff39865a;
    }

    static final class SpecialDates {
        final String festival;
        final String term;
        final int days;

        SpecialDates(String festival, String term, int days) {
            this.festival = festival;
            this.term = term;
            this.days = days;
        }

        boolean isEmpty() {
            return festival.isEmpty() && term.isEmpty();
        }

        String names() {
            if (festival.isEmpty()) return term;
            return term.isEmpty() ? festival : festival + "、" + term;
        }
    }

    private static SpecialDates labels(Context context, LocalDate date, int days) {
        ArrayList<String> festivals = LunarUtils.festivalLabels(context, date);
        // The main calendar also includes modern public holidays. Only traditional
        // festivals and solar terms belong in the agenda widget's countdown.
        festivals.remove("元旦");
        festivals.remove("劳动节");
        festivals.remove("国庆节");
        String term = LunarUtils.solarTermLabel(context, date);
        if ("清明".equals(term) && festivals.contains("清明节")) term = "";
        return new SpecialDates(String.join("、", festivals), term, days);
    }

    static SpecialDates specialDates(Context context, LocalDate date) {
        if (!LocalizationSettings.festivals(context) && !LocalizationSettings.terms(context)) {
            return new SpecialDates("", "", 0);
        }
        // Reuse the existing authoritative lunar/solar-term table and its cache.
        // Search across year boundaries, with a bounded one-year lookahead.
        for (int days = 0; days <= 370; days++) {
            SpecialDates result = labels(context, date.plusDays(days), days);
            if (!result.isEmpty()) return result;
        }
        return new SpecialDates("", "", 0);
    }
}
