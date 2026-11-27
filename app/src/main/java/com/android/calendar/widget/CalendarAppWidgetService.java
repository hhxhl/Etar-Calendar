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

import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.android.calendar.Utils;
import com.android.calendar.widget.CalendarAppWidgetModel.DayInfo;
import com.android.calendar.widget.CalendarAppWidgetModel.EventInfo;
import com.android.calendar.widget.CalendarAppWidgetModel.RowInfo;
import com.android.calendar.calendarcommon2.Time;


import ws.xsoh.etar.R;

public class CalendarAppWidgetService extends RemoteViewsService {
    static final int EVENT_MIN_COUNT = 20;
    static final int EVENT_MAX_COUNT = 100;
    static final String[] EVENT_PROJECTION = new String[] {
        Instances.ALL_DAY,
        Instances.BEGIN,
        Instances.END,
        Instances.TITLE,
        Instances.EVENT_LOCATION,
        Instances.EVENT_ID,
        Instances.START_DAY,
        Instances.END_DAY,
        Instances.DISPLAY_COLOR,
        Instances.SELF_ATTENDEE_STATUS,
    };
    static final int INDEX_ALL_DAY = 0;
    static final int INDEX_BEGIN = 1;
    static final int INDEX_END = 2;
    static final int INDEX_TITLE = 3;
    static final int INDEX_EVENT_LOCATION = 4;
    static final int INDEX_EVENT_ID = 5;
    static final int INDEX_START_DAY = 6;
    static final int INDEX_END_DAY = 7;
    static final int INDEX_COLOR = 8;
    static final int INDEX_SELF_ATTENDEE_STATUS = 9;
    static final int MAX_DAYS = 31;
    private static final String TAG = "CalendarWidget";
    private static final String EVENT_SORT_ORDER = Instances.START_DAY + " ASC, "
            + Instances.START_MINUTE + " ASC, " + Instances.END_DAY + " ASC, "
            + Instances.END_MINUTE + " ASC LIMIT " + EVENT_MAX_COUNT;
    private static final long SEARCH_DURATION = MAX_DAYS * DateUtils.DAY_IN_MILLIS;
    /**
     * Update interval used when no next-update calculated, or bad trigger time in past.
     * Unit: milliseconds.
     */
    private static final long UPDATE_TIME_NO_EVENTS = DateUtils.HOUR_IN_MILLIS * 6;


    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new CalendarFactory(getApplicationContext(), intent);
    }

    public static class CalendarFactory extends BroadcastReceiver implements
            RemoteViewsService.RemoteViewsFactory {
        private volatile CalendarAppWidgetModel mModel;
        private Context mContext;
        private int mAppWidgetId;

        protected CalendarFactory(Context context, Intent intent) {
            mContext = context;
            mAppWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        }

        public CalendarFactory() {
            // This is being created as part of onReceive

        }

        /* @VisibleForTesting */
        protected static CalendarAppWidgetModel buildAppWidgetModel(
                Context context, Cursor cursor, String timeZone) {
            CalendarAppWidgetModel model = new CalendarAppWidgetModel(context, timeZone);
            model.buildFromCursor(cursor, timeZone);
            return model;
        }

        private CalendarAppWidgetModel buildWidgetModelWithTasks(Cursor cursor, String timeZone) {
            CalendarAppWidgetModel model = new CalendarAppWidgetModel(mContext, timeZone);
            try (MatrixCursor merged = WidgetTaskRows.merge(mContext, mAppWidgetId, cursor,
                    EVENT_PROJECTION, model.mTodayJulianDay, model.mMaxJulianDay, false, true)) {
                model.buildFromCursor(merged, timeZone);
            }
            return model;
        }

        private static long getNextMidnightTimeMillis(String timezone) {
            Time time = new Time();
            time.set(System.currentTimeMillis());
            time.setDay(time.getDay() + 1);
            time.setHour(0);
            time.setMinute(0);
            time.setSecond(0);
            long midnightDeviceTz = time.normalize();

            time.setTimezone(timezone);
            time.set(System.currentTimeMillis());
            time.setDay(time.getDay() + 1);
            time.setHour(0);
            time.setMinute(0);
            time.setSecond(0);
            long midnightHomeTz = time.normalize();

            return Math.min(midnightDeviceTz, midnightHomeTz);
        }

        static void updateTextView(RemoteViews views, int id, int visibility, String string) {
            views.setViewVisibility(id, visibility);
            if (visibility == View.VISIBLE) {
                views.setTextViewText(id, string);
            }
        }

        private void loadData() {
            if (mContext == null) return;
            String tz = Utils.getTimeZone(mContext, null);
            long now = System.currentTimeMillis();
            try (Cursor cursor = WidgetTaskRows.queryCalendar(mContext, mAppWidgetId,
                    now - DateUtils.DAY_IN_MILLIS, now + SEARCH_DURATION + DateUtils.DAY_IN_MILLIS,
                    EVENT_PROJECTION, EVENT_SORT_ORDER)) {
                mModel = buildWidgetModelWithTasks(cursor, tz);
                scheduleNextUpdate(calculateUpdateTime(mModel, now, tz));
            } catch (Exception e) {
                Log.e(TAG, "Error loading events for widget " + mAppWidgetId, e);
                // Retain the last snapshot on provider failure and arrange a retry.
                scheduleNextUpdate(now + UPDATE_TIME_NO_EVENTS);
            }
        }

        private void scheduleNextUpdate(long triggerTime) {
            if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            boolean active = false;
            for (int id : AppWidgetManager.getInstance(mContext)
                    .getAppWidgetIds(CalendarAppWidgetProvider.getComponentName(mContext))) {
                if (id == mAppWidgetId) { active = true; break; }
            }
            if (!active) return;
            AlarmManager alarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            alarm.set(AlarmManager.RTC, triggerTime,
                    CalendarAppWidgetProvider.getUpdateIntent(mContext, mAppWidgetId));
        }

        @Override
        public void onCreate() {
            loadData();
        }

        @Override
        public void onDataSetChanged() {
            loadData();
        }

        @Override
        public void onDestroy() {
            mModel = null;
        }

        @Override
        public RemoteViews getLoadingView() {
            return new RemoteViews(mContext.getPackageName(),
                    R.layout.appwidget_loading);
        }

        @Override
        public RemoteViews getViewAt(int position) {
            // we use getCount here so that it doesn't return null when empty
            if (position < 0 || position >= getCount()) {
                return null;
            }

            if (mModel == null) {
                RemoteViews views = new RemoteViews(mContext.getPackageName(),
                        R.layout.appwidget_loading);
                final Intent intent = CalendarAppWidgetProvider.getLaunchFillInIntent(mContext, 0,
                        0, 0, false);
                views.setOnClickFillInIntent(R.id.appwidget_loading, intent);
                return views;

            }
            if (mModel.mEventInfos.isEmpty() || mModel.mRowInfos.isEmpty()) {
                RemoteViews views = new RemoteViews(mContext.getPackageName(),
                        R.layout.appwidget_no_events);
                final Intent intent = CalendarAppWidgetProvider.getLaunchFillInIntent(mContext, 0,
                        0, 0, false);
                views.setColorStateList(R.id.no_events, "setTextColor", R.color.widget_live_text);
                views.setOnClickFillInIntent(R.id.appwidget_no_events, intent);
                return views;
            }

            RowInfo rowInfo = mModel.mRowInfos.get(position);
            if (rowInfo.mType == RowInfo.TYPE_DAY) {
                RemoteViews views = new RemoteViews(mContext.getPackageName(),
                        R.layout.appwidget_day);
                DayInfo dayInfo = mModel.mDayInfos.get(rowInfo.mIndex);
                updateTextView(views, R.id.date, View.VISIBLE, dayInfo.mDayLabel);
                views.setColorStateList(R.id.date, "setTextColor", R.color.widget_live_text);
                return views;
            } else {
                RemoteViews views;
                final EventInfo eventInfo = mModel.mEventInfos.get(rowInfo.mIndex);
                if (eventInfo.allDay) {
                    views = new RemoteViews(mContext.getPackageName(),
                            R.layout.widget_all_day_item);
                } else {
                    views = new RemoteViews(mContext.getPackageName(), R.layout.widget_item);
                }
                int displayColor = Utils.getDisplayColorFromColor(mContext, eventInfo.color);
                if (eventInfo.allDay) {
                    com.android.calendar.ItemAppearance.bindWidget(mContext, views, displayColor,
                            R.id.widget_item_surface, R.id.agenda_item_color, R.id.title);
                } else {
                    com.android.calendar.ItemAppearance.bindWidget(mContext, views, displayColor,
                            R.id.widget_item_surface, R.id.agenda_item_color, R.id.title, R.id.when, R.id.where);
                    updateTextView(views, R.id.when, eventInfo.visibWhen, eventInfo.when);
                    updateTextView(views, R.id.where, eventInfo.visibWhere, eventInfo.where);
                }
                String countdown = eventInfo.id < -1 ? "" : com.android.calendar.EventCountdown.widgetLabel(mContext, eventInfo.id, eventInfo.start);
                String displayTitle = countdown.isEmpty() ? eventInfo.title : eventInfo.title + " · " + countdown;
                updateTextView(views, R.id.title, eventInfo.visibTitle, displayTitle);

                if (eventInfo.status == Events.STATUS_CANCELED) {
                    views.setInt(R.id.title, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG);
                }

                long start = eventInfo.start;
                long end = eventInfo.end;
                // An element in ListView.
                if (eventInfo.allDay) {
                    String tz = Utils.getTimeZone(mContext, null);
                    Time recycle = new Time();
                    start = Utils.convertAlldayLocalToUTC(recycle, start, tz);
                    end = Utils.convertAlldayLocalToUTC(recycle, end, tz);
                }
                final Intent fillInIntent = CalendarAppWidgetProvider.getLaunchFillInIntent(
                        mContext, eventInfo.id, start, end, eventInfo.allDay);
                views.setOnClickFillInIntent(R.id.widget_row, fillInIntent);
                return views;
            }
        }

        @Override
        public int getViewTypeCount() {
            return 5;
        }

        @Override
        public int getCount() {
            // if there are no events, we still return 1 to represent the "no
            // events" view
            if (mModel == null) {
                return 1;
            }
            return Math.max(1, mModel.mRowInfos.size());
        }

        @Override
        public long getItemId(int position) {
            if (mModel == null ||  mModel.mRowInfos.isEmpty() || position >= getCount()) {
                return 0;
            }
            RowInfo rowInfo = mModel.mRowInfos.get(position);
            if (rowInfo.mType == RowInfo.TYPE_DAY) {
                return rowInfo.mIndex;
            }
            EventInfo eventInfo = mModel.mEventInfos.get(rowInfo.mIndex);
            long prime = 31;
            long result = 1;
            result = prime * result + (int) (eventInfo.id ^ (eventInfo.id >>> 32));
            result = prime * result + (int) (eventInfo.start ^ (eventInfo.start >>> 32));
            return result;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        /**
         * Calculates and returns the next time we should push widget updates.
         */
        private long calculateUpdateTime(CalendarAppWidgetModel model, long now, String timeZone) {
            // Make sure an update happens at midnight or earlier
            long minUpdateTime = getNextMidnightTimeMillis(timeZone);
            for (EventInfo event : model.mEventInfos) {
                if (event.allDay) continue;
                minUpdateTime = Math.min(minUpdateTime,
                        com.android.calendar.ItemStateSymbols.nextTimedTransition(
                                event.start, event.end, event.id < -1, event.taskCompleted, now));
                // Expired completed reminders and ordinary events also leave the agenda.
                if ((event.id >= -1 || event.taskCompleted) && event.end >= now
                        && event.end < Long.MAX_VALUE)
                    minUpdateTime = Math.min(minUpdateTime, event.end + 1);
            }
            return minUpdateTime;
        }

        /** All refreshes use the widget's own factory and source selection. */
        @Override
        public void onReceive(Context context, Intent intent) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] active = manager.getAppWidgetIds(CalendarAppWidgetProvider.getComponentName(context));
            int requested = Utils.getWidgetScheduledUpdateAction(context).equals(intent.getAction())
                    ? intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                    : AppWidgetManager.INVALID_APPWIDGET_ID;
            for (int widgetId : active) {
                if (requested == AppWidgetManager.INVALID_APPWIDGET_ID || requested == widgetId) {
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.events_list);
                }
            }
        }
    }
}
