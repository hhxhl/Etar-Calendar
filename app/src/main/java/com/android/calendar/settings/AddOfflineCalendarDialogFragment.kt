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

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.android.calendar.persistence.CalendarRepository
import com.android.calendar.tasks.TaskStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import ws.xsoh.etar.R


class AddOfflineCalendarDialogFragment : DialogFragment() {

    private val isTaskList: Boolean get() = arguments?.getBoolean(ARG_TASK_LIST) == true
    private val listId: Long get() = arguments?.getLong(ARG_LIST_ID) ?: 0L

    private lateinit var nameEditText: EditText
    private lateinit var nameEditTextLayout: TextInputLayout

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.add_offline_calendar_dialog, null)
            nameEditText = view.findViewById(R.id.offline_calendar_name)
            nameEditTextLayout = view.findViewById(R.id.offline_calendar_name_layout)
            if (isTaskList && listId > 0) {
                val list = TaskStore.get(requireContext()).lists().firstOrNull { it.id == listId }
                nameEditText.setText(list?.name?.ifBlank { getString(R.string.tasks_default_list) } ?: "")
            }
            nameEditText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    nameEditTextLayout.error = null
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }
            })

            val builder = MaterialAlertDialogBuilder(it).apply {
                setView(view)
                if (isTaskList) {
                    setTitle(if (listId == 0L) R.string.tasks_add_offline_list else R.string.tasks_edit_list)
                    if (listId == 0L) setMessage(R.string.tasks_offline_list_message)
                } else {
                    setTitle(R.string.preferences_list_add_offline_title)
                    setMessage(R.string.preferences_list_add_offline_message)
                }
            }
            val dialog = builder.create()

            // set listener in onResume to work around auto-dismiss of AlertDialog.Builder
            // https://stackoverflow.com/a/10661281/1600685
            val emptyClickListener: DialogInterface.OnClickListener? = null
            val positiveLabel = if (!isTaskList) R.string.preferences_list_add_offline_button
                    else if (listId == 0L) R.string.tasks_add_list_button else android.R.string.ok
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(positiveLabel), emptyClickListener)
            if (isTaskList && listId > 1L) {
                dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.tasks_delete), emptyClickListener)
            }
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.preferences_list_add_offline_cancel), emptyClickListener)

            return dialog
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onResume() {
        super.onResume()
        val alertDialog = dialog as AlertDialog
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            saveItem()
        }
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            alertDialog.cancel()
        }
        if (isTaskList && listId > 1L) {
            alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.tasks_delete_list_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.tasks_delete) { _, _ ->
                            TaskStore.get(requireContext()).deleteList(listId)
                            notifyTaskListsChanged()
                            dismiss()
                        }.show()
            }
        }
    }

    private fun saveItem() {
        val displayName = nameEditText.text.toString()
        if (if (isTaskList) displayName.isBlank() else displayName.isEmpty()) {
            nameEditTextLayout.error = getString(if (isTaskList) R.string.tasks_required
                    else R.string.preferences_list_add_offline_error_empty)
            return
        }
        if (isTaskList) {
            val store = TaskStore.get(requireContext())
            if (listId == 0L) store.addList(displayName) else store.renameList(listId, displayName)
            notifyTaskListsChanged()
        } else {
            val repository = CalendarRepository(requireActivity().application)
            repository.addLocalCalendar(getString(R.string.offline_account_name), displayName)
        }
        dismiss()
    }

    private fun notifyTaskListsChanged() {
        parentFragmentManager.setFragmentResult(TASK_LIST_CHANGED, Bundle())
    }

    companion object {
        const val TASK_LIST_CHANGED = "offline_task_lists_changed"
        private const val ARG_TASK_LIST = "task_list"
        private const val ARG_LIST_ID = "list_id"

        fun forTaskList(listId: Long = 0L) = AddOfflineCalendarDialogFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_TASK_LIST, true)
                putLong(ARG_LIST_ID, listId)
            }
        }
    }
}
