/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar.widget;

import static android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY;
import static android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME;
import static android.provider.CalendarContract.EXTRA_EVENT_END_TIME;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.calendar.Utils;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.theme.ThemeUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ws.xsoh.etar.R;

/**
 * Simple widget to show next upcoming calendar event.
 */
public class CalendarAppWidgetProvider extends AppWidgetProvider {
    static final String TAG = "CalendarAppWidgetProvider";
    static final boolean LOGD = false;

    private static boolean sWidgetChecked = false;
    private static boolean sWidgetSupported = false;

    // Process-local state: after process death, send one complete update to recover
    // the host layout before relying on partial updates.
    private static final Map<Integer, List<?>> sHeaders = new HashMap<>();
    private static final Map<Integer, List<?>> sBackgrounds = new HashMap<>();

    // TODO Move these to Calendar.java
    static final String EXTRA_EVENT_IDS = "com.android.calendar.EXTRA_EVENT_IDS";

    /**
     * Build {@link ComponentName} describing this specific
     * {@link AppWidgetProvider}
     */
    static ComponentName getComponentName(Context context) {
        return new ComponentName(context, CalendarAppWidgetProvider.class);
    }

    /**
     * Build the {@link PendingIntent} used to trigger an update of all calendar
     * widgets. Uses {@link Utils#getWidgetScheduledUpdateAction(Context)} to
     * directly target all widgets instead of using
     * {@link AppWidgetManager#EXTRA_APPWIDGET_IDS}.
     *
     * @param context Context to use when building broadcast.
     */
    static PendingIntent getUpdateIntent(Context context) {
        Intent intent = new Intent(Utils.getWidgetScheduledUpdateAction(context));
        intent.setClass(context, CalendarAppWidgetService.CalendarFactory.class);
        intent.setDataAndType(CalendarContract.CONTENT_URI, Utils.APPWIDGET_DATA_TYPE);
        return PendingIntent.getBroadcast(context, 0 /* no requestCode */, intent,
                PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Build a {@link PendingIntent} to launch the Calendar app. This should be used
     * in combination with {@link RemoteViews#setPendingIntentTemplate(int, PendingIntent)}.
     */
    static PendingIntent getLaunchPendingIntentTemplate(Context context) {
        Intent launchIntent = new Intent(context, WidgetClickActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return PendingIntent.getActivity(context, 0 /* no requestCode */, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Build an {@link Intent} available as FillInIntent to launch the Calendar app.
     * This should be used in combination with
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     * If the go to time is 0, then calendar will be launched without a starting time.
     *
     * @param goToTime time that calendar should take the user to, or 0 to
     *                 indicate no specific start time.
     */
    static Intent getLaunchFillInIntent(Context context, long id, long start, long end,
                                        boolean allDay) {
        final Intent fillInIntent = new Intent();
        if (id < -1) {
            return WidgetClickActivity.itemIntent(-id - 1, start);
        }
        String dataString = "content://com.android.calendar/events";
        if (id != 0) {
            fillInIntent.putExtra(Utils.INTENT_KEY_DETAIL_VIEW, true);
            fillInIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                    Intent.FLAG_ACTIVITY_TASK_ON_HOME);

            dataString += "/" + id;
        }
        Uri data = Uri.parse(dataString);
        fillInIntent.setData(data);
        fillInIntent.putExtra(EXTRA_EVENT_BEGIN_TIME, start);
        fillInIntent.putExtra(EXTRA_EVENT_END_TIME, end);
        fillInIntent.putExtra(EXTRA_EVENT_ALL_DAY, allDay);

        return fillInIntent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        // Handle calendar-specific updates ourselves because they might be
        // coming in without extras, which AppWidgetProvider then blocks.
        final String action = intent.getAction();
        if (LOGD)
            Log.d(TAG, "AppWidgetProvider got the intent: " + intent);
        if (Utils.getWidgetUpdateAction(context).equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            if (!isWidgetSupported(context)) {
                return;
            }

            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            performUpdate(context, appWidgetManager,
                    appWidgetManager.getAppWidgetIds(getComponentName(context)),
                    null /* no eventIds */, false);
        } else {
            super.onReceive(context, intent);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDisabled(Context context) {
        // Unsubscribe from all AlarmManager updates
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingUpdate = getUpdateIntent(context);
        am.cancel(pendingUpdate);
        am.cancel(getHeaderUpdateIntent(context));
        sHeaders.clear();
        sBackgrounds.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        for (int appWidgetId : appWidgetIds) {
            WidgetUtils.INSTANCE.deleteWidgetCalendarIds(context, appWidgetId);
            sHeaders.remove(appWidgetId);
            sBackgrounds.remove(appWidgetId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        performUpdate(context, appWidgetManager, appWidgetIds, null /* no eventIds */, true);
    }

    /**
     * Process and push out an update for the given appWidgetIds. This call
     * actually fires an intent to start {@link CalendarAppWidgetService} as a
     * background service which handles the actual update, to prevent ANR'ing
     * during database queries.
     *
     * @param context Context to use when starting {@link CalendarAppWidgetService}.
     * @param appWidgetIds List of specific appWidgetIds to update, or null for
     *            all.
     * @param changedEventIds Specific events known to be changed. If present,
     *            we use it to decide if an update is necessary.
     */
    private void performUpdate(Context context,
            AppWidgetManager appWidgetManager, int[] appWidgetIds,
            long[] changedEventIds, boolean forceFull) {
        if (!isWidgetSupported(context)) {
            return;
        }

        if (appWidgetIds == null || appWidgetIds.length == 0) return;
        // Launch over to service so it can perform update
        for (int appWidgetId : appWidgetIds) {
            if (LOGD) Log.d(TAG, "Building widget update...");
            boolean full = forceFull || !sHeaders.containsKey(appWidgetId)
                    || !sBackgrounds.containsKey(appWidgetId);
            // Calendar header
            Time time = new Time(Utils.getTimeZone(context, null));
            time.set(System.currentTimeMillis());
            long millis = time.toMillis();
            java.time.LocalDate today = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.of(Utils.getTimeZone(context, null))).toLocalDate();
            java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            String weekday = "zh".equals(locale.getLanguage())
                    ? "周" + "一二三四五六日".charAt(today.getDayOfWeek().getValue() - 1)
                    : today.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, locale);
            String lunar = com.android.calendar.LunarUtils.dayLabel(context, today);
            String marker = com.android.calendar.LocalizedDay.marker(context, today);
            WidgetHeaderUtils.SpecialDates special = WidgetHeaderUtils.specialDates(context, today);
            boolean festivalToday = special.days == 0 && !special.festival.isEmpty();
            String primary = special.isEmpty() ? "" : special.days > 0
                    ? context.getString(R.string.widget_special_countdown, special.names(), special.days)
                    : festivalToday ? special.festival : special.term;
            String secondary = festivalToday ? special.term : "";
            int headerTextColor = WidgetHeaderUtils.accentColor(context);
            int dividerColor = WidgetHeaderUtils.dividerColor(context);
            int festivalColor = WidgetHeaderUtils.festivalColor(context);
            boolean liveTheme = WidgetUtils.usesLiveTheme(context);
            List<?> theme = Arrays.asList(ThemeUtils.INSTANCE.getTheme(context),
                    Utils.getSharedPreference(context, "pref_pure_black_night_mode", false),
                    liveTheme ? "" : ThemeUtils.INSTANCE.getSuffix(context));
            List<?> headerKey = Arrays.asList(today, Utils.getTimeZone(context, null), locale,
                    weekday, lunar, marker, primary, secondary, festivalToday, theme,
                    liveTheme ? 0 : headerTextColor, liveTheme ? 0 : dividerColor,
                    liveTheme ? 0 : festivalColor);
            List<?> backgroundKey = Arrays.asList(theme,
                    WidgetUtils.getBackgroundOpacity(context, appWidgetId));
            boolean headerChanged = full || !headerKey.equals(sHeaders.get(appWidgetId));
            boolean backgroundChanged = full || !backgroundKey.equals(sBackgrounds.get(appWidgetId));

            // Normal event changes must not reapply the widget shell or its background.
            if (!headerChanged && !backgroundChanged) {
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.events_list);
                continue;
            }
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.appwidget);
            if (headerChanged) {
                views.setTextViewText(R.id.date, String.valueOf(today.getDayOfMonth()));
                views.setTextViewText(R.id.day_of_week, "zh".equals(locale.getLanguage())
                        ? WidgetHeaderUtils.vertical(weekday) : weekday);
                views.setTextViewText(R.id.widget_lunar_day, WidgetHeaderUtils.vertical(lunar));
                views.setViewVisibility(R.id.widget_lunar_day,
                        lunar.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                views.setTextViewText(R.id.widget_holiday_marker, marker);
                views.setViewVisibility(R.id.widget_holiday_marker,
                        marker.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                if (!marker.isEmpty()) {
                    views.setTextColor(R.id.widget_holiday_marker, WidgetHeaderUtils.markerColor(marker));
                }
                if (liveTheme) {
                    for (int id : new int[]{R.id.day_of_week, R.id.date, R.id.widget_lunar_day}) {
                        views.setColorStateList(id, "setTextColor", R.color.widget_live_accent);
                    }
                    views.setColorStateList(R.id.iv_settings, "setImageTintList", R.color.widget_live_accent);
                    for (int id : new int[]{R.id.widget_header_divider, R.id.widget_date_lunar_divider,
                            R.id.widget_lunar_weekday_divider}) {
                        views.setInt(id, "setBackgroundResource", R.color.widget_live_divider);
                    }
                } else {
                    views.setTextColor(R.id.day_of_week, headerTextColor);
                    views.setTextColor(R.id.date, headerTextColor);
                    views.setTextColor(R.id.widget_lunar_day, headerTextColor);
                    views.setInt(R.id.iv_settings, "setColorFilter", headerTextColor);
                    views.setInt(R.id.widget_header_divider, "setBackgroundColor", dividerColor);
                    views.setInt(R.id.widget_date_lunar_divider, "setBackgroundColor", dividerColor);
                    views.setInt(R.id.widget_lunar_weekday_divider, "setBackgroundColor", dividerColor);
                }
                views.setViewVisibility(R.id.widget_lunar_weekday_divider,
                        lunar.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                views.setTextViewText(R.id.widget_special_dates, primary);
                views.setTextViewText(R.id.widget_special_term, secondary);
                views.setViewVisibility(R.id.widget_special_dates_row,
                        primary.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                views.setViewVisibility(R.id.widget_special_term,
                        secondary.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                WidgetUtils.setThemeText(context, views, R.id.widget_special_dates,
                        festivalToday ? R.color.widget_live_festival : R.color.widget_live_accent,
                        festivalToday ? festivalColor : headerTextColor);
                WidgetUtils.setThemeText(context, views, R.id.widget_special_term,
                        R.color.widget_live_accent, headerTextColor);

                views.setOnClickPendingIntent(R.id.header,
                        WidgetUtils.getLaunchCalendarPendingIntent(context, appWidgetId, millis));
            }
            if (backgroundChanged) {
                WidgetUtils.applyWidgetBackground(context, views, R.id.widget_background, appWidgetId);
            }
            if (full) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.os.Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
                    boolean landscape = context.getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                            : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
                    int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                            : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                    // Keep the visible surface square for compact, near-square launcher cells.
                    // Deliberately taller or wider layouts keep their available space.
                    boolean compactSquare = width > 0 && width <= 200 && height >= width
                            && height <= width * 1.4f;
                    views.setViewLayoutHeight(R.id.widget_background, compactSquare ? width : -1,
                            android.util.TypedValue.COMPLEX_UNIT_DIP);
                }


                Intent updateIntent = new Intent(context, CalendarAppWidgetService.class);
                updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                if (changedEventIds != null) {
                    updateIntent.putExtra(EXTRA_EVENT_IDS, changedEventIds);
                }
                updateIntent.setData(Uri.parse(updateIntent.toUri(Intent.URI_INTENT_SCHEME)));
                views.setRemoteAdapter(R.id.events_list, updateIntent);
                views.setOnClickPendingIntent(R.id.iv_settings,
                        WidgetUtils.getMenuPendingIntent(context, appWidgetId));
                views.setPendingIntentTemplate(R.id.events_list, getLaunchPendingIntentTemplate(context));
                appWidgetManager.updateAppWidget(appWidgetId, views);
            } else {
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views);
            }
            sHeaders.put(appWidgetId, headerKey);
            sBackgrounds.put(appWidgetId, backgroundKey);
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.events_list);
        }
        scheduleHeaderUpdate(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int appWidgetId, android.os.Bundle newOptions) {
        performUpdate(context, manager, new int[]{appWidgetId}, null, true);
    }

    private static PendingIntent getHeaderUpdateIntent(Context context) {
        Intent intent = new Intent(Utils.getWidgetUpdateAction(context))
                .setClass(context, CalendarAppWidgetProvider.class);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleHeaderUpdate(Context context) {
        java.time.ZoneId zone = java.time.ZoneId.of(Utils.getTimeZone(context, null));
        long midnight = java.time.LocalDate.now(zone).plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarm.set(AlarmManager.RTC, midnight, getHeaderUpdateIntent(context));
    }

    public static boolean isWidgetSupported(Context context) {
        if (!sWidgetChecked) {
            sWidgetSupported = hasAppWidgetsSystemFeature(context);
            sWidgetChecked = true;
        }

        return sWidgetSupported;
    }

    private static boolean hasAppWidgetsSystemFeature(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS);
    }
}
