package com.android.calendar.widget;

import android.content.Context;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.view.View;
import android.widget.RemoteViews;
import java.time.ZoneId;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.android.calendar.LocalizationSettings;
import com.android.calendar.LunarUtils;
import com.android.calendar.LocalizedDay;
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
        return ContextCompat.getColor(context, dark ? R.color.etar_primary_dark
                : R.color.etar_primary_light);
    }

    static int secondaryColor(Context context) {
        return ColorUtils.blendARGB(DynamicThemeKt.getColor(context, "widget_text"),
                background(context), 0.28f);
    }

    static int dividerColor(Context context) {
        return ColorUtils.blendARGB(background(context),
                DynamicThemeKt.getColor(context, "widget_text"), 0.28f);
    }

    static void scheduleMidnight(Context context, PendingIntent intent, ZoneId zone) {
        long midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarm.set(AlarmManager.RTC, midnight, intent);
    }

    static void bindGridDate(Context context, RemoteViews views, int dayId, int labelId,
            LocalDate date, boolean today, boolean currentMonth) {
        views.setTextViewText(dayId, String.valueOf(date.getDayOfMonth()));
        String label = LocalizedDay.combined(context, date);
        views.setTextViewText(labelId, label);
        views.setViewVisibility(labelId, label.isEmpty() ? View.GONE : View.VISIBLE);
        WidgetUtils.setThemeText(context, views, labelId, R.color.widget_live_text,
                DynamicThemeKt.getColor(context, "month_day_number"));
        if (today) {
            WidgetUtils.setThemeText(context, views, dayId, R.color.widget_live_on_primary,
                    DynamicThemeKt.getColor(context, "month_widget_today_text"));
            views.setInt(dayId, "setBackgroundResource", WidgetUtils.usesLiveTheme(context)
                    ? R.drawable.widget_today_live : DynamicThemeKt.getDrawableId(context, "month_widget_today_circle"));
        } else {
            WidgetUtils.setThemeText(context, views, dayId,
                    currentMonth ? R.color.widget_live_text : R.color.widget_live_secondary,
                    DynamicThemeKt.getColor(context, currentMonth ? "month_day_number" : "month_day_number_other"));
            views.setInt(dayId, "setBackgroundResource", 0);
        }
    }

    static void bindGridBorder(Context context, RemoteViews views, int id, boolean visible) {
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        if (visible) WidgetUtils.setThemeLine(context, views, id, R.color.widget_live_grid,
                DynamicThemeKt.getColor(context, "month_grid_lines"));
    }

    static void bindOverflow(Context context, RemoteViews views, int id, int extra) {
        views.setViewVisibility(id, extra > 0 ? View.VISIBLE : View.GONE);
        if (extra > 0) {
            views.setTextViewText(id, context.getString(R.string.widget_event_overflow, extra));
            WidgetUtils.setThemeText(context, views, id, R.color.widget_live_text,
                    DynamicThemeKt.getColor(context, "month_day_number"));
        }
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
        return LocalizedDay.festivalColor(background(context));
    }

    static int markerColor(String marker) {
        return LocalizedDay.markerColor(marker);
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
