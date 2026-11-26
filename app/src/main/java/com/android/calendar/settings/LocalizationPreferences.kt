package com.android.calendar.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.android.calendar.HolidayManager
import com.android.calendar.LocalizationSettings
import com.android.calendar.Utils
import ws.xsoh.etar.R

class LocalizationPreferences : PreferenceFragmentCompat() {
    private var busy = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val context = requireContext().applicationContext
            runTask {
                context.contentResolver.openInputStream(uri)?.use {
                    HolidayManager.getInstance(context).importData(it)
                } ?: error("Cannot open file")
                1
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = GeneralPreferences.SHARED_PREFS_NAME
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        listOf("local_lunar" to R.string.localization_lunar,
            "local_festivals" to R.string.localization_festivals,
            "local_terms" to R.string.localization_terms,
            "local_holidays" to R.string.localization_holidays).forEach { (id, label) ->
            val initial = LocalizationSettings.enabled(context, id)
            preferenceScreen.addPreference(SwitchPreference(context).apply {
                key = id
                setTitle(label)
                setDefaultValue(initial)
                isChecked = initial
                setOnPreferenceChangeListener { _, _ ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Utils.sendUpdateWidgetIntent(context)
                    }
                    true
                }
            })
        }
        preferenceScreen.addPreference(Preference(context).apply {
            setTitle(R.string.localization_import)
            setOnPreferenceClickListener {
                if (!busy) picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.localization_title)
    }

    private fun runTask(task: () -> Int) {
        if (busy) return
        busy = true
        val context = requireContext().applicationContext
        Thread {
            val result = runCatching(task)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                if (result.isSuccess) Utils.sendUpdateWidgetIntent(context)
                Toast.makeText(context, if (result.isSuccess)
                    context.getString(R.string.localization_updated, result.getOrThrow())
                else context.getString(R.string.localization_failed), Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
