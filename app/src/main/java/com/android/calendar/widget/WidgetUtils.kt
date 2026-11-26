/*
 * Copyright (C) 2026 The Etar Project
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

package com.android.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.android.calendar.theme.ThemeUtils
import com.android.calendar.theme.ThemeUtils.isPureBlackModeEnabled
import com.android.calendar.theme.model.Theme
import ws.xsoh.etar.R
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import androidx.core.content.edit
import com.android.calendar.AllInOneActivity
import com.android.calendar.CalendarUtils
import com.android.calendar.Utils
import com.android.calendar.event.EditEventActivity

object WidgetUtils {

    private const val PREF_KEY_PREFIX = "widget_calendars_"
    private const val OPACITY_KEY_PREFIX = "widget_background_opacity_"

    fun getWidgetCalendarIds(context: Context, appWidgetId: Int): Set<String>? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return null
        }
        val prefs = CalendarUtils.getSharedPreferences(context, Utils.SHARED_PREFS_NAME)
        return prefs.getStringSet(PREF_KEY_PREFIX + appWidgetId, null)
    }

    fun saveWidgetCalendarIds(context: Context, appWidgetId: Int, calendarIds: Set<String>) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return
        }
        val prefs = CalendarUtils.getSharedPreferences(context, Utils.SHARED_PREFS_NAME)
        prefs.edit { putStringSet(PREF_KEY_PREFIX + appWidgetId, calendarIds) }
    }

    fun deleteWidgetCalendarIds(context: Context, appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return
        }
        val prefs = CalendarUtils.getSharedPreferences(context, Utils.SHARED_PREFS_NAME)
        prefs.edit {
            remove(PREF_KEY_PREFIX + appWidgetId)
            remove(OPACITY_KEY_PREFIX + appWidgetId)
        }
    }

    @JvmStatic
    fun getBackgroundOpacity(context: Context, appWidgetId: Int): Int {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return 100
        val prefs = CalendarUtils.getSharedPreferences(context, Utils.SHARED_PREFS_NAME)
        return prefs.getInt(OPACITY_KEY_PREFIX + appWidgetId, 100).coerceIn(0, 100)
    }

    fun saveBackgroundOpacity(context: Context, appWidgetId: Int, opacity: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val prefs = CalendarUtils.getSharedPreferences(context, Utils.SHARED_PREFS_NAME)
        prefs.edit { putInt(OPACITY_KEY_PREFIX + appWidgetId, opacity.coerceIn(0, 100)) }
    }

    @JvmStatic
    fun usesLiveTheme(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ThemeUtils.getTheme(context) == Theme.SYSTEM

    // Use the same background shape and host-resolved color for every widget.
    @JvmStatic
    fun applyWidgetBackground(context: Context, views: android.widget.RemoteViews,
                              rootId: Int, appWidgetId: Int) {
        val color = when (ThemeUtils.getTheme(context)) {
            Theme.SYSTEM -> if (context.isPureBlackModeEnabled)
                R.color.widget_live_background_black else R.color.widget_live_background
            Theme.LIGHT -> R.color.widget_background_light
            Theme.DARK -> if (context.isPureBlackModeEnabled)
                R.color.bg_black else R.color.widget_background_dark
            Theme.BLACK -> R.color.bg_black
        }
        val id = R.id.widget_background_image
        views.setInt(rootId, "setBackgroundResource", 0)
        views.setImageViewResource(id, R.drawable.widget_surface)
        if (Build.VERSION.SDK_INT >= 31) {
            // Resolve at RemoteViews application time in the host, not the provider.
            views.setColorStateList(id, "setImageTintList", color)
        } else {
            views.setInt(id, "setColorFilter", context.getColor(color))
        }
        applyOpacity(views, id, getBackgroundOpacity(context, appWidgetId))
    }

    @JvmStatic
    fun setThemeText(context: Context, views: android.widget.RemoteViews, viewId: Int,
                     liveColor: Int, fallback: Int) {
        if (usesLiveTheme(context)) views.setColorStateList(viewId, "setTextColor", liveColor)
        else views.setTextColor(viewId, fallback)
    }

    @JvmStatic
    fun setThemeLine(context: Context, views: android.widget.RemoteViews, viewId: Int,
                     liveColor: Int, fallback: Int) {
        if (usesLiveTheme(context)) views.setInt(viewId, "setBackgroundResource", liveColor)
        else views.setInt(viewId, "setBackgroundColor", fallback)
    }

    private fun applyOpacity(views: android.widget.RemoteViews, viewId: Int, opacity: Int) {
        // Reset drawable alpha left by earlier widget versions. View alpha survives
        // asynchronous drawable replacement and does not affect sibling content.
        views.setInt(viewId, "setImageAlpha", 255)
        views.setFloat(viewId, "setAlpha", opacity.coerceIn(0, 100) / 100f)
    }

    fun updateBackgroundOpacity(context: Context, appWidgetId: Int, layoutId: Int) {
        val views = android.widget.RemoteViews(context.packageName, layoutId)
        applyOpacity(views, ws.xsoh.etar.R.id.widget_background_image,
            getBackgroundOpacity(context, appWidgetId))
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(appWidgetId, views)
    }

    @JvmStatic
    fun getWidgetSelection(context: Context, appWidgetId: Int): String {
        val baseSelection = if (Utils.getHideDeclinedEvents(context)) {
            Calendars.VISIBLE + "=1 AND " + Instances.SELF_ATTENDEE_STATUS + "!=" + Attendees.ATTENDEE_STATUS_DECLINED
        } else {
            Calendars.VISIBLE + "=1"
        }

        val calendarIds = getWidgetCalendarIds(context, appWidgetId)
        return if (!calendarIds.isNullOrEmpty()) {
            val ids = calendarIds.joinToString(",")
            "$baseSelection AND ${Instances.CALENDAR_ID} IN ($ids)"
        } else {
            baseSelection
        }
    }

    @JvmStatic
    fun getConfigurePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val configIntent = getConfigureIntent(context, appWidgetId)
        return PendingIntent.getActivity(
            context, appWidgetId, configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @JvmStatic
    fun getAddEventPendingIntent(context: Context): PendingIntent {
        val addIntent = getAddEventIntent(context)
        return PendingIntent.getActivity(
            context, 0, addIntent, PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun getConfigureIntent(context: Context, appWidgetId: Int): Intent =
        Intent(context, CalendarWidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    fun getAddEventIntent(context: Context): Intent = Intent(Intent.ACTION_VIEW).apply {
        setClass(context, EditEventActivity::class.java)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
        putExtra(CalendarContract.Events.CALENDAR_ID, -1L)
    }

    @JvmStatic
    fun getMenuPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetMenuActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // Explicit component and unique widget ID; allow RemoteViews to supply source bounds.
        return PendingIntent.getActivity(
            context, appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @JvmStatic
    fun getLaunchCalendarPendingIntent(context: Context, appWidgetId: Int, timeMillis: Long): PendingIntent {
        val launchIntent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, AllInOneActivity::class.java)
            data = Uri.parse("content://com.android.calendar/time/$timeMillis")
        }
        return PendingIntent.getActivity(
            context, appWidgetId, launchIntent, PendingIntent.FLAG_IMMUTABLE
        )
    }
}
