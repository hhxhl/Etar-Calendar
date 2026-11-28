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

import android.content.Intent
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.android.calendar.persistence.Calendar
import com.android.calendar.tasks.TaskStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ws.xsoh.etar.R


class MainListPreferences : PreferenceFragmentCompat() {

    private lateinit var mainListViewModel: MainListViewModel

    private var currentCalendars = mutableSetOf<Long>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        currentCalendars.clear()

        addGeneralPreferences(screen)

        preferenceScreen = screen
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.preferences_title)
        parentFragmentManager.setFragmentResultListener(AddOfflineCalendarDialogFragment.TASK_LIST_CHANGED,
                viewLifecycleOwner) { _, _ -> updateTaskListPreferences(preferenceScreen) }

        val factory = MainListViewModelFactory(requireActivity().application)
        mainListViewModel = ViewModelProvider(this, factory).get(MainListViewModel::class.java)

        // Add an observer on the LiveData returned by getCalendarsOrderedByAccount.
        // The onChanged() method fires when the observed data changes and the activity is
        // in the foreground.
        mainListViewModel.getCalendarsOrderedByAccount().observe(viewLifecycleOwner, Observer<List<Calendar>> { calendars ->
            updateCalendarPreferences(preferenceScreen, calendars)
        })
    }

    private fun updateCalendarPreferences(screen: PreferenceScreen, allCalendars: List<Calendar>) {
        val newCalendars = mutableSetOf<Long>()

        for (calendar in allCalendars) {
            newCalendars.add(calendar.id)

            // add category per account if not already present
            val accountCategoryUniqueKey = "account_category_${calendar.accountName}_${calendar.accountType}"
            var accountCategory = screen.findPreference<PreferenceCategory>(accountCategoryUniqueKey)
            if (accountCategory == null) {
                accountCategory = PreferenceCategory(requireContext()).apply {
                    key = accountCategoryUniqueKey
                    title = calendar.accountName
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.outline_account_circle)
                    order = if (calendar.isLocal) 10 else 12 // offline calendars, offline lists, remote accounts
                    isOrderingAsAdded = false // use alphabetic ordering for children
                }
                screen.addPreference(accountCategory)
            }

            // add preference per calendar if not already present
            val calendarUniqueKey = "calendar_preference_${calendar.id}"
            var calendarPreference = screen.findPreference<Preference>(calendarUniqueKey)
            if (calendarPreference == null) {
                calendarPreference = Preference(requireContext())
                accountCategory.addPreference(calendarPreference)
            }
            calendarPreference.apply {
                key = calendarUniqueKey
                title = if (calendar.displayName.isNullOrBlank()) getString(R.string.preferences_calendar_no_display_name) else calendar.displayName
                fragment = CalendarPreferences::class.java.name
                order = if (calendar.isPrimary) 1 else 2 // primary calendar is first, others are alphabetically ordered below
                icon = getCalendarIcon(calendar.color, calendar.visible, calendar.syncEvents)
                summary = getCalendarSummary(calendar.visible, calendar.syncEvents)
            }
            // pass-through calendar id for CalendarPreferences
            calendarPreference.extras.putLong(CalendarPreferences.ARG_CALENDAR_ID, calendar.id)
        }

        // remove preferences for calendars no longer existing
        val calendarsToDelete = currentCalendars.subtract(newCalendars)
        calendarsToDelete.forEach {
            val calendarUniqueKey = "calendar_preference_${it}"
            screen.removePreferenceRecursively(calendarUniqueKey)
        }

        // remove empty account categories
        val categoriesToDelete = mutableSetOf<PreferenceCategory>()
        for (i in 0 until screen.preferenceCount) {
            val pref = screen.getPreference(i)
            if (pref is PreferenceCategory) {
                if (pref.preferenceCount == 0) {
                    categoriesToDelete.add(pref)
                }
            }
        }
        categoriesToDelete.forEach {
            screen.removePreference(it)
        }

        currentCalendars = newCalendars
    }

    private fun getCalendarSummary(visible: Boolean, syncEvents: Boolean): String {
        return if (!syncEvents) {
            getString(R.string.preferences_list_calendar_summary_sync_off)
        } else if (visible) {
            ""
        } else {
            getString(R.string.preferences_list_calendar_summary_invisible)
        }
    }

    private fun getCalendarIcon(color: Int, visible: Boolean, syncEvents: Boolean): Drawable {
        val icon = if (!syncEvents) {
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_sync_off_light)
        } else if (visible) {
            ContextCompat.getDrawable(requireContext(), R.drawable.circle)
        } else {
            ContextCompat.getDrawable(requireContext(), R.drawable.circle_outline)
        }

        icon!!.mutate().colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        return icon
    }

    private fun addGeneralPreferences(screen: PreferenceScreen) {
        val generalPreference = Preference(requireContext()).apply {
            title = getString(R.string.preferences_list_general)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.outline_settings)
            fragment = GeneralPreferences::class.java.name
        }
        val addCaldavPreference = Preference(requireContext()).apply {
            title = getString(R.string.preferences_list_add_remote)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_add)
        }
        addCaldavPreference.setOnPreferenceClickListener {
            launchDavX5Login()
            true
        }
        val addOfflinePreference = Preference(requireContext()).apply {
            title = getString(R.string.preferences_list_add_offline)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_add)
        }
        addOfflinePreference.setOnPreferenceClickListener {
            addOfflineCalendar()
            true
        }
        screen.addPreference(generalPreference)
        screen.addPreference(addCaldavPreference)
        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.tasks_add_caldav_list)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_add)
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.tasks_add_caldav_list)
                        .setMessage(R.string.tasks_caldav_list_unavailable)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                true
            }
        })
        screen.addPreference(addOfflinePreference)
        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.tasks_add_offline_list)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_add)
            setOnPreferenceClickListener {
                AddOfflineCalendarDialogFragment.forTaskList().show(parentFragmentManager, "addOfflineList")
                true
            }
        })
        updateTaskListPreferences(screen)
    }

    private fun updateTaskListPreferences(screen: PreferenceScreen) {
        val categoryKey = "offline_task_lists"
        val old = screen.findPreference<PreferenceCategory>(categoryKey)
        if (old != null) screen.removePreference(old)
        val lists = TaskStore.get(requireContext()).lists()
        if (lists.isEmpty()) return
        val category = PreferenceCategory(requireContext()).apply {
            key = categoryKey
            title = getString(R.string.tasks_offline_lists)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.outline_account_circle)
            order = 11
            isOrderingAsAdded = false
        }
        // A PreferenceGroup must be attached to the screen before adding children,
        // otherwise AndroidX has no PreferenceManager to allocate their IDs.
        screen.addPreference(category)
        for (list in lists) {
            category.addPreference(Preference(requireContext()).apply {
                key = "task_list_${list.id}"
                title = list.name.ifBlank { getString(R.string.tasks_default_list) }
                icon = getCalendarIcon(
                        if (list.color == 0) requireContext().getColor(R.color.event_center) else list.color,
                        list.visible, true)
                summary = if (list.visible) "" else getString(R.string.tasks_list_hidden)
                fragment = TaskListPreferences::class.java.name
                extras.putLong(TaskListPreferences.ARG_LIST_ID, list.id)
            })
        }
    }

    private fun addOfflineCalendar() {
        val dialog = AddOfflineCalendarDialogFragment()
        dialog.show(parentFragmentManager, "addOfflineCalendar")
    }

    /**
     * Based on https://manual.davx5.com/integration.html
     */
    private fun launchDavX5Login() {
        val davX5Intent = Intent().apply {
            setClassName("at.bitfire.davdroid", "at.bitfire.davdroid.ui.setup.LoginActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (requireActivity().packageManager.resolveActivity(davX5Intent, 0) != null) {
            startActivity(davX5Intent)
        } else {
            // DAVx5 is not installed
            val installIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=at.bitfire.davdroid"))

            // launch market
            if (installIntent.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(installIntent)
            } else {
                // no f-droid market app or Play store installed -> launch browser for f-droid url
                val downloadIntent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/repository/browse/?fdid=at.bitfire.davdroid"))
                if (downloadIntent.resolveActivity(requireActivity().packageManager) != null) {
                    startActivity(downloadIntent)
                } else {
                    Toast.makeText(activity, "No browser available!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.preferences_title)
        updateTaskListPreferences(preferenceScreen)
    }

}
