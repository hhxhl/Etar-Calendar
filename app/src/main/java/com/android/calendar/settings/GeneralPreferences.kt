/*
 * Copyright (C) 2020 Dominik Schürmann <dominik@schuermann.eu>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.android.calendar.settings

import android.app.backup.BackupManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarCache
import android.provider.SearchRecentSuggestions
import android.provider.Settings
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.android.calendar.CalendarController
import com.android.calendar.CalendarRecentSuggestionsProvider
import com.android.calendar.CalendarUtils
import com.android.calendar.Utils
import com.android.calendar.event.EventViewUtils
import com.android.calendar.theme.isSystemInDarkTheme
import com.android.calendar.timezonepicker.TimeZoneInfo
import com.android.calendar.timezonepicker.TimeZonePickerDialog
import com.android.calendar.timezonepicker.TimeZonePickerUtils
import ws.xsoh.etar.R
import java.util.TimeZone

class GeneralPreferences : PreferenceFragmentCompat(),
        OnSharedPreferenceChangeListener, Preference.OnPreferenceChangeListener,
        TimeZonePickerDialog.OnTimeZoneSetListener {

    private lateinit var themePref: ListPreference
    private lateinit var pureBlackNightModePref: MaterialSwitchPreference
    private lateinit var doNotCheckBatteryOptimizationPref: MaterialSwitchPreference
    private lateinit var defaultStartPref: ListPreference
    private lateinit var hideDeclinedPref: MaterialSwitchPreference
    private lateinit var staggeredDisplayPref: MaterialSwitchPreference
    private lateinit var weekStartPref: ListPreference
    private lateinit var dayWeekPref: ListPreference
    private lateinit var defaultEventDurationPref: ListPreference
    private lateinit var useHomeTzPref: MaterialSwitchPreference
    private lateinit var homeTzPref: Preference
    private lateinit var snoozeDelayPref: ListPreference
    private lateinit var useDefaultCustomSnoozeDelayPref: Preference
    private lateinit var defaultReminderPref: ListPreference
    private lateinit var copyDbPref: Preference
    private lateinit var skipRemindersPref: ListPreference

    private lateinit var tzPickerUtils: TimeZonePickerUtils
    private var timeZoneId: String? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = SHARED_PREFS_NAME
        setPreferencesFromResource(R.xml.general_preferences, rootKey)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.title = getString(R.string.preferences_list_general)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themePref = preferenceScreen.findPreference(KEY_THEME_PREF)!!
        pureBlackNightModePref = preferenceScreen.findPreference(KEY_PURE_BLACK_NIGHT_MODE)!!
        doNotCheckBatteryOptimizationPref = preferenceScreen.findPreference(KEY_DO_NOT_CHECK_BATTERY_OPTIMIZATION)!!
        defaultStartPref = preferenceScreen.findPreference(KEY_DEFAULT_START)!!
        hideDeclinedPref = preferenceScreen.findPreference(KEY_HIDE_DECLINED)!!
        staggeredDisplayPref = preferenceScreen.findPreference(KEY_STAGGERED_DISPLAY)!!
        weekStartPref = preferenceScreen.findPreference(KEY_WEEK_START_DAY)!!
        dayWeekPref = preferenceScreen.findPreference(KEY_DAYS_PER_WEEK)!!
        defaultEventDurationPref = preferenceScreen.findPreference(KEY_DEFAULT_EVENT_DURATION)!!
        useHomeTzPref = preferenceScreen.findPreference(KEY_HOME_TZ_ENABLED)!!
        homeTzPref = preferenceScreen.findPreference(KEY_HOME_TZ)!!
        snoozeDelayPref = preferenceScreen.findPreference(KEY_DEFAULT_SNOOZE_DELAY)!!
        useDefaultCustomSnoozeDelayPref = preferenceScreen.findPreference(KEY_USE_CUSTOM_SNOOZE_DELAY)!!
        defaultReminderPref = preferenceScreen.findPreference(KEY_DEFAULT_REMINDER)!!
        copyDbPref = preferenceScreen.findPreference(KEY_OTHER_COPY_DB)!!
        skipRemindersPref = preferenceScreen.findPreference(KEY_OTHER_REMINDERS_RESPONDED)!!

        val prefs = CalendarUtils.getSharedPreferences(requireActivity(),
                Utils.SHARED_PREFS_NAME)

        if (themePref.value == "system" && !isSystemInDarkTheme(requireActivity()) || themePref.value == "light") {
            preferenceScreen.removePreferenceRecursively(KEY_PURE_BLACK_NIGHT_MODE)
        }

        buildWeekStartEntries()
        buildSnoozeDelayEntries()
        buildDefaultReminderPrefEntries()
        handleUseCustomSnoozeDelayVisibility()
        defaultEventDurationPref.summary = defaultEventDurationPref.entry
        themePref.summary = themePref.entry
        weekStartPref.summary = weekStartPref.entry
        dayWeekPref.summary = dayWeekPref.entry
        defaultReminderPref.summary = defaultReminderPref.entry
        snoozeDelayPref.summary = snoozeDelayPref.entry
        defaultStartPref.summary = defaultStartPref.entry
        skipRemindersPref.summary = skipRemindersPref.entry

        // This triggers an asynchronous call to the provider to refresh the data in shared pref
        timeZoneId = Utils.getTimeZone(activity, null)

        // Utils.getTimeZone will return the currentTimeZone instead of the one
        // in the shared_pref if home time zone is disabled. So if home tz is
        // off, we will explicitly read it.
        if (!prefs.getBoolean(KEY_HOME_TZ_ENABLED, false)) {
            timeZoneId = prefs.getString(KEY_HOME_TZ, TimeZone.getDefault().id)
        }

        tzPickerUtils = TimeZonePickerUtils(activity)

        val timezoneName = tzPickerUtils.getGmtDisplayName(activity, timeZoneId,
                System.currentTimeMillis(), false)
        homeTzPref.summary = timezoneName ?: timeZoneId

        com.android.calendar.ItemDateTimePickers.restoreZone(requireActivity().supportFragmentManager,
                FRAG_TAG_TIME_ZONE_PICKER, this)

    }

    private fun handleUseCustomSnoozeDelayVisibility() {
        useDefaultCustomSnoozeDelayPref.isEnabled = Integer.parseInt(snoozeDelayPref.value) >= 0
    }

    private fun showTimezoneDialog() {
        com.android.calendar.ItemDateTimePickers.showZone(requireActivity().supportFragmentManager,
                FRAG_TAG_TIME_ZONE_PICKER, System.currentTimeMillis(),
                timeZoneId ?: Utils.getTimeZone(activity, null), this)
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        setPreferenceListeners(this)
    }

    /**
     * Sets up all the preference change listeners to use the specified listener.
     */
    private fun setPreferenceListeners(listener: Preference.OnPreferenceChangeListener) {
        themePref.onPreferenceChangeListener = listener
        pureBlackNightModePref.onPreferenceChangeListener = listener
        doNotCheckBatteryOptimizationPref.onPreferenceChangeListener = listener
        defaultStartPref.onPreferenceChangeListener = listener
        hideDeclinedPref.onPreferenceChangeListener = listener
        staggeredDisplayPref.onPreferenceChangeListener = listener
        weekStartPref.onPreferenceChangeListener = listener
        dayWeekPref.onPreferenceChangeListener = listener
        defaultEventDurationPref.onPreferenceChangeListener = listener
        useHomeTzPref.onPreferenceChangeListener = listener
        homeTzPref.onPreferenceChangeListener = listener
        snoozeDelayPref.onPreferenceChangeListener = listener
        defaultReminderPref.onPreferenceChangeListener = listener
        skipRemindersPref.onPreferenceChangeListener = listener
    }

    override fun onStop() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        val a = activity ?: return
        key ?: return

        BackupManager.dataChanged(a.packageName)

        when (key) {
            com.android.calendar.tasks.TaskTimePresentation.KEY -> {
                Utils.notifyWidgetDataChanged(a)
            }
            KEY_THEME_PREF -> {
                Utils.sendUpdateWidgetIntent(a)
                a.recreate()
            }
            KEY_PURE_BLACK_NIGHT_MODE -> {
                if (themePref.value == "system" && isSystemInDarkTheme(a) || themePref.value == "dark") {
                    Utils.sendUpdateWidgetIntent(a)
                    a.recreate()
                }
            }
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when (preference) {
            useHomeTzPref -> {
                if (Utils.isCalendarPermissionGranted(requireContext(), true)) {
                    val useHomeTz = newValue as Boolean
                    val tz: String? = if (useHomeTz) {
                        timeZoneId
                    } else {
                        CalendarCache.TIMEZONE_TYPE_AUTO
                    }
                    Utils.setTimeZone(activity, tz)
                    return true
                }
            }
            themePref -> {
                themePref.value = newValue as String
                themePref.summary = themePref.entry
            }
            hideDeclinedPref -> {
                hideDeclinedPref.isChecked = newValue as Boolean
                val intent = Intent(Utils.getWidgetScheduledUpdateAction(activity))
                intent.setDataAndType(CalendarContract.CONTENT_URI, Utils.APPWIDGET_DATA_TYPE)
                requireActivity().sendBroadcast(intent)
                return true
            }
            staggeredDisplayPref -> {
                staggeredDisplayPref.isChecked = newValue as Boolean
                return true
            }
            weekStartPref -> {
                weekStartPref.value = newValue as String
                weekStartPref.summary = weekStartPref.entry
            }
            dayWeekPref -> {
                dayWeekPref.value = newValue as String
                dayWeekPref.summary = dayWeekPref.entry
            }
            defaultEventDurationPref -> {
                defaultEventDurationPref.value = newValue as String
                defaultEventDurationPref.summary = defaultEventDurationPref.entry
            }
            defaultReminderPref -> {
                defaultReminderPref.value = newValue as String
                defaultReminderPref.summary = defaultReminderPref.entry
            }
            snoozeDelayPref -> {
                snoozeDelayPref.value = newValue as String
                snoozeDelayPref.summary = snoozeDelayPref.entry
                handleUseCustomSnoozeDelayVisibility()
            }
            defaultStartPref -> {
                val i = defaultStartPref.findIndexOfValue(newValue as String)
                defaultStartPref.summary = defaultStartPref.entries[i]
                return true
            }
            skipRemindersPref -> {
                skipRemindersPref.value = newValue as String
                skipRemindersPref.summary = skipRemindersPref.entry
            }
            else -> {
                return true
            }
        }
        return false
    }

    private fun buildWeekStartEntries() {
        val symbols = DateFormatSymbols.getInstance(Locale.getDefault())
        val weekdays = symbols.weekdays
        val entries = arrayOf(
            getString(R.string.week_start_locale_default),
            weekdays[Calendar.SUNDAY],
            weekdays[Calendar.MONDAY],
            weekdays[Calendar.TUESDAY],
            weekdays[Calendar.WEDNESDAY],
            weekdays[Calendar.THURSDAY],
            weekdays[Calendar.FRIDAY],
            weekdays[Calendar.SATURDAY]
        )
        weekStartPref.entries = entries
    }

    private fun buildSnoozeDelayEntries() {
        val values = snoozeDelayPref.entryValues
        val count = values.size
        val entries = arrayOfNulls<CharSequence>(count)

        for (i in 0 until count) {
            val value = Integer.parseInt(values[i].toString())
            entries[i] = EventViewUtils.constructReminderLabel(requireActivity(), value, false)
        }

        snoozeDelayPref.entries = entries
    }

    private fun buildDefaultReminderPrefEntries() {
        val values = defaultReminderPref.entryValues
        val count = values.size
        val entries = arrayOfNulls<CharSequence>(count)

        for (i in 0 until count) {
            val value = Integer.parseInt(values[i].toString())
            entries[i] = EventViewUtils.constructReminderLabel(requireActivity(), value, false)
        }

        defaultReminderPref.entries = entries
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            KEY_HOME_TZ -> {
                showTimezoneDialog()
                return true
            }
            KEY_CLEAR_SEARCH_HISTORY -> {
                clearSearchHistory()
                return true
            }
            KEY_NOTIFICATION -> {
                showNotificationChannel()
                return true
            }
            KEY_OTHER_COPY_DB -> {
                showDbCopy()
                return true
            }
            else -> return super.onPreferenceTreeClick(preference)
        }
    }

    private fun showDbCopy() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = ComponentName("com.android.providers.calendar",
                "com.android.providers.calendar.CalendarDebugActivity")
        startActivity(intent)
    }

    private fun clearSearchHistory() {
        val suggestions = SearchRecentSuggestions(activity,
                Utils.getSearchAuthority(activity),
                CalendarRecentSuggestionsProvider.MODE)
        suggestions.clearHistory()
        Toast.makeText(activity, R.string.search_history_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun showNotificationChannel() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_CHANNEL_ID, "alert_channel_01")
            putExtra(Settings.EXTRA_APP_PACKAGE, requireActivity().packageName)
        }
        startActivity(intent)
    }

    override fun onTimeZoneSet(tzi: TimeZoneInfo) {
        timeZoneId = tzi.mTzId
        val timeZoneDisplayName = tzPickerUtils.getGmtDisplayName(
                activity, tzi.mTzId, System.currentTimeMillis(), false)
        homeTzPref.summary = timeZoneDisplayName
        useHomeTzPref.isChecked = true
        Utils.setTimeZone(activity, tzi.mTzId)
    }

    companion object {
        // Preference keys
        const val KEY_THEME_PREF = "pref_theme"
        /** Show buttons to switch between the day, week and month views in the toolbar */
        const val KEY_VIEW_SWITCH_BUTTONS = "pref_view_switch_buttons"
        const val KEY_DO_NOT_CHECK_BATTERY_OPTIMIZATION = "pref_do_not_check_battery_optimization"
        const val KEY_PURE_BLACK_NIGHT_MODE = "pref_pure_black_night_mode"
        const val KEY_DEFAULT_START = "preferences_default_start"
        const val KEY_HIDE_DECLINED = "preferences_hide_declined"
        const val KEY_STAGGERED_DISPLAY = "preferences_staggered_display"
        const val KEY_WEEK_START_DAY = "preferences_week_start_day"
        const val KEY_SHOW_WEEK_NUM = "preferences_show_week_num"
        const val KEY_DAYS_PER_WEEK = "preferences_days_per_week"
        const val KEY_MDAYS_PER_WEEK = "preferences_mdays_per_week"
        const val KEY_CLEAR_SEARCH_HISTORY = "preferences_clear_search_history"
        const val KEY_ALERTS_CATEGORY = "preferences_alerts_category"
        const val KEY_ALERTS = "preferences_alerts"
        const val KEY_NOTIFICATION = "preferences_notification"
        const val KEY_ALERTS_VIBRATE = "preferences_alerts_vibrate"
        const val KEY_ALERTS_RINGTONE = "preferences_alerts_ringtone"
        const val KEY_SHOW_CONTROLS = "preferences_show_controls"
        const val KEY_DEFAULT_REMINDER = "preferences_default_reminder"
        const val NO_REMINDER = -2147483648
        const val NO_REMINDER_STRING = "-2147483648"
        const val REMINDER_DEFAULT_TIME = 10 // in minutes
        const val KEY_USE_CUSTOM_SNOOZE_DELAY = "preferences_custom_snooze_delay"
        const val KEY_DEFAULT_SNOOZE_DELAY = "preferences_default_snooze_delay"
        const val SNOOZE_DELAY_DEFAULT_TIME = 5 // in minutes
        const val KEY_DEFAULT_CELL_HEIGHT = "preferences_default_cell_height"
        const val KEY_VERSION = "preferences_version"
        /** Key to SharePreference for default view (CalendarController.ViewType)  */
        const val KEY_START_VIEW = "preferred_startView"
        /**
         * Key to SharePreference for default detail view (CalendarController.ViewType)
         * Typically used by widget
         */
        const val KEY_DETAILED_VIEW = "preferred_detailedView"
        const val KEY_DEFAULT_CALENDAR = "preference_defaultCalendar"

        /** Key to preference for default new event duration (if provider doesn't indicate one)  */
        const val KEY_DEFAULT_EVENT_DURATION = "preferences_default_event_duration"
        const val EVENT_DURATION_DEFAULT = "60"

        // These must be in sync with the array preferences_week_start_day_values
        const val WEEK_START_DEFAULT = "-1"
        const val WEEK_START_SUNDAY = "1"
        const val WEEK_START_MONDAY = "2"
        const val WEEK_START_TUESDAY = "3"
        const val WEEK_START_WEDNESDAY = "4"
        const val WEEK_START_THURSDAY = "5"
        const val WEEK_START_FRIDAY = "6"
        const val WEEK_START_SATURDAY = "7"
        // Default preference values
        const val DEFAULT_DEFAULT_START = "-2"
        const val DEFAULT_START_VIEW = CalendarController.ViewType.WEEK
        const val DEFAULT_DETAILED_VIEW = CalendarController.ViewType.DAY
        const val DEFAULT_SHOW_WEEK_NUM = false
        // This should match the XML file.
        const val DEFAULT_RINGTONE = "content://settings/system/notification_sound"
        // The name of the shared preferences file. This name must be maintained for historical
        // reasons, as it's what PreferenceManager assigned the first time the file was created.
        const val SHARED_PREFS_NAME = "com.android.calendar_preferences"
        const val SHARED_PREFS_NAME_NO_BACKUP = "com.android.calendar_preferences_no_backup"
        private const val KEY_HOME_TZ_ENABLED = "preferences_home_tz_enabled"
        private const val KEY_HOME_TZ = "preferences_home_tz"
        private const val FRAG_TAG_TIME_ZONE_PICKER = "TimeZonePicker"

        // experimental
        const val KEY_OTHER_COPY_DB = "preferences_copy_db"
        const val KEY_OTHER_REMINDERS_RESPONDED = "preferences_reminders_responded"


        /** Return a properly configured SharedPreferences instance  */
        fun getSharedPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        }

        /** Set the default shared preferences in the proper context */
        fun setDefaultValues(context: Context) {
            PreferenceManager.setDefaultValues(context, SHARED_PREFS_NAME, Context.MODE_PRIVATE,
                    R.xml.general_preferences, true)
        }
    }
}
