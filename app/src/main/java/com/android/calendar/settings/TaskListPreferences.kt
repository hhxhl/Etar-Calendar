package com.android.calendar.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.calendar.colorpicker.ColorPickerDialog
import com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener
import com.android.calendar.tasks.TaskAlarmReceiver
import com.android.calendar.tasks.TaskStore
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

    private fun populate() {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        val list = store.findList(listId) ?: return

        screen.addPreference(SourcePreferences.visibility(context, "task_list_visible", R.string.tasks_list_visible).apply {
            isPersistent = false
            isChecked = list.visible
            setOnPreferenceChangeListener { _, value ->
                store.setListVisible(listId, value as Boolean)
                true
            }
        })

        screen.addPreference(SourcePreferences.notifications(context, TaskAlarmReceiver.CHANNEL,
                R.string.tasks_list_notifications_shared) {
            TaskAlarmReceiver.ensureChannel(requireContext())
        })

        screen.addPreference(SourcePreferences.color(context, COLOR_KEY, displayColor(list), ::showColorPicker))
        screen.addPreference(SourcePreferences.name(context, "task_list_name").apply {
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
            screen.addPreference(SourcePreferences.delete(context, R.string.tasks_delete_list,
                    R.string.tasks_delete_list_confirm, R.string.tasks_delete, android.R.string.cancel) {
                store.deleteList(listId)
                parentFragmentManager.popBackStack()
            })
        }

        val info = SourcePreferences.information(context, R.string.tasks_list_info)
        screen.addPreference(info)
        val items = store.tasks(listId)
        fun addInfo(label: String) {
            info.addPreference(SourcePreferences.info(context, label))
        }
        addInfo(resources.getQuantityString(R.plurals.tasks_list_reminder_count, items.size, items.size))
        addInfo(getString(R.string.tasks_offline_list))
        addInfo(getString(R.string.tasks_offline_list_message))
    }

    @Suppress("DEPRECATION")
    private fun showColorPicker() {
        if (parentFragmentManager.findFragmentByTag(COLOR_DIALOG_TAG) != null) return
        val list = store.findList(listId) ?: return
        val colors = ColorPickerDialog.defaultColors(requireContext(), displayColor(list))
        val size = ColorPickerDialog.paletteSize(requireContext())
        // Resolve the manager from the attached preference fragment, not the
        // new dialog (which has not been added to a FragmentManager yet).
        val picker = ColorPickerDialog.newInstance(R.string.calendar_color_picker_dialog_title,
                colors, displayColor(list), 4, size)
        SourcePreferences.showPicker(this, picker, COLOR_DIALOG_TAG)
    }

    override fun onColorSelected(color: Int) {
        store.setListColor(listId, color)
        findPreference<Preference>(COLOR_KEY)?.icon = SourcePreferences.colorIcon(requireContext(), color)
    }

    companion object {
        const val ARG_LIST_ID = "list_id"
        private const val COLOR_KEY = "task_list_color"
        private const val COLOR_DIALOG_TAG = "TaskListColorPicker"
    }
}
