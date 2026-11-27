package com.android.calendar.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.android.calendar.colorpicker.ColorPickerDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ws.xsoh.etar.R

/** Shared preference presentation. CalendarProvider and TaskStore retain their own persistence. */
internal object SourcePreferences {
    fun visibility(context: Context, key: String, titleRes: Int) =
        MaterialSwitchPreference(context).apply {
            this.key = key
            setTitle(titleRes)
        }

    fun name(context: Context, key: String) = EditTextPreference(context).apply {
        this.key = key
        setTitle(R.string.preferences_calendar_display_name)
        dialogTitle = title
    }

    fun colorIcon(context: Context, color: Int): Drawable =
        ContextCompat.getDrawable(context, R.drawable.circle)!!.mutate().apply {
            setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

    fun color(context: Context, key: String, color: Int, openPicker: () -> Unit) =
        Preference(context).apply {
            this.key = key
            setTitle(R.string.preferences_calendar_color)
            icon = colorIcon(context, color)
            setOnPreferenceClickListener { openPicker(); true }
        }

    @Suppress("DEPRECATION")
    fun showPicker(owner: PreferenceFragmentCompat, picker: ColorPickerDialog, tag: String) {
        picker.setTargetFragment(owner, 0)
        picker.showOnce(owner.parentFragmentManager, tag)
    }

    fun delete(context: Context, titleRes: Int, messageRes: Int, positiveRes: Int,
               negativeRes: Int, onDelete: () -> Unit) = Preference(context).apply {
        setTitle(titleRes)
        setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(context)
                .setMessage(messageRes)
                .setPositiveButton(positiveRes) { _, _ -> onDelete() }
                .setNegativeButton(negativeRes, null)
                .show()
            true
        }
    }

    fun information(context: Context, titleRes: Int) = PreferenceCategory(context).apply {
        setTitle(titleRes)
    }

    fun info(context: Context, label: CharSequence) = Preference(context).apply {
        title = label
        isSelectable = false
    }
}
