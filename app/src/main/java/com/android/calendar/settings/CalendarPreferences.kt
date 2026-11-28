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

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.calendar.Utils
import com.android.calendar.alerts.channelId
import com.android.calendar.icalendar.IcalExporter
import com.android.calendar.icalendar.IcalendarUtils
import com.android.calendar.icalendar.VCalendar
import com.android.calendar.persistence.ICalendarRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ws.xsoh.etar.R


class CalendarPreferences : PreferenceFragmentCompat(), com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener {

    private var calendarId: Long = -1
    private lateinit var calendarRepository: ICalendarRepository
    private lateinit var account: Account
    private var numberOfEvents: Long = -1

    private var mLastCalendarToExport: VCalendar? = null
    private val mExportLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val success = try {
                        activity?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                            IcalendarUtils.writeCalendarToStream(mLastCalendarToExport, outputStream)
                        } ?: false
                    } catch (e: Exception) {
                        Log.e("CalendarPreferences", "Error exporting calendar", e)
                        false
                    }
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(activity, R.string.cal_export_success, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, R.string.cal_export_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        calendarId = requireArguments().getLong(ARG_CALENDAR_ID)
        calendarRepository = ICalendarRepository.get(requireActivity().application)
        account = calendarRepository.queryAccount(calendarId)!!
        numberOfEvents = calendarRepository.queryNumberOfEvents(calendarId)!!

        // use custom data store to save/retrieve calendar preferences in Android's calendar database
        val preferenceManager = preferenceManager
        preferenceManager.preferenceDataStore = CalendarDataStore(requireActivity(), calendarId)

        populatePreferences()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val displayName = preferenceManager.preferenceDataStore!!.getString(DISPLAY_NAME_KEY, null)
        activity?.title = if (displayName.isNullOrBlank()) getString(R.string.preferences_calendar_no_display_name) else displayName
    }

    private fun populatePreferences() {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val isLocalAccount = account.type == CalendarContract.ACCOUNT_TYPE_LOCAL
        val currentColor = preferenceManager.preferenceDataStore!!.getInt(COLOR_KEY, -1)
        val authenticatorInfo = getAuthenticatorInfo(account)

        val visiblePreference = SourcePreferences.visibility(context, VISIBLE_KEY, R.string.preferences_calendar_visible)
        val colorPreference = SourcePreferences.color(context, COLOR_KEY, currentColor, ::displayCalendarColorPicker)
        val displayNamePreference = SourcePreferences.name(context, DISPLAY_NAME_KEY)
        displayNamePreference.setOnPreferenceChangeListener { _, newValue ->
            activity?.title = newValue as String
            true
        }
        val deletePreference = SourcePreferences.delete(context,
                R.string.preferences_calendar_delete, R.string.preferences_calendar_delete_message,
                R.string.preferences_calendar_delete_delete, R.string.preferences_calendar_delete_cancel) {
            calendarRepository.deleteLocalCalendar(account.name, calendarId)
            activity?.supportFragmentManager?.popBackStackImmediate()
        }
        val exportPreference = Preference(context).apply {
            title = getString(R.string.cal_export_calendar_title)
            summary = getString(R.string.cal_export_calendar_summary)
        }
        exportPreference.setOnPreferenceClickListener {
            startExport()
            true
        }
        val configurePreference = Preference(context).apply {
            title = getString(R.string.preferences_calendar_configure_account, authenticatorInfo?.label)
            intent = authenticatorInfo?.intent
        }

        val infoCategory = SourcePreferences.information(context, R.string.preferences_calendar_info_category)

        val numberOfEventsPreference = SourcePreferences.info(context,
                getString(R.string.preferences_calendar_number_of_events, numberOfEvents))
        val accountPreference = Preference(context).apply {
            title = getString(R.string.preferences_calendar_account, authenticatorInfo?.label)
            icon = authenticatorInfo?.icon
            isSelectable = false
        }
        val localAccountPreference = SourcePreferences.info(context, getString(R.string.preferences_calendar_account_local))
        val localAccountInfoPreference = SourcePreferences.info(context, getString(R.string.preferences_list_add_offline_message))

        screen.addPreference(visiblePreference)

        screen.addPreference(SourcePreferences.notifications(context, channelId(calendarId)))

        screen.addPreference(colorPreference)
        if (isLocalAccount) {
            screen.addPreference(displayNamePreference)
        }
        screen.addPreference(exportPreference)
        if (isLocalAccount) {
            screen.addPreference(deletePreference)
        }
        if (authenticatorInfo?.intent != null && !isLocalAccount) {
            screen.addPreference(configurePreference)
        }

        screen.addPreference(infoCategory)

        infoCategory.addPreference(numberOfEventsPreference)
        if (isLocalAccount) {
            infoCategory.addPreference(localAccountPreference)
            infoCategory.addPreference(localAccountInfoPreference)
        } else {
            infoCategory.addPreference(accountPreference)
        }

        preferenceScreen = screen
    }

    private fun displayCalendarColorPicker() {
        if (parentFragmentManager.findFragmentByTag(COLOR_PICKER_DIALOG_TAG) != null) {
            return
        }

        val isTablet = Utils.getConfigBool(context, R.bool.tablet_config)
        val calendarDialogPicker = CalendarColorPickerDialogX.newInstance(calendarId, isTablet)
        SourcePreferences.showPicker(this, calendarDialogPicker, COLOR_PICKER_DIALOG_TAG)
    }

    override fun onColorSelected(color: Int) {
        findPreference<Preference>(COLOR_KEY)?.icon = SourcePreferences.colorIcon(requireContext(), color)
    }

    data class AuthenticatorInfo(val label: String?,
                                 val icon: Drawable?,
                                 val intent: Intent?)

    private fun getAuthenticatorInfo(account: Account): AuthenticatorInfo? {
        val description = getAuthenticatorDescription(account) ?: return null

        return try {
            val pm = activity?.packageManager
            val label = pm?.getResourcesForApplication(description.packageName)?.getString(
                description.labelId)
            val icon = pm?.getDrawable(description.packageName, description.iconId, null)
            val intent = pm?.getLaunchIntentForPackage(description.packageName)

            AuthenticatorInfo(label, icon, intent)

        } catch (e: PackageManager.NameNotFoundException) {
            val errorDialog = MaterialAlertDialogBuilder(requireActivity())
                .setMessage("$e")
                .create()
            errorDialog.show()
            return null
        }
    }

    private fun getAuthenticatorDescription(account: Account): AuthenticatorDescription? {
        val manager = AccountManager.get(context)
        val descriptions = manager.authenticatorTypes
        for (description in descriptions) {
            if (description.type == account.type) {
                return description
            }
        }
        return null
    }

    private fun startExport() {
        val displayName = preferenceManager.preferenceDataStore!!.getString(DISPLAY_NAME_KEY, "calendar")!!
        CoroutineScope(Dispatchers.IO).launch {
            val exporter = IcalExporter(requireContext())
            mLastCalendarToExport = exporter.exportCalendar(calendarId)

            withContext(Dispatchers.Main) {
                if (mLastCalendarToExport != null) {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/calendar"
                        val fileName = displayName.replace("\\W+".toRegex(), "_") + ".ics"
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    mExportLauncher.launch(intent)
                } else {
                    Toast.makeText(activity, R.string.cal_export_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val COLOR_PICKER_DIALOG_TAG = "CalendarColorPickerDialog"

        const val ARG_CALENDAR_ID = "calendarId"

        const val VISIBLE_KEY = "visible"
        const val COLOR_KEY = "color"
        const val DISPLAY_NAME_KEY = "displayName"
    }

}
