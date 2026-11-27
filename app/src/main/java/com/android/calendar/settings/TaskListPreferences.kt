package com.android.calendar.settings

import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.android.calendar.Utils
import com.android.calendar.colorpicker.ColorPickerDialog
import com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener
import com.android.calendar.colorpicker.HsvColorComparator
import com.android.calendar.tasks.TaskAlarmReceiver
import com.android.calendar.tasks.TaskStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ws.xsoh.etar.R

/** Offline reminder/task list settings. Uses the existing task store and preference UI. */
class TaskListPreferences : PreferenceFragmentCompat(), OnColorSelectedListener {
    private val listId: Long get() = requireArguments().getLong(ARG_LIST_ID)
    private lateinit var store: TaskStore

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        store = TaskStore.get(requireContext())
        populate()
    }

    override fun onResume() {
        super.onResume()
        val list = store.findList(listId)
        if (list == null) {
            parentFragmentManager.popBackStack()
            return
        }
        activity?.title = displayName(list)
    }

    private fun displayName(list: TaskStore.TaskList): String =
        list.name.ifBlank { getString(R.string.tasks_default_list) }

    private fun displayColor(list: TaskStore.TaskList): Int =
        if (list.color == 0) requireContext().getColor(R.color.event_center) else list.color

    private fun colorIcon(color: Int): Drawable =
        ContextCompat.getDrawable(requireContext(), R.drawable.circle)!!.mutate().apply {
            setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

    private fun populate() {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        val list = store.findList(listId) ?: return

        screen.addPreference(MaterialSwitchPreference(context).apply {
            key = "task_list_visible"
            title = getString(R.string.tasks_list_visible)
            isPersistent = false
            isChecked = list.visible
            setOnPreferenceChangeListener { _, value ->
                store.setListVisible(listId, value as Boolean)
                true
            }
        })

        if (Build.VERSION.SDK_INT >= 26) {
            screen.addPreference(Preference(context).apply {
                title = getString(R.string.preferences_manage_notifications)
                summary = getString(R.string.tasks_list_notifications_shared)
                setOnPreferenceClickListener {
                    TaskAlarmReceiver.ensureChannel(requireContext())
                    startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, TaskAlarmReceiver.CHANNEL)
                    })
                    true
                }
            })
        }

        screen.addPreference(Preference(context).apply {
            key = COLOR_KEY
            title = getString(R.string.preferences_calendar_color)
            icon = colorIcon(displayColor(list))
            setOnPreferenceClickListener {
                showColorPicker()
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "task_list_name"
            title = getString(R.string.preferences_calendar_display_name)
            dialogTitle = title
            isPersistent = false
            text = displayName(list)
            setOnPreferenceChangeListener { _, value ->
                val name = (value as String).trim()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(context, R.string.tasks_required,
                            android.widget.Toast.LENGTH_SHORT).show()
                    false
                } else {
                    store.renameList(listId, name)
                    activity?.title = name
                    true
                }
            }
        })
        if (listId != 1L) {
            screen.addPreference(Preference(context).apply {
                title = getString(R.string.tasks_delete_list)
                setOnPreferenceClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                            .setMessage(R.string.tasks_delete_list_confirm)
                            .setPositiveButton(R.string.tasks_delete) { _, _ ->
                                store.deleteList(listId)
                                parentFragmentManager.popBackStack()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    true
                }
            })
        }

        val info = PreferenceCategory(context).apply {
            title = getString(R.string.tasks_list_info)
        }
        screen.addPreference(info)
        val items = store.tasks(listId)
        val reminders = items.count { it.due != null }
        val tasks = items.size - reminders
        fun addInfo(label: String) {
            info.addPreference(Preference(context).apply {
                title = label
                isSelectable = false
            })
        }
        addInfo(resources.getQuantityString(R.plurals.tasks_list_reminder_count, reminders, reminders))
        addInfo(resources.getQuantityString(R.plurals.tasks_list_task_count, tasks, tasks))
        addInfo(getString(R.string.tasks_offline_list))
        addInfo(getString(R.string.tasks_offline_list_message))
    }

    @Suppress("DEPRECATION")
    private fun showColorPicker() {
        if (parentFragmentManager.findFragmentByTag(COLOR_DIALOG_TAG) != null) return
        val list = store.findList(listId) ?: return
        val colors = resources.getIntArray(R.array.defaultCalendarColors)
                .toTypedArray().sortedWith(HsvColorComparator()).toIntArray()
        val size = if (Utils.getConfigBool(context, R.bool.tablet_config))
            ColorPickerDialog.SIZE_LARGE else ColorPickerDialog.SIZE_SMALL
        ColorPickerDialog.newInstance(R.string.calendar_color_picker_dialog_title,
                colors, displayColor(list), 4, size).apply {
            setTargetFragment(this@TaskListPreferences, 0)
            show(parentFragmentManager, COLOR_DIALOG_TAG)
        }
    }

    override fun onColorSelected(color: Int) {
        store.setListColor(listId, color)
        findPreference<Preference>(COLOR_KEY)?.icon = colorIcon(color)
    }

    companion object {
        const val ARG_LIST_ID = "list_id"
        private const val COLOR_KEY = "task_list_color"
        private const val COLOR_DIALOG_TAG = "TaskListColorPicker"
    }
}
